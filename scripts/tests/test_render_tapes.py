"""Execute the tape wrapper in an isolated checkout with a sandboxing fake Mill."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RenderTapesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "checkout with spaces"
        (self.root / "scripts").mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/render-tapes.sh", self.root / "scripts")
        mill = self.root / "mill"
        mill.write_text('''#!/usr/bin/python3
import json, os
from pathlib import Path
root = Path.cwd()
with (root / "calls.jsonl").open("a") as out:
    out.write(json.dumps({"args": __import__("sys").argv[1:], "output": os.environ["GLYPHORA_TAPE_OUTPUT"]}) + "\\n")
sandbox = root / "mill-sandbox"
sandbox.mkdir(exist_ok=True)
os.chdir(sandbox)
output = Path(os.environ["GLYPHORA_TAPE_OUTPUT"])
output.mkdir(parents=True, exist_ok=True)
(output / "fixture.cast").write_text("fixture recording")
''')
        mill.chmod(0o755)

    def run_script(self, args=(), output=None):
        env = {"PATH": os.environ["PATH"]}
        if output is not None:
            env["GLYPHORA_TAPE_OUTPUT"] = output
        result = subprocess.run(["bash", str(self.root / "scripts/render-tapes.sh"), *args],
                                cwd=self.temp.name, env=env, text=True, capture_output=True, timeout=10)
        return result

    def test_paths_are_absolute_after_changing_to_checkout(self):
        for output in (None, "relative/tapes", "relative spaced/tapes", str(self.root / "absolute spaced")):
            with self.subTest(output=output):
                result = self.run_script(output=output)
                self.assertEqual(result.returncode, 0, result.stderr)
                call = json.loads((self.root / "calls.jsonl").read_text().splitlines()[-1])
                expected = (self.root / (output or "build/tapes")).resolve()
                self.assertEqual(call["output"], str(expected))
                self.assertEqual(call["args"], ["test-support.test.testOnly", "io.worxbend.tui.testsupport.TapeSpec"])
                self.assertTrue((expected / "fixture.cast").is_file())

    def test_pairs_are_preserved(self):
        result = self.run_script(["first", "SuiteOne", "second", "SuiteTwo"])
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line)["args"] for line in (self.root / "calls.jsonl").read_text().splitlines()]
        self.assertEqual(calls, [["first.test.testOnly", "SuiteOne"], ["second.test.testOnly", "SuiteTwo"]])

    def test_odd_arguments_fail_before_any_mill_call(self):
        for args in (["orphan"], ["module", "Suite", "orphan"]):
            with self.subTest(args=args):
                result = self.run_script(args)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("pairs", result.stderr)
                self.assertFalse((self.root / "calls.jsonl").exists())
