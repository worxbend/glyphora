#!/usr/bin/env python3
"""Repair narrowly identified Scala 3.9.0 external-Scaladoc defects in doc jars.

Enum entries are rendered on the enum page, not its companion. Retarget only
known enum pages, and only when the unchanged fragment exists under Enum-entries
in the actual dependency HTML. Never infer member-ID hashes.

Scaladoc also links inherited method parameters and Text.style without emitting
IDs for their declaration spans. Give those exact, existing declaration spans
the IDs Scaladoc already uses in incoming links. No text or links are removed.
The source jar is immutable; the repaired artifact is written separately.
"""
import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit
from zipfile import ZipFile

API_ROOT = "https://worxbend.github.io/glyphora/api/"
ENUMS = {
    "core": {"MouseEventKind", "DiffDirective", "GlyphSupport", "Event", "Direction"},
    "terminal": {"MouseCaptureMode"},
    "widgets": {"ColorPickerMode", "Overflow"},
}
# (page, enclosing documented member or constructor, parameter, incoming ID).
PARAMETERS = (
    ("io/worxbend/tui/core/Measured.html", "widthAt-fffff2e4", "height", "height-0"),
    ("io/worxbend/tui/core/Measured.html", "heightAt-fffff2e4", "width", "width-0"),
    ("io/worxbend/tui/core/Text.html", None, "style", "style-0"),
)


class Page(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.offsets = [0]
        for line in html.splitlines(keepends=True):
            self.offsets.append(self.offsets[-1] + len(line))
        self.ids = set()
        self.links = []
        self.parameters = []
        self.stack = []
        self.parameter = None
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        offset = self.offsets[self.getpos()[0] - 1] + self.getpos()[1]
        if "id" in attrs:
            self.ids.add(attrs["id"])
        if tag == "a" and (attrs.get("href") or "").startswith(API_ROOT):
            self.links.append((attrs["href"], offset, self.get_starttag_text()))
        owner = next((a["id"] for _, a in reversed(self.stack)
                      if "documentableElement" in a.get("class", "").split() and "id" in a), None)
        signature = any("signature" in a.get("class", "").split() for _, a in self.stack)
        if tag == "span" and "data-unresolved-link" in attrs and attrs.get("t") == "n" and signature:
            self.parameter = (owner, offset, self.get_starttag_text())
        if tag not in {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}:
            self.stack.append((tag, attrs))

    def handle_data(self, data):
        if self.parameter:
            self.parameters.append((*self.parameter, data))
            self.parameter = None

    def handle_endtag(self, tag):
        self.parameter = None
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break


def edits(html, replacements):
    for offset, before, after in sorted(replacements, reverse=True):
        if html[offset:offset + len(before)] != before:
            raise ValueError("HTML changed while applying a repair")
        html = html[:offset] + after + html[offset + len(before):]
    return html


def parameter_anchors(name, html):
    changes = []
    for path, owner, parameter, anchor in PARAMETERS:
        if name != path:
            continue
        page = Page(html)
        if anchor in page.ids:
            continue  # An upstream fix needs no repair.
        matches = [(offset, tag) for parent, offset, tag, text in page.parameters
                   if parent == owner and text == parameter]
        if len(matches) != 1:
            raise ValueError(f"Expected one declaration span for {path} {owner}({parameter}), got {len(matches)}")
        offset, tag = matches[0]
        html = edits(html, [(offset, tag, tag[:-1] + f' id="{anchor}">')])
        changes.append({"page": name, "parameter": parameter, "id": anchor, "owner": owner})
    return html, changes


def enum_links(html, targets):
    replacements = []
    repairs = []
    for href, offset, tag in Page(html).links:
        url = urlsplit(href)
        dependency, _, path = unquote(url.path.removeprefix("/glyphora/api/")).partition("/")
        original = targets.get(dependency, {}).get(path)
        fragment = unquote(url.fragment)
        if original is not None and (not fragment or fragment in original.ids):
            continue
        enum = Path(path).name.removesuffix("$.html")
        if not path.endswith("$.html") or enum not in ENUMS.get(dependency, set()) or not fragment:
            continue  # Unknown defects stay visible to the strict corpus validator.
        destination = path.removesuffix("$.html") + ".html"
        target = targets.get(dependency, {}).get(destination)
        if target is None or "Enum-entries" not in target.ids or fragment not in target.ids:
            raise ValueError(f"No real enum entry destination for {href}")
        repaired = API_ROOT + dependency + "/" + destination + "#" + url.fragment
        replacements.append((offset, tag, tag.replace(href, repaired)))
        repairs.append({"from": href, "to": repaired})
    return edits(html, replacements), repairs


def normalize(source, output, dependencies):
    targets = {}
    for dependency, jar_path in dependencies.items():
        with ZipFile(jar_path) as jar:
            targets[dependency] = {name: Page(jar.read(name).decode("utf-8"))
                                   for name in jar.namelist() if name.endswith(".html")}
    repairs = []
    with ZipFile(source) as jar, ZipFile(output, "w") as result:
        for info in jar.infolist():
            data = jar.read(info.filename)
            if info.filename.endswith(".html"):
                html, added = parameter_anchors(info.filename, data.decode("utf-8"))
                html, links = enum_links(html, targets)
                repairs.extend(added)
                repairs.extend({"page": info.filename, **link} for link in links)
                data = html.encode("utf-8")
            result.writestr(info, data)
    return repairs


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dependency", action="append", default=[])
    args = parser.parse_args()
    dependencies = dict(value.split("=", 1) for value in args.dependency)
    if args.source.resolve() == args.output.resolve():
        parser.error("source and output must differ")
    print(json.dumps({"module": args.module,
                      "repairs": normalize(args.source, args.output, dependencies)}, indent=2))
