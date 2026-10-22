"""Fixtures for the actual Scala 3.9.0 enum and parameter HTML defects."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location("normalize", Path(__file__).parents[1] / "normalize-api-links.py")
assert SPEC is not None and SPEC.loader is not None
API = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(API)


class NormalizeApiLinksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR", str(Path.home() / ".hermes/cache/scratch")))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def jar(self, name, pages):
        path = self.root / name
        with ZipFile(path, "w") as jar:
            for page, html in pages.items():
                jar.writestr(page, html)
        return path

    def test_enum_companion_retargets_only_to_existing_unchanged_fragment(self):
        path = "io/worxbend/tui/core/GlyphSupport"
        href = API.API_ROOT + "core/" + path + "$.html#Full-0"
        html = f'<a href="{href}">Full</a><a href>bare</a>'
        source = self.jar("source.jar", {"Page.html": html, "asset.txt": "unchanged"})
        target = self.jar("core.jar", {path + ".html": '<div id="Enum-entries"><div id="Full-0"></div></div>',
                                       path + "$.html": '<div id="permits-fffff3c0"></div>'})
        output = self.root / "output.jar"
        repairs = API.normalize(source, output, {"core": target})
        self.assertEqual(len(repairs), 1)
        self.assertEqual(repairs[0]["to"], href.replace("$.html", ".html"))
        with ZipFile(output) as jar:
            self.assertEqual(jar.read("Page.html").decode(), html.replace("$.html", ".html"))
            self.assertEqual(jar.read("asset.txt"), b"unchanged")
        with ZipFile(source) as jar:
            self.assertEqual(jar.read("Page.html").decode(), html)
        # Idempotence, and no inferred hash/name fallback when the real ID changes.
        self.assertEqual(API.normalize(output, self.root / "again.jar", {"core": target}), [])
        target = self.jar("core.jar", {path + ".html": '<div id="Enum-entries"><div id="Full-1"></div></div>'})
        with self.assertRaisesRegex(ValueError, "No real enum entry"):
            API.normalize(source, output, {"core": target})

    def test_unknown_or_vendor_links_are_not_normalized(self):
        html = (f'<a href="{API.API_ROOT}core/Unknown$.html#Missing-0">unknown</a>'
                '<a href="https://vendor.example/GlyphSupport$.html#Full-0">vendor</a>')
        source = self.jar("source.jar", {"Page.html": html})
        output = self.root / "output.jar"
        self.assertEqual(API.normalize(source, output, {}), [])
        with ZipFile(output) as jar:
            self.assertEqual(jar.read("Page.html").decode(), html)

    def test_parameter_fragment_is_attached_to_exact_existing_declaration(self):
        name = "io/worxbend/tui/core/Measured.html"
        html = ('<div class="documentableElement" id="widthAt-fffff2e4"><div class="signature">'
                '<a class="documentableName">widthAt</a>('
                '<span data-unresolved-link="" t="n">height</span>: Int)</div></div>'
                '<div class="documentableElement" id="heightAt-fffff2e4"><div class="signature">'
                '<span data-unresolved-link="" t="n">width</span>: Int</div></div>')
        result, repairs = API.parameter_anchors(name, html)
        self.assertEqual(len(repairs), 2)
        self.assertIn('<span data-unresolved-link="" t="n" id="height-0">height</span>', result)
        self.assertIn('<span data-unresolved-link="" t="n" id="width-0">width</span>', result)
        self.assertEqual(API.parameter_anchors(name, result), (result, []))
        with self.assertRaisesRegex(ValueError, "Expected one declaration span"):
            API.parameter_anchors(name, html.replace("height</span>", "other</span>"))

    def test_constructor_field_fragment_retains_real_text_and_links(self):
        name = "io/worxbend/tui/core/Text.html"
        html = ('<div class="signature">class Text('
                '<span data-unresolved-link="" t="n">style</span>: <a href="Style.html">Style</a>)</div>')
        result, repairs = API.parameter_anchors(name, html)
        self.assertEqual(repairs[0]["id"], "style-0")
        self.assertEqual(result.replace(' id="style-0"', ''), html)
        with self.assertRaisesRegex(ValueError, "got 2"):
            API.parameter_anchors(name, html + html)


if __name__ == "__main__":
    unittest.main()
