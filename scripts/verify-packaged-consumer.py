#!/usr/bin/env python3
"""Stage seven Maven artifacts and exercise an isolated consumer, without publishing remotely."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("core", "terminal", "widgets", "runtime", "macros", "dsl", "test-support")
ARTIFACT_EDGES = {
    "tui-core_3": set(),
    "tui-terminal_3": {"tui-core_3"},
    "tui-widgets_3": {"tui-core_3"},
    "tui-runtime_3": {"tui-core_3", "tui-terminal_3"},
    "tui-macros_3": set(),
    "tui-dsl_3": {"tui-core_3", "tui-terminal_3", "tui-widgets_3", "tui-runtime_3", "tui-macros_3"},
    "tui-test_3": {"tui-core_3", "tui-terminal_3", "tui-runtime_3"},
}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def validate_poms(repository: Path, version: str) -> None:
    """Assert coordinates, exact production edges and the absence of test-library leaks."""
    for artifact, expected in ARTIFACT_EDGES.items():
        pom = repository / "io/worxbend" / artifact / version / f"{artifact}-{version}.pom"
        project = ET.parse(pom).getroot()
        coordinates = tuple(project.findtext(f"m:{key}", namespaces=NS) for key in ("groupId", "artifactId", "version"))
        if coordinates != ("io.worxbend", artifact, version):
            raise ValueError(f"{artifact}: wrong coordinates {coordinates}")
        actual = set()
        for dependency in project.findall("m:dependencies/m:dependency", NS):
            group = dependency.findtext("m:groupId", namespaces=NS) or ""
            name = dependency.findtext("m:artifactId", namespaces=NS) or ""
            scope = dependency.findtext("m:scope", namespaces=NS) or "compile"
            if group.startswith(("org.scalatest", "org.scalacheck")):
                raise ValueError(f"{artifact}: test dependency leaked: {group}:{name}")
            if group == "io.worxbend":
                dependency_version = dependency.findtext("m:version", namespaces=NS)
                if dependency_version != version or scope != "compile":
                    raise ValueError(f"{artifact}: wrong dependency version/scope for {name}: {dependency_version}/{scope}")
                if dependency.findtext("m:optional", namespaces=NS) == "true":
                    raise ValueError(f"{artifact}: production dependency {name} is optional")
                if (dependency.findtext("m:type", namespaces=NS) or "jar") != "jar":
                    raise ValueError(f"{artifact}: production dependency {name} is not an ordinary jar")
                if dependency.findtext("m:classifier", namespaces=NS):
                    raise ValueError(f"{artifact}: production dependency {name} has a classifier")
                if dependency.findall("m:exclusions/m:exclusion", NS):
                    raise ValueError(f"{artifact}: production dependency {name} has exclusions")
                actual.add(name)
        if actual != expected:
            raise ValueError(f"{artifact}: dependencies {sorted(actual)} != {sorted(expected)}")


def mill(cwd: Path, selectors: list[str], env: dict[str, str]) -> str:
    """Use the checked-in toolchain with a bounded command; stderr preserves live progress."""
    command = [str(cwd / "mill"), "--no-server", "-j", "2", *selectors]
    print(f"[{cwd}] {' '.join(selectors)}", flush=True)
    result = subprocess.run(command, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE, timeout=900)
    print(result.stdout, end="", flush=True)
    result.check_returncode()
    return result.stdout


def verify_classpath(entries: list[str], repository: Path, version: str, expected: set[str]) -> None:
    """Prove dependencies are the staged jars, never the source build or an older local release."""
    found = set()
    for entry in entries:
        path = Path(entry.split(":", 3)[3] if entry.startswith(("ref:", "qref:")) else entry).resolve()
        if path.is_relative_to(ROOT):
            raise ValueError(f"consumer reached the source checkout: {path}")
        for artifact in ARTIFACT_EDGES:
            if path.name.startswith(f"{artifact}-") and path.suffix == ".jar":
                if path.name != f"{artifact}-{version}.jar":
                    raise ValueError(f"unexpected Glyphora jar on consumer classpath: {path}")
                if artifact in found:
                    raise ValueError(f"duplicate Glyphora artifact on consumer classpath: {artifact}")
                staged = repository / "io/worxbend" / artifact / version / path.name
                if hashlib.sha256(path.read_bytes()).digest() != hashlib.sha256(staged.read_bytes()).digest():
                    raise ValueError(f"consumer did not resolve the staged {artifact}")
                found.add(artifact)
    if found != expected:
        raise ValueError(f"packaged classpath {sorted(found)} != {sorted(expected)}")


def main() -> None:
    env = dict(os.environ)
    env.pop("GLYPHORA_GOLDEN_UPDATE", None)
    version = json.loads(mill(ROOT, ["show", "core.publishVersion"], env))
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version):
        raise ValueError(f"invalid release version: {version!r}")
    with tempfile.TemporaryDirectory(prefix="glyphora-consumer-", dir=os.environ.get("TMPDIR")) as temporary:
        workspace = Path(temporary)
        repository = workspace / "repository"
        consumer = workspace / "consumer-build"
        shutil.copytree(ROOT / "scripts/packaged-consumer", consumer)
        for name in ("mill", ".mill-version", ".mill-jvm-version"):
            shutil.copy2(ROOT / name, consumer / name)
        for module in MODULES:
            mill(ROOT, [f"{module}.publishM2Local", "--m-2-repo-path", str(repository)], env)
        validate_poms(repository, version)
        print("All seven packaged POM dependency graphs verified", flush=True)
        consumer_env = {
            **env,
            "GLYPHORA_VERSION": version,
            "GLYPHORA_MAVEN_REPO": repository.as_uri(),
            # No local Ivy/Maven fallback can conceal a missing staged artifact.
            "COURSIER_REPOSITORIES": f"{repository.as_uri()}|https://repo1.maven.org/maven2",
        }
        mill(consumer, ["consumer.run", "+", "consumer.test"], consumer_env)
        production = json.loads(mill(consumer, ["show", "consumer.runClasspath"], consumer_env))
        tests = json.loads(mill(consumer, ["show", "consumer.test.runClasspath"], consumer_env))
        verify_classpath(production, repository, version, set(ARTIFACT_EDGES) - {"tui-test_3"})
        verify_classpath(tests, repository, version, set(ARTIFACT_EDGES))
        print("Packaged DSL/macro consumer and test-only Pilot consumer passed on the pinned JDK", flush=True)


if __name__ == "__main__":
    main()
