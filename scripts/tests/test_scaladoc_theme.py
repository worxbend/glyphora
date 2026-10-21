"""Run the dependency-free Node/VM Scaladoc checks in the existing CI discovery."""
from pathlib import Path
import subprocess
import unittest


class ScaladocThemeTests(unittest.TestCase):
    def test_node_vm_theme_and_injection_boundaries(self):
        result = subprocess.run(["node", "--test", str(Path(__file__).with_name("scaladoc.test.mjs"))],
                                text=True, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
