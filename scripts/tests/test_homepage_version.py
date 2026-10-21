"""Keep the homepage's copyable coordinate on the synchronized build release."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HomepageVersionTests(unittest.TestCase):
    def test_homepage_coordinate_matches_shared_publish_version(self):
        versions = re.findall(r'def publishVersion\s*=\s*"([^"\n]+)"', (ROOT / "build.mill").read_text())
        self.assertEqual(len(versions), 1, "expected one synchronized literal publishVersion")
        coordinates = re.findall(r'com\.worxbend::tui-dsl:([^&"\s<>]+)',
                                 (ROOT / "website/src/pages/index.js").read_text())
        self.assertTrue(coordinates, "homepage must contain a copyable tui-dsl coordinate")
        self.assertEqual(coordinates, [versions[0]])
