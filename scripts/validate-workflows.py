#!/usr/bin/env python3
"""Repository workflow policy, complementing actionlint's schema/expression validation."""

from pathlib import Path
import re

try:
    import yaml
except ModuleNotFoundError as error:
    if error.name != "yaml":
        raise
    raise SystemExit("PyYAML is required: python3 -m pip install -r scripts/requirements-ci.txt") from None

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_GATES = {
    "discipline", "workflow-checks", "discover", "lint", "build", "test", "example",
    "native-image", "website-build", "packaged-consumer",
}
SHA = "${{ needs.validate.outputs.validated-sha }}"
RELEASE_TAG = "${{ github.event_name == 'workflow_dispatch' && format('v{0}', inputs.version) || github.ref_name }}"


def load(path: Path) -> dict:
    # BaseLoader deliberately preserves `on` and booleans as strings: YAML 1.1's
    # SafeLoader otherwise treats the Actions trigger key `on` as boolean True.
    return yaml.load(path.read_text(), Loader=yaml.BaseLoader)


def bound(value: str, maximum: int) -> bool:
    return isinstance(value, str) and value.isdecimal() and 0 < int(value) <= maximum


def validate(workflows: dict[str, dict], actions: dict[str, dict]) -> None:
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    def check_steps(steps, location, maximum):
        for index, step in enumerate(steps):
            target = f"{location}/step {index + 1}"
            if maximum is not None:
                require(bound(step.get("timeout-minutes"), maximum), f"{target}: missing/invalid step timeout")
            require(step.get("continue-on-error", "false") == "false", f"{target}: may hide failure")
            require("${{" not in step.get("run", ""), f"{target}: expression interpolated into shell")
            uses = step.get("uses", "")
            if uses and not uses.startswith("./"):
                require(re.fullmatch(r"[\w.-]+/[\w./-]+@[0-9a-f]{40}", uses), f"{target}: action is not SHA-pinned")
            if uses.startswith("actions/checkout@"):
                require(step.get("with", {}).get("persist-credentials") == "false", f"{target}: persistent checkout credentials")

    for name, workflow in workflows.items():
        require(workflow.get("permissions") == {"contents": "read"}, f"{name}: default permissions must be read-only")
        require("pull_request_target" not in workflow.get("on", {}), f"{name}: privileged PR trigger")
        for job_name, job in workflow.get("jobs", {}).items():
            location = f"{name}/{job_name}"
            require(job.get("continue-on-error", "false") == "false", f"{location}: may hide failure")
            if "uses" in job:
                continue  # Reusable callers cannot set a job timeout; every callee job must.
            timeout = job.get("timeout-minutes")
            require(bound(timeout, 120), f"{location}: missing/invalid job timeout")
            check_steps(job.get("steps", []), location, int(timeout) if bound(timeout, 120) else 120)
            permissions = job.get("permissions", workflow.get("permissions"))
            permitted = {"contents": "read"}
            if (name, job_name) == ("autofix.yml", "autofix"):
                permitted = {"contents": "write"}
            elif (name, job_name) == ("docs.yml", "build"):
                permitted = {"contents": "read", "pages": "read"}
            elif (name, job_name) == ("docs.yml", "deploy"):
                permitted = {"actions": "read", "pages": "write", "id-token": "write"}
            elif (name, job_name) == ("publish-packages.yml", "publish"):
                permitted = {"contents": "read", "packages": "write"}
            require(permissions == permitted, f"{location}: unexpected permissions")
    for name, action in actions.items():
        check_steps(action.get("runs", {}).get("steps", []), name, None)

    for filename, job_name in (("ci.yml", "website-build"), ("docs.yml", "build")):
        build_steps = [step for step in workflows[filename]["jobs"][job_name]["steps"]
                       if step.get("working-directory") == "website"]
        expected = ["npm ci --ignore-scripts", "npm test", "npm audit --audit-level=critical", "npm run build"]
        commands = [line.strip() for step in build_steps for line in step.get("run", "").splitlines()
                    if line.strip() and not line.lstrip().startswith("#")]
        require(len(build_steps) == 1 and commands == expected,
                f"{filename}: website dependency checks must precede the production build without failure bypass")

    require("docs/assets/**" in workflows["docs.yml"].get("on", {}).get("push", {}).get("paths", []),
            "docs: static assets must trigger Pages")

    ci = workflows["ci.yml"]
    jobs = ci["jobs"]
    require(set(jobs) == REQUIRED_GATES | {"validated"}, "ci: required validation jobs changed")
    aggregate = jobs["validated"]
    require(set(aggregate.get("needs", [])) == REQUIRED_GATES, "ci: aggregate must require every gate")
    require("if" not in aggregate, "ci: aggregate must not bypass failed dependencies")
    for name, job in jobs.items():
        require("if" not in job, f"ci/{name}: required job cannot be conditional")
    require(ci["on"]["workflow_call"]["outputs"]["validated-sha"]["value"] == "${{ jobs.validated.outputs.sha }}", "ci: validated output must come from aggregate")
    require(aggregate.get("outputs", {}).get("sha") == "${{ steps.commit.outputs.sha }}", "ci: aggregate SHA must come from checkout")
    run = next(step["run"] for step in aggregate["steps"] if step.get("id") == "commit")
    require('test "$actual" = "$GITHUB_SHA"' in run and "git rev-parse HEAD" in run, "ci: checkout must equal event SHA")
    quickstarts = [step for step in jobs["build"]["steps"]
                   if "python3 scripts/verify-scala-cli.py --scala-cli-jar" in step.get("run", "")]
    require(len(quickstarts) == 1 and "if" not in quickstarts[0],
            "ci: current-checkout Scala CLI quickstart compilation missing or conditional")
    checks = jobs["workflow-checks"]["steps"]
    require(any(step.get("run") == "bash scripts/check-workflows.sh" for step in checks), "ci: workflow validation missing")
    require(any(step.get("run") == "python3 -m unittest discover -s scripts/tests -v" for step in checks), "ci: release tooling tests missing")
    native = jobs["native-image"]["steps"]
    require(any(step.get("run") == "python3 scripts/ci-examples.py smoke-native" for step in native), "ci: bounded native smoke missing")
    require(any(step.get("run") == "python3 scripts/ci-examples.py build-native" for step in native), "ci: validated native discovery missing")
    require(any(step.get("run") == 'python3 scripts/ci-examples.py test --example "$EXAMPLE"' and step.get("env", {}).get("EXAMPLE") == "${{ matrix.example }}" for step in jobs["example"]["steps"]), "ci: safe example matrix binding missing")

    def publication_steps(workflow, label):
        jobs = workflow["jobs"]
        require(jobs["validate"] == {"uses": "./.github/workflows/ci.yml"}, f"{label}: full reusable CI must run without secrets/inputs/conditions")
        job = jobs["publish"]
        require(job.get("needs") == "validate" and "if" not in job, f"{label}: validation must gate publication")
        steps = job["steps"]
        checkout = next(step for step in steps if step.get("uses", "").startswith("actions/checkout@"))
        require(checkout.get("with", {}).get("ref") == SHA, f"{label}: checkout must use validated SHA")
        return steps

    def version_index(steps):
        return next((i for i, step in enumerate(steps) if step.get("run") == "python3 scripts/validate-release-version.py"), len(steps))

    def versions_before_credentials(steps, version, label):
        credentials = [i for i, step in enumerate(steps) if "secrets." in str(step)]
        require(bool(credentials) and all(version < i for i in credentials), f"{label}: all module versions must be checked before credentials")

    def exact_checkout_guard(steps, message):
        guard = next((step for step in steps if step.get("env", {}).get("VALIDATED_SHA") == SHA), {})
        require('"$actual" != "$VALIDATED_SHA"' in guard.get("run", "") and '"$actual" != "$GITHUB_SHA"' in guard.get("run", ""), message)

    steps = publication_steps(workflows["publish.yml"], "publish")
    version = version_index(steps)
    versions_before_credentials(steps, version, "publish")
    exact_checkout_guard(steps, "publish: exact tag checkout comparison missing")
    require(any(step.get("run") == "python3 scripts/verify-packaged-consumer.py --repository https://repo.maven.apache.org/maven2"
                and "secrets." not in str(step) for step in steps),
            "publish: anonymous Central consumer verification missing")
    package_workflow = workflows["publish-packages.yml"]
    require(package_workflow.get("concurrency", {}).get("group") == "github-packages-" + RELEASE_TAG,
            "packages: manual and tag releases must serialize the same version")
    package_steps = publication_steps(package_workflow, "packages")
    exact_checkout_guard(package_steps, "packages: exact release checkout comparison missing")
    version = version_index(package_steps)
    require(version < len(package_steps) and package_steps[version].get("env") == {
        "GLYPHORA_RELEASE_TAG": RELEASE_TAG
    }, "packages: release version must use a project variable, not GitHub's reserved ref variable")
    versions_before_credentials(package_steps, version, "packages")
    require(any(step.get("run") == 'python3 scripts/verify-packaged-consumer.py --repository "$GLYPHORA_MAVEN_REPO"' for step in package_steps), "packages: remote consumer verification missing")
    wiki = workflows["wiki.yml"]["jobs"]["publish"]["steps"]
    for step in wiki:
        require("https://x-access-token:" not in step.get("run", ""), "wiki: credentials must not enter remote URL")
    if errors:
        raise ValueError("\n".join(errors))


def main() -> None:
    workflows = {path.name: load(path) for path in sorted((ROOT / ".github/workflows").glob("*.y*ml"))}
    actions = {str(path.relative_to(ROOT)): load(path) for path in sorted((ROOT / ".github/actions").rglob("action.y*ml"))}
    validate(workflows, actions)
    print(f"Validated policy for {len(workflows)} workflows and {len(actions)} composite actions")


if __name__ == "__main__":
    main()
