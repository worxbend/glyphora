#!/usr/bin/env python3
"""Require a literal release tag and matching versions from all seven publish modules."""

import json
import os
from pathlib import Path
import re
import subprocess

MODULES = ("core", "terminal", "widgets", "runtime", "macros", "dsl", "test-support")
ROOT = Path(__file__).resolve().parents[1]


def validate_versions(tag: str, versions: dict[str, str]) -> str:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?", tag):
        raise ValueError(f"invalid release tag: {tag!r}")
    if set(versions) != set(MODULES):
        raise ValueError("release versions must cover exactly the seven publish modules")
    expected = tag[1:]
    for module, version in versions.items():
        if not isinstance(version, str) or version != expected:
            raise ValueError(f"{module}.publishVersion {version!r} does not match tag {tag!r}")
    return expected


def main() -> None:
    tag = os.environ.get("GLYPHORA_RELEASE_TAG") or os.environ["GITHUB_REF_NAME"]
    # Validate tag syntax before resolving any build task.
    validate_versions(tag, dict.fromkeys(MODULES, tag[1:]))
    env = dict(os.environ)
    env.pop("GLYPHORA_GOLDEN_UPDATE", None)
    versions = {}
    for module in MODULES:
        result = subprocess.run(
            [str(ROOT / "mill"), "show", f"{module}.publishVersion"], cwd=ROOT, env=env,
            text=True, stdout=subprocess.PIPE, check=True, timeout=180,
        )
        versions[module] = json.loads(result.stdout)
    print(f"All seven publish versions match {validate_versions(tag, versions)}")


if __name__ == "__main__":
    main()
