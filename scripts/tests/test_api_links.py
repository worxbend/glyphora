"""Unit failure probes for the focused generated-API navigation check."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location("api_links", Path(__file__).parents[1] / "check-api-links.py")
assert SPEC is not None and SPEC.loader is not None
API = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(API)


class ApiLinksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR", str(Path.home() / ".hermes/cache/scratch")))
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name)
        self.required = (("runtime", "Frame.html", "core/Effect.html#render-1"),)
        self.patcher = patch.object(API, "REQUIRED_LINKS", self.required)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        for module in API.MODULES:
            (self.out / module / "docJar.dest").mkdir(parents=True)
            with ZipFile(self.out / module / "docJar.dest/out.jar", "w"):
                pass
        self.mapping = rf"\Q{self.out / 'core/compile.dest/classes'}\E.*::scaladoc3::{API.API_ROOT}core/"
        self.options(["-external-mappings", self.mapping])
        self.pages(f'<a href="{API.API_ROOT}core/Effect.html#render-1">Effect</a>', '<div id="render-1"></div>')

    def options(self, options):
        (self.out / "runtime/scalaDocOptions.json").write_text(json.dumps({"value": options}))

    def pages(self, source, target):
        for module, name, html in (("runtime", "Frame.html", source), ("core", "Effect.html", target)):
            with ZipFile(self.out / module / "docJar.dest/out.jar", "w") as jar:
                jar.writestr(name, html)

    def test_mapping_and_real_anchor_with_fragment(self):
        self.assertEqual(API.check(self.out)["validated_anchors"], 1)

    def test_unresolved_span_is_not_a_link(self):
        self.pages('<span data-unresolved-link="Effect">Effect</span>', '<div id="render-1"></div>')
        with self.assertRaisesRegex(ValueError, "Missing required anchor"):
            API.check(self.out)

    def test_wrong_published_root_fails(self):
        self.pages('<a href="/api/core/Effect.html#render-1">Effect</a>', '<div id="render-1"></div>')
        with self.assertRaisesRegex(ValueError, "Missing required anchor"):
            API.check(self.out)

    def test_missing_target_fragment_fails(self):
        self.pages(f'<a href="{API.API_ROOT}core/Effect.html#render-1">Effect</a>', '<div id="other"></div>')
        with self.assertRaisesRegex(ValueError, "missing target"):
            API.check(self.out)

    def test_missing_mapping_fails(self):
        self.options([])
        with self.assertRaisesRegex(ValueError, "Missing local classpath mapping"):
            API.check(self.out)

    def test_mapping_does_not_point_at_remote_dependencies(self):
        self.options(["-external-mappings", self.mapping.replace("compile.dest/classes", "unrelated.jar")])
        with self.assertRaisesRegex(ValueError, "Missing local classpath mapping"):
            API.check(self.out)

    def test_unrelated_invalid_anchor_is_reported(self):
        self.pages(f'<a href="{API.API_ROOT}core/Effect.html#render-1">Effect</a>'
                   f'<a href="{API.API_ROOT}core/Missing.html">Missing</a>', '<div id="render-1"></div>')
        self.assertEqual(len(API.check(self.out)["other_invalid_anchors"]), 1)

    def test_bare_href_is_not_an_anchor(self):
        self.assertEqual(API.Page('<a href>empty</a>').links, [])


if __name__ == "__main__":
    unittest.main()
