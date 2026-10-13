"""Executable regression tests for discovery, bounded smoke and workflow policy."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


class ExampleDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.tool = module("ci-examples")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.examples = Path(self.temp.name)

    def add(self, name):
        directory = self.examples / name
        directory.mkdir()
        (directory / "package.mill").write_text("// Explicit discovery fixture\n")
        return directory

    def test_sorted_discovery_ignores_aggregator(self):
        self.add("z-last")
        self.add("hello-world")
        (self.examples / "package.mill").write_text("// Aggregator fixture\n")
        self.assertEqual(self.tool.discover(self.examples), ["hello-world", "z-last"])

    def test_empty_discovery_fails(self):
        with self.assertRaisesRegex(ValueError, "no example"):
            self.tool.discover(self.examples)

    def test_missing_package_fails(self):
        self.add("valid")
        (self.examples / "missing").mkdir()
        with self.assertRaisesRegex(ValueError, "missing regular"):
            self.tool.discover(self.examples)

    def test_nested_package_fails(self):
        nested = self.add("valid") / "nested"
        nested.mkdir()
        (nested / "package.mill").write_text("// Nested fixture\n")
        with self.assertRaisesRegex(ValueError, "nested"):
            self.tool.discover(self.examples)

    def test_unsafe_names_fail(self):
        for name in ("a b", "a\nb", "a$(touch-pwn)", "a.b", "--option", "a--b", "é"):
            with self.subTest(name=name):
                path = self.add(name)
                with self.assertRaisesRegex(ValueError, "unsafe"):
                    self.tool.discover(self.examples)
                (path / "package.mill").unlink()
                path.rmdir()

    def test_symlink_module_fails(self):
        self.add("valid")
        (self.examples / "alias").symlink_to(self.examples / "valid", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.tool.discover(self.examples)

    def test_symlink_package_fails(self):
        path = self.add("valid")
        (path / "package.mill").unlink()
        (path / "package.mill").symlink_to(ROOT / "examples/package.mill")
        with self.assertRaisesRegex(ValueError, "missing regular"):
            self.tool.discover(self.examples)

    def test_native_selectors_separate_every_task(self):
        self.assertEqual(self.tool.native_selectors(["hello-world", "counter"]),
                         ["examples.hello-world.nativeImage", "+", "examples.counter.nativeImage"])
        for names in ([], ["hello.world"], ["counter;false"]):
            with self.subTest(names=names), self.assertRaises(ValueError):
                self.tool.native_selectors(names)

    def test_real_discovery_covers_every_example(self):
        names = self.tool.discover(ROOT / "examples")
        self.assertEqual(set(names), {p.name for p in (ROOT / "examples").iterdir() if p.is_dir()})
        result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-examples.py"), "discover"],
                                check=True, text=True, capture_output=True, timeout=10)
        self.assertEqual(json.loads(result.stdout), names)

    def test_invalid_matrix_value_never_invokes_mill(self):
        with patch.object(sys, "argv", ["ci-examples.py", "test", "--example", "counter;false"]), \
                patch.object(self.tool.subprocess, "run") as run, self.assertRaises(SystemExit):
            self.tool.main()
        run.assert_not_called()


class NativeSmokeTests(unittest.TestCase):
    def setUp(self):
        self.tool = module("ci-examples")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.binary = Path(self.temp.name) / "fixture-binary"

    def fixture(self, source):
        self.binary.write_text(f"#!{sys.executable}\n" + source)
        self.binary.chmod(0o755)

    def test_expected_exit_passes_with_no_tty(self):
        self.fixture("import os, sys\nassert not os.isatty(0) and not os.isatty(1)\n"
                     "print('glyphora: terminal not supported: explicit fixture')\nsys.exit(1)\n")
        self.assertIn("terminal not supported", self.tool.smoke(self.binary))

    def test_missing_binary_fails(self):
        with self.assertRaisesRegex(ValueError, "missing executable"):
            self.tool.smoke(self.binary)

    def test_wrong_status_or_message_fails(self):
        for status, message in ((0, "terminal not supported"), (1, "unrelated failure"), (2, "terminal not supported")):
            self.fixture(f"import sys\nprint({message!r})\nsys.exit({status})\n")
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "unclean"):
                self.tool.smoke(self.binary)

    def test_stacktrace_after_expected_message_fails(self):
        self.fixture("import sys\nprint('terminal not supported\\nException in thread main')\nsys.exit(1)\n")
        with self.assertRaisesRegex(ValueError, "stack trace"):
            self.tool.smoke(self.binary)

    def test_message_then_hang_is_bounded(self):
        self.fixture("import time\nprint('terminal not supported', flush=True)\ntime.sleep(60)\n")
        with self.assertRaisesRegex(ValueError, "timed out"):
            self.tool.smoke(self.binary, timeout=0.2)

    def test_inherited_pipe_child_is_killed_on_timeout(self):
        # A child holding stdout open used to keep communicate hanging after the
        # parent exited; killing only the parent does not close the child's pipe.
        self.fixture("import subprocess, sys\nsubprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\n"
                     "print('terminal not supported')\nsys.exit(1)\n")
        with self.assertRaisesRegex(ValueError, "timed out"):
            self.tool.smoke(self.binary, timeout=0.2)


class ReleaseVersionTests(unittest.TestCase):
    def setUp(self):
        self.tool = module("validate-release-version")
        self.versions = dict.fromkeys(self.tool.MODULES, "0.18.0")

    def test_all_versions_match(self):
        self.assertEqual(self.tool.validate_versions("v0.18.0", self.versions), "0.18.0")

    def test_every_module_mismatch_fails(self):
        for module_name in self.versions:
            bad = {**self.versions, module_name: "0.17.0"}
            with self.subTest(module=module_name), self.assertRaisesRegex(ValueError, module_name):
                self.tool.validate_versions("v0.18.0", bad)

    def test_missing_or_extra_module_fails(self):
        for bad in ({"core": "0.18.0"}, {**self.versions, "extra": "0.18.0"}):
            with self.assertRaisesRegex(ValueError, "exactly"):
                self.tool.validate_versions("v0.18.0", bad)

    def test_malformed_tags_not_normalized(self):
        for tag in ("0.18.0", "v0.18.0\n", "v0.18", "v0.18.0-SNAPSHOT.", "v0.18.0;true", "v0.18.0 "):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "invalid release tag"):
                self.tool.validate_versions(tag, self.versions)

    def test_nonstring_version_fails(self):
        with self.assertRaises(ValueError):
            self.tool.validate_versions("v0.18.0", {**self.versions, "core": 18})

    def test_main_queries_all_seven_versions_without_golden_updates(self):
        with patch.dict(os.environ, {"GITHUB_REF_NAME": "v0.18.0", "GLYPHORA_GOLDEN_UPDATE": "never"}), \
                patch.object(self.tool.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, '"0.18.0"')) as run:
            self.tool.main()
        self.assertEqual([call.args[0][-1] for call in run.call_args_list],
                         [f"{name}.publishVersion" for name in self.tool.MODULES])
        self.assertTrue(all("GLYPHORA_GOLDEN_UPDATE" not in call.kwargs["env"] for call in run.call_args_list))
        self.assertTrue(all(call.kwargs["timeout"] > 0 for call in run.call_args_list))


class WorkflowPolicyTests(unittest.TestCase):
    def setUp(self):
        self.tool = module("validate-workflows")
        self.workflows = {p.name: self.tool.load(p) for p in (ROOT / ".github/workflows").glob("*.yml")}
        self.actions = {str(p): self.tool.load(p) for p in (ROOT / ".github/actions").rglob("action.yml")}

    def test_current_policy_passes(self):
        self.tool.validate(self.workflows, self.actions)

    def fails(self, text):
        with self.assertRaisesRegex(ValueError, text):
            self.tool.validate(self.workflows, self.actions)

    def test_website_compatibility_tests_cannot_be_skipped(self):
        for filename, job in (("ci.yml", "website-build"), ("docs.yml", "build")):
            with self.subTest(workflow=filename):
                workflows = copy.deepcopy(self.workflows)
                build = next(step for step in workflows[filename]["jobs"][job]["steps"]
                             if step.get("working-directory") == "website")
                build["run"] = build["run"].replace("npm test\n", "")
                with self.assertRaisesRegex(ValueError, "website dependency checks"):
                    self.tool.validate(workflows, self.actions)

    def test_website_audit_failure_cannot_be_hidden(self):
        for filename, job in (("ci.yml", "website-build"), ("docs.yml", "build")):
            with self.subTest(workflow=filename):
                workflows = copy.deepcopy(self.workflows)
                build = next(step for step in workflows[filename]["jobs"][job]["steps"]
                             if step.get("working-directory") == "website")
                build["run"] = build["run"].replace("npm audit --audit-level=critical", "npm audit --audit-level=critical || true")
                with self.assertRaisesRegex(ValueError, "website dependency checks"):
                    self.tool.validate(workflows, self.actions)

    def test_missing_timeout_fails(self):
        self.workflows["ci.yml"]["jobs"]["native-image"].pop("timeout-minutes")
        self.fails("job timeout")

    def test_missing_smoke_step_timeout_fails(self):
        self.workflows["ci.yml"]["jobs"]["native-image"]["steps"][-1].pop("timeout-minutes")
        self.fails("step timeout")

    def test_mutable_action_reference_fails(self):
        self.workflows["ci.yml"]["jobs"]["build"]["steps"][0]["uses"] = "actions/checkout@v7"
        self.fails("SHA-pinned")

    def test_composite_action_is_checked_too(self):
        next(iter(self.actions.values()))["runs"]["steps"][0]["uses"] = "actions/setup-java@v6"
        self.fails("SHA-pinned")

    def test_direct_matrix_shell_interpolation_fails(self):
        self.workflows["ci.yml"]["jobs"]["example"]["steps"][-1]["run"] = "./mill examples.${{ matrix.example }}.test"
        self.fails("interpolated")

    def test_persisted_checkout_credentials_fail(self):
        self.workflows["wiki.yml"]["jobs"]["publish"]["steps"][0]["with"]["persist-credentials"] = "true"
        self.fails("persistent checkout")

    def test_missing_gate_and_failure_bypass_fail(self):
        original = copy.deepcopy(self.workflows)
        self.workflows["ci.yml"]["jobs"]["validated"]["needs"].remove("native-image")
        self.fails("every gate")
        self.workflows = original
        self.workflows["ci.yml"]["jobs"]["validated"]["if"] = "always()"
        self.fails("bypass")

    def test_secrets_not_inherited_into_reusable_gate(self):
        self.workflows["publish.yml"]["jobs"]["validate"]["secrets"] = "inherit"
        self.fails("without secrets")

    def test_publish_cannot_checkout_a_branch(self):
        self.workflows["publish.yml"]["jobs"]["publish"]["steps"][0]["with"]["ref"] = "main"
        self.fails("validated SHA")

    def test_version_checks_precede_secrets(self):
        steps = self.workflows["publish.yml"]["jobs"]["publish"]["steps"]
        versions = next(step for step in steps if step.get("run") == "python3 scripts/validate-release-version.py")
        steps.remove(versions)
        steps.append(versions)
        self.fails("before credentials")

    def test_build_has_no_deployment_permission(self):
        self.workflows["docs.yml"]["jobs"]["build"]["permissions"]["id-token"] = "write"
        self.fails("unexpected permissions")

    def test_credentials_cannot_be_in_wiki_remote(self):
        self.workflows["wiki.yml"]["jobs"]["publish"]["steps"][-1]["run"] += '\ngit clone "https://x-access-token:$GLYPHORA_GIT_TOKEN@github.com/test.wiki.git"'
        self.fails("remote URL")


class ExecutedWorkflowGuardsTests(unittest.TestCase):
    def setUp(self):
        self.tool = module("validate-workflows")

    def test_publish_sha_guard_rejects_empty_or_wrong_commit(self):
        workflow = self.tool.load(ROOT / ".github/workflows/publish.yml")
        guard = next(step["run"] for step in workflow["jobs"]["publish"]["steps"]
                     if "VALIDATED_SHA" in step.get("env", {}))
        with tempfile.TemporaryDirectory() as temp:
            git = Path(temp) / "git"
            git.write_text("#!/bin/sh\nprintf '%s\\n' \"$FIXTURE_HEAD\"\n")
            git.chmod(0o755)
            for validated, head, event, status in (("abc", "abc", "abc", 0), ("", "abc", "abc", 1),
                                                   ("abc", "def", "abc", 1), ("abc", "abc", "def", 1)):
                env = {**os.environ, "PATH": temp + os.pathsep + os.environ["PATH"],
                       "VALIDATED_SHA": validated, "FIXTURE_HEAD": head, "GITHUB_SHA": event}
                result = subprocess.run(["bash", "-e", "-c", guard], env=env, capture_output=True, timeout=5)
                with self.subTest(validated=validated, head=head, event=event):
                    self.assertEqual(result.returncode, status)

    def test_scaladoc_path_decode_handles_current_mill_refs(self):
        workflow = self.tool.load(ROOT / ".github/workflows/docs.yml")
        command = next(step["run"] for step in workflow["jobs"]["build"]["steps"]
                       if step.get("name") == "Generate Scaladoc for every published module")
        decoder = re.search(r"python3 -c '([^']+)'", command)
        self.assertIsNotNone(decoder)
        assert decoder is not None
        for ref in ("ref:v1:12345678:/explicit fixture/doc.jar", "qref:v1:12345678:/explicit fixture/doc.jar",
                    "ref:v0:hash:/explicit fixture/doc.jar", "/explicit fixture/doc.jar"):
            with self.subTest(ref=ref):
                result = subprocess.run([sys.executable, "-c", decoder[1]], input=json.dumps(ref),
                                        text=True, capture_output=True, check=True, timeout=5)
                self.assertEqual(result.stdout.strip(), "/explicit fixture/doc.jar")

    def test_wiki_export_uses_transferred_repository(self):
        with tempfile.TemporaryDirectory() as temp:
            subprocess.run(["node", str(ROOT / "scripts/export-wiki.mjs"), "--output", temp],
                           cwd=ROOT, check=True, capture_output=True, timeout=30)
            sidebar = (Path(temp) / "_Sidebar.md").read_text()
            footer = (Path(temp) / "_Footer.md").read_text()
            self.assertIn("https://github.com/worxbend/glyphora", sidebar)
            self.assertIn("https://worxbend.github.io/glyphora/api/", sidebar)
            self.assertIn("https://github.com/worxbend/glyphora/tree/main/website/docs", footer)
            self.assertNotIn("oleksandr-balyshyn", sidebar + footer)


class GitAskpassTests(unittest.TestCase):
    def test_no_token_for_username_or_unknown_prompt(self):
        for prompt, status, output in (("Username for https://github.com", 0, "x-access-token\n"), ("unknown", 1, "")):
            result = subprocess.run(["bash", str(ROOT / "scripts/git-askpass.sh"), prompt],
                                    env={"PATH": os.environ["PATH"]}, text=True, capture_output=True, timeout=5)
            self.assertEqual((result.returncode, result.stdout), (status, output))

    def test_password_requires_explicit_step_token(self):
        result = subprocess.run(["bash", str(ROOT / "scripts/git-askpass.sh"), "Password for https://github.com"],
                                env={"PATH": os.environ["PATH"]}, text=True, capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not configured", result.stderr)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
