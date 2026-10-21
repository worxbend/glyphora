"""Fixture-only characterization of the Wiki exporter (including current quirks)."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def document(title, body):
    return f"---\ntitle: {title}\n---\n\n{body}\n"


class WikiExportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        self.docs = self.root / "website/docs"
        self.docs.mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/export-wiki.mjs", self.root / "scripts")
        self.output = self.root / "output"

    def export(self, pages, navigation=None):
        for name, source in pages.items():
            (self.docs / f"{name}.md").write_text(source)
        (self.root / "website/docs-navigation.mjs").write_text(
            "export const docsNavigation = " + json.dumps(navigation if navigation is not None else list(pages)) + ";\n")
        return subprocess.run(["node", str(self.root / "scripts/export-wiki.mjs"), "--output", str(self.output)],
                              text=True, capture_output=True, timeout=15)

    def test_frontmatter_anchors_assets_api_and_code_fences(self):
        result = self.export({
            "intro": document("Intro", '[Guide](./guide#section)\n[API](pathname:///api/type)\n'
                              '<img src="/glyphora/gallery/table.svg"/>\n'
                              '```md\n[Example](./guide#inside)\n[Ignored](missing-in-code)\n```\n'
                              '`[Inline](missing-inline)`'),
            "guide": document("Café & API", "## Section\n[Top](#section)"),
        }, ["intro", {"label": "Reference group", "items": ["guide"]}])
        self.assertEqual(result.returncode, 0, result.stderr)
        home = (self.output / "Home.md").read_text()
        self.assertNotIn("title:", home)
        self.assertIn("[Guide](Café-and-API#section)", home)
        self.assertIn("https://worxbend.github.io/glyphora/api/type", home)
        self.assertIn("https://raw.githubusercontent.com/worxbend/glyphora/main/docs/assets/gallery/table.svg", home)
        # Current rewriting is textual, even inside fenced examples; validation skips fences/inline code.
        self.assertIn("```md\n[Example](Café-and-API#inside)\n[Ignored](missing-in-code)\n```", home)
        sidebar = (self.output / "_Sidebar.md").read_text()
        self.assertIn("## Reference group", sidebar)
        self.assertIn("[Café & API](Café-and-API)", sidebar)

    def test_title_collision_currently_last_page_wins_and_output_is_replaced(self):
        self.output.mkdir()
        (self.output / "stale.md").write_text("stale fixture")
        result = self.export({"intro": document("Intro", "home"), "first": document("A & B", "first body"),
                              "second": document("A and B", "second body")})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.output / "stale.md").exists())
        self.assertIn("second body", (self.output / "A-and-B.md").read_text())
        self.assertEqual((self.output / "_Sidebar.md").read_text().count("](A-and-B)"), 2)

    def test_missing_frontmatter_and_title_fail_before_output_removal(self):
        for source, message in (("# Intro", "intro.md is missing YAML front matter"),
                                ("---\nslug: /\n---\nbody", "intro.md is missing a front matter title")):
            with self.subTest(message=message):
                self.output.mkdir(exist_ok=True)
                sentinel = self.output / "sentinel"
                sentinel.write_text("fixture")
                result = self.export({"intro": source})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertTrue(sentinel.exists())

    def test_navigation_mismatch_lists_missing_and_stale(self):
        result = self.export({"intro": document("Intro", "home")}, ["stale"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing from navigation: intro", result.stderr)
        self.assertIn("Missing source files: stale", result.stderr)
        self.assertFalse(self.output.exists())

    def test_broken_prose_link_fails_but_anchor_is_not_validated(self):
        result = self.export({"intro": document("Intro", "[Bad](./unknown#anchor)\n[Anchor](#missing)")})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Broken generated Wiki links:\nHome.md: ./unknown#anchor", result.stderr)
        self.assertTrue((self.output / "Home.md").is_file())

    def test_quoted_title_is_not_yaml_unquoted(self):
        result = self.export({"intro": document("Intro", "home"), "guide": document('"A: B"', "body")})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.output / "A-B.md").is_file())
        self.assertIn('["A: B"](A-B)', (self.output / "_Sidebar.md").read_text())
