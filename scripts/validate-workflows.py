#!/usr/bin/env python3
"""Repository workflow policy, complementing actionlint's schema/expression validation."""

from pathlib import Path
import re

import yaml

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_GATES = {
    "discipline", "workflow-checks", "discover", "lint", "build", "test", "example",
    "native-image", "website-build", "packaged-consumer",
}
SHA = "${{ needs.validate.outputs.validated-sha }}"


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
            require(permissions == permitted, f"{location}: unexpected permissions")
    for name, action in actions.items():
        check_steps(action.get("runs", {}).get("steps", []), name, None)

    for filename, job_name in (("ci.yml", "website-build"), ("docs.yml", "build")):
        build_steps = [step for step in workflows[filename]["jobs"][job_name]["steps"]
                       if step.get("working-directory") == "website"]
        expected = ["npm ci", "npm test", "npm audit --audit-level=critical", "npm run build"]
        commands = [line.strip() for step in build_steps for line in step.get("run", "").splitlines()
                    if line.strip() and not line.lstrip().startswith("#")]
        require(len(build_steps) == 1 and commands == expected,
                f"{filename}: website dependency checks must precede the production build without failure bypass")

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
    checks = jobs["workflow-checks"]["steps"]
    require(any(step.get("run") == "bash scripts/check-workflows.sh" for step in checks), "ci: workflow validation missing")
    require(any(step.get("run") == "python3 -m unittest discover -s scripts/tests -v" for step in checks), "ci: release tooling tests missing")
    native = jobs["native-image"]["steps"]
    require(any(step.get("run") == "python3 scripts/ci-examples.py smoke-native" for step in native), "ci: bounded native smoke missing")
    require(any(step.get("run") == "python3 scripts/ci-examples.py build-native" for step in native), "ci: validated native discovery missing")
    require(any(step.get("run") == 'python3 scripts/ci-examples.py test --example "$EXAMPLE"' and step.get("env", {}).get("EXAMPLE") == "${{ matrix.example }}" for step in jobs["example"]["steps"]), "ci: safe example matrix binding missing")

    publish = workflows["publish.yml"]["jobs"]
    require(publish["validate"] == {"uses": "./.github/workflows/ci.yml"}, "publish: full reusable CI must run without secrets/inputs/conditions")
    job = publish["publish"]
    require(job.get("needs") == "validate" and "if" not in job, "publish: validation must gate publication")
    steps = job["steps"]
    checkout = next(step for step in steps if step.get("uses", "").startswith("actions/checkout@"))
    require(checkout.get("with", {}).get("ref") == SHA, "publish: checkout must use validated SHA")
    version = next((i for i, step in enumerate(steps) if step.get("run") == "python3 scripts/validate-release-version.py"), len(steps))
    credentials = [i for i, step in enumerate(steps) if "secrets." in str(step)]
    require(bool(credentials) and all(version < i for i in credentials), "publish: all module versions must be checked before credentials")
    guard = next((step for step in steps if step.get("env", {}).get("VALIDATED_SHA") == SHA), {})
    require('"$actual" != "$VALIDATED_SHA"' in guard.get("run", "") and '"$actual" != "$GITHUB_SHA"' in guard.get("run", ""), "publish: exact tag checkout comparison missing")
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
