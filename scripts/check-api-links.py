#!/usr/bin/env python3
"""Validate the deployed cross-module hrefs in Mill's packaged Scaladoc jars.

Run after all seven published modules' docJar targets, with --out <Mill out>.
Only local jars are read; no publication or network access is needed.
"""
import argparse
from collections import Counter
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit
from zipfile import ZipFile

API_ROOT = "https://worxbend.github.io/glyphora/api/"
MODULES = ("core", "terminal", "widgets", "runtime", "dsl", "macros", "test-support")
REQUIRED_LINKS = (
    ("runtime", "Frame.html", "core/io/worxbend/tui/core/Effect.html"),
    ("dsl", "AccordionElement.html", "widgets/io/worxbend/tui/widgets/AccordionState.html#toggle-fffff3b3"),
    ("dsl", "AnimationClock$.html", "runtime/io/worxbend/tui/runtime/RenderThread$.html#capture-cf"),
    ("test-support", "Pilot.html", "terminal/io/worxbend/tui/terminal/HeadlessBackend.html"),
)


class Page(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.links = []
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            self.ids.add(attrs["id"])
        href = attrs.get("href") or ""
        if tag == "a" and href.startswith(API_ROOT):
            self.links.append(href)


def check(out):
    for module, _, suffix in REQUIRED_LINKS:
        target_module = suffix.split("/", 1)[0]
        options = json.loads((out / module / "scalaDocOptions.json").read_text())["value"]
        classes = (out / target_module / "compile.dest" / "classes").resolve()
        mapping = rf"\Q{classes}\E.*::scaladoc3::{API_ROOT}{target_module}/"
        if not any(options[i:i + 2] == ["-external-mappings", mapping] for i in range(len(options))):
            raise ValueError(f"Missing local classpath mapping: {module} -> {target_module}")
    pages = {}
    for module in MODULES:
        with ZipFile(out / module / "docJar.dest" / "out.jar") as jar:
            pages[module] = {
                name: Page(jar.read(name).decode("utf-8"))
                for name in jar.namelist() if name.endswith(".html")
            }
    counts = Counter()
    examples = {}
    errors = []
    for module, module_pages in pages.items():
        for source, page in module_pages.items():
            for href in page.links:
                url = urlsplit(href)
                target_module, _, target_path = unquote(url.path.removeprefix("/glyphora/api/")).partition("/")
                if target_module not in MODULES:
                    continue  # Vendor/external documentation is outside the local mapped corpus.
                target = pages[target_module].get(target_path)
                if target is None or (url.fragment and unquote(url.fragment) not in target.ids):
                    errors.append(f"{module}/{source}: missing target {href}")
                    continue
                edge = (module, target_module)
                counts[edge] += 1
                examples.setdefault(edge, {"source": f"{module}/{source}", "href": href})
    required = []
    for module, filename, suffix in REQUIRED_LINKS:
        href = API_ROOT + suffix
        sources = [name for name, page in pages[module].items()
                   if name.rsplit("/", 1)[-1] == filename and href in page.links]
        problem = next((error for error in errors if error.endswith(href)), None)
        if not sources or problem:
            raise ValueError(problem or f"Missing required anchor in {module}/{filename}: {href}")
        required.append({"source": f"{module}/{sources[0]}", "href": href})
    if errors:
        raise ValueError("Invalid internal API anchors:\n" + "\n".join(errors))
    return {
        "required_links": required,
        "validated_anchors": sum(counts.values()),
        "other_invalid_anchors": errors,
        "edges": [dict(source=s, target=t, count=n, example=examples[(s, t)])
                  for (s, t), n in sorted(counts.items())],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("out"))
    args = parser.parse_args()
    print(json.dumps(check(args.out), indent=2))
