"""Pin publication policy diagnostics and their order before sharing checks."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("publication_policy", ROOT / "scripts/validate-workflows.py")
policy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(policy)


class PublicationPolicyTests(unittest.TestCase):
    def setUp(self):
        self.workflows = {p.name: policy.load(p) for p in (ROOT / ".github/workflows").glob("*.yml")}
        self.actions = {str(p): policy.load(p) for p in (ROOT / ".github/actions").rglob("action.yml")}

    def mutate(self, filename, field):
        workflow = self.workflows[filename]
        jobs = workflow["jobs"]
        steps = jobs["publish"]["steps"]
        if field == "reusable":
            jobs["validate"]["secrets"] = "inherit"
        elif field == "needs":
            jobs["publish"]["needs"] = []
        elif field == "checkout":
            steps[0]["with"]["ref"] = "main"
        elif field == "version":
            next(s for s in steps if s.get("run") == "python3 scripts/validate-release-version.py")["run"] = "echo fixture"
        elif field == "guard":
            next(s for s in steps if "VALIDATED_SHA" in s.get("env", {}))["run"] = "echo fixture"
        elif field == "remote":
            steps[-1]["run"] = "echo fixture"
        elif field == "concurrency":
            workflow["concurrency"]["group"] = "fixture"

    def assert_diagnostics(self, expected):
        with self.assertRaises(ValueError) as error:
            policy.validate(self.workflows, self.actions)
        self.assertEqual(str(error.exception).splitlines(), expected)

    def test_each_common_failure_has_exact_diagnostic(self):
        originals = copy.deepcopy(self.workflows)
        for filename, label in (("publish.yml", "publish"), ("publish-packages.yml", "packages")):
            for field, message in (("reusable", "full reusable CI must run without secrets/inputs/conditions"),
                                   ("needs", "validation must gate publication"),
                                   ("checkout", "checkout must use validated SHA"),
                                   ("guard", "exact tag checkout comparison missing" if label == "publish" else "exact release checkout comparison missing"),
                                   ("remote", "anonymous Central consumer verification missing" if label == "publish" else "remote consumer verification missing")):
                with self.subTest(workflow=filename, field=field):
                    self.workflows = copy.deepcopy(originals)
                    self.mutate(filename, field)
                    self.assert_diagnostics([f"{label}: {message}"])

    def test_simultaneous_failures_keep_central_then_packages_order(self):
        for filename in ("publish.yml", "publish-packages.yml"):
            for field in ("reusable", "needs", "checkout", "version", "guard", "remote"):
                self.mutate(filename, field)
        self.mutate("publish-packages.yml", "concurrency")
        self.assert_diagnostics([
            "publish: full reusable CI must run without secrets/inputs/conditions",
            "publish: validation must gate publication",
            "publish: checkout must use validated SHA",
            "publish: all module versions must be checked before credentials",
            "publish: exact tag checkout comparison missing",
            "publish: anonymous Central consumer verification missing",
            "packages: manual and tag releases must serialize the same version",
            "packages: full reusable CI must run without secrets/inputs/conditions",
            "packages: validation must gate publication",
            "packages: checkout must use validated SHA",
            "packages: exact release checkout comparison missing",
            "packages: release version must use a project variable, not GitHub's reserved ref variable",
            "packages: all module versions must be checked before credentials",
            "packages: remote consumer verification missing",
        ])

    def test_packages_only_version_binding_is_not_required_for_central(self):
        version = next(s for s in self.workflows["publish.yml"]["jobs"]["publish"]["steps"]
                       if s.get("run") == "python3 scripts/validate-release-version.py")
        self.assertNotIn("GLYPHORA_RELEASE_TAG", version.get("env", {}))
        policy.validate(self.workflows, self.actions)
        version = next(s for s in self.workflows["publish-packages.yml"]["jobs"]["publish"]["steps"]
                       if s.get("run") == "python3 scripts/validate-release-version.py")
        version["env"] = {}
        self.assert_diagnostics(["packages: release version must use a project variable, not GitHub's reserved ref variable"])
