"""Regression tests for the gallery screenshot drift check."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


class GalleryAssetTests(unittest.TestCase):
    def setUp(self):
        self.tool = module("gallery-assets")
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / "checkout"
        self.rendered = Path(temp.name) / "rendered"
        (self.root / "website" / "docs").mkdir(parents=True)
        self.rendered.mkdir()
        (self.root / "README.md").write_text('<img src="docs/assets/gallery/table.svg"/>\n')
        (self.root / "website" / "docs" / "widgets.md").write_text('<img src="/glyphora/gallery/barChart.svg"/>\n')
        for name in ("table", "barChart", "gauge"):
            (self.rendered / f"{name}.svg").write_text(f"<svg>{name}</svg>")

    def commit(self, name, content):
        directory = self.root / "docs" / "assets" / "gallery"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"{name}.svg").write_text(content)

    def test_references_come_from_readme_and_docs_pages(self):
        self.assertEqual(self.tool.referenced(self.root), {"table", "barChart"})

    def test_sync_commits_exactly_the_referenced_screenshots(self):
        self.commit("stale", "<svg/>")
        self.assertEqual(self.tool.sync(self.root, self.rendered), ["barChart", "table"])
        self.assertEqual(self.tool.committed(self.root), {"table", "barChart"})
        self.assertEqual(self.tool.problems(self.root, self.rendered), [])

    def test_a_changed_render_is_drift(self):
        self.tool.sync(self.root, self.rendered)
        (self.rendered / "table.svg").write_text("<svg>table, redrawn</svg>")
        self.assertEqual(
            self.tool.problems(self.root, self.rendered),
            ["docs/assets/gallery/table.svg differs from what the gallery renders now"],
        )

    def test_missing_and_unreferenced_screenshots_are_drift(self):
        self.commit("table", "<svg>table</svg>")
        self.commit("gauge", "<svg>gauge</svg>")
        self.assertEqual(
            self.tool.problems(self.root, self.rendered),
            [
                "barChart.svg is shown by the docs but not committed under docs/assets/gallery",
                "docs/assets/gallery/gauge.svg is committed but no page shows it",
            ],
        )

    def test_a_reference_to_a_factory_with_no_page_is_drift(self):
        self.commit("table", "<svg>table</svg>")
        self.commit("barChart", "<svg>barChart</svg>")
        (self.rendered / "barChart.svg").unlink()
        self.assertEqual(
            self.tool.problems(self.root, self.rendered),
            ["barChart.svg was not rendered: no gallery page for `barChart`"],
        )
        with self.assertRaises(SystemExit):
            self.tool.sync(self.root, self.rendered)

    def test_the_repository_is_in_sync_with_its_own_references(self):
        # whatever the checkout commits is exactly what its prose shows; the bytes are checked by CI after a render
        self.assertEqual(self.tool.committed(ROOT), self.tool.referenced(ROOT))


if __name__ == "__main__":
    unittest.main()
