"""Consumer quickstart and actionable workflow-dependency regressions."""

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class StandardsTests(unittest.TestCase):
    def test_missing_yaml_explains_install_command_without_traceback(self):
        result = subprocess.run(
            [sys.executable, "-S", str(ROOT / "scripts/validate-workflows.py")],
            env={**os.environ, "PYTHONPATH": ""}, text=True, capture_output=True, timeout=10,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pip install -r scripts/requirements-ci.txt", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertIn("PyYAML==", (ROOT / "scripts/requirements-ci.txt").read_text())

    def test_quickstart_checks_pin_and_preserves_program(self):
        tool = module("verify-scala-cli")
        source = (ROOT / "examples/scala-cli/hello.scala").read_text()
        version = tool.DEPENDENCY.findall(source)[0]
        local = tool.local_source(source, version)
        self.assertNotIn("//> using dep", local)
        self.assertIn("//> using scala 3.9.0", local)
        self.assertEqual(local.split("package ", 1)[1], source.split("package ", 1)[1])
        with self.assertRaisesRegex(ValueError, "pin"):
            tool.local_source(source.replace(f"tui-dsl:{version}", "tui-dsl:0.0.0"), version)

    def test_quickstart_decodes_mill_classpaths(self):
        tool = module("verify-scala-cli")
        for entry in ("qref:v1:hash:/explicit path/classes", "ref:v0:hash:/explicit path/classes", "/explicit path/classes"):
            self.assertEqual(tool.classpath_path(entry), "/explicit path/classes")

    def test_ci_cannot_omit_quickstart_compilation(self):
        tool = module("validate-workflows")
        workflows = {p.name: tool.load(p) for p in (ROOT / ".github/workflows").glob("*.yml")}
        actions = {str(p): tool.load(p) for p in (ROOT / ".github/actions").rglob("action.yml")}
        steps = workflows["ci.yml"]["jobs"]["build"]["steps"]
        steps[:] = [step for step in steps if "verify-scala-cli.py" not in step.get("run", "")]
        with self.assertRaisesRegex(ValueError, "quickstart"):
            tool.validate(workflows, actions)


if __name__ == "__main__":
    unittest.main()
