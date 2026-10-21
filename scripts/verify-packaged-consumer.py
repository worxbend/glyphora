#!/usr/bin/env python3
"""Stage seven Maven artifacts and exercise an isolated local or remote consumer."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
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


def authenticated_opener(url: str) -> urllib.request.OpenerDirector:
    """Keep authenticated downloads on their initial HTTPS origin, at every hop."""
    def origin(value):
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme != "https" or parsed.username is not None or parsed.password is not None:
            raise ValueError("unsafe authenticated redirect")
        return parsed.hostname, 443 if parsed.port is None else parsed.port

    expected = origin(url)

    class SameOriginRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            try:
                if origin(newurl) != expected:
                    raise ValueError("unsafe authenticated redirect")
            except ValueError:
                fp.close()
                raise
            return super().redirect_request(req, fp, code, msg, headers, newurl)

    return urllib.request.build_opener(SameOriginRedirect())


def verify_remote_artifacts(repository: Path, url: str, version: str) -> None:
    """Download all 28 release files and compare them with the validated build."""
    headers = {}
    attempts = 6
    if url.rstrip("/") == "https://repo.maven.apache.org/maven2":
        # Central's public repository can lag behind a successful Portal upload.
        # Download anonymously, including when GitHub credentials exist in the environment.
        attempts = 180
    elif url.startswith("https://maven.pkg.github.com/"):
        credentials = f"{os.environ['GITHUB_PACKAGES_USERNAME']}:{os.environ['GITHUB_PACKAGES_PASSWORD']}"
        headers["Authorization"] = "Basic " + base64.b64encode(credentials.encode()).decode()
    else:
        raise ValueError("remote verification requires HTTPS GitHub Packages or Maven Central")
    fetch = authenticated_opener(url).open if headers else urllib.request.urlopen
    for artifact in ARTIFACT_EDGES:
        for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar"):
            relative = f"com/worxbend/{artifact}/{version}/{artifact}-{version}{suffix}"
            request = urllib.request.Request(f"{url.rstrip('/')}/{relative}", headers=headers)
            for attempt in range(attempts):
                try:
                    with fetch(request, timeout=30) as response:
                        downloaded = response.read()
                    break
                except urllib.error.HTTPError as error:
                    if error.code != 404 or attempt == attempts - 1:
                        raise
                    time.sleep(5)
            staged = repository / relative
            if hashlib.sha256(downloaded).digest() != hashlib.sha256(staged.read_bytes()).digest():
                raise ValueError(f"published artifact differs from the validated build: {relative}")
            print(f"Verified remote {artifact}-{version}{suffix}", flush=True)


def validate_poms(repository: Path, version: str) -> None:
    """Assert coordinates, exact production edges and the absence of test-library leaks."""
    for artifact, expected in ARTIFACT_EDGES.items():
        pom = repository / "com/worxbend" / artifact / version / f"{artifact}-{version}.pom"
        project = ET.parse(pom).getroot()
        coordinates = tuple(project.findtext(f"m:{key}", namespaces=NS) for key in ("groupId", "artifactId", "version"))
        if coordinates != ("com.worxbend", artifact, version):
            raise ValueError(f"{artifact}: wrong coordinates {coordinates}")
        actual = set()
        for dependency in project.findall("m:dependencies/m:dependency", NS):
            group = dependency.findtext("m:groupId", namespaces=NS) or ""
            name = dependency.findtext("m:artifactId", namespaces=NS) or ""
            scope = dependency.findtext("m:scope", namespaces=NS) or "compile"
            if group.startswith(("org.scalatest", "org.scalacheck")):
                raise ValueError(f"{artifact}: test dependency leaked: {group}:{name}")
            if group == "com.worxbend":
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
                if name in actual:
                    raise ValueError(f"{artifact}: duplicate production dependency {name}")
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
                staged = repository / "com/worxbend" / artifact / version / path.name
                if hashlib.sha256(path.read_bytes()).digest() != hashlib.sha256(staged.read_bytes()).digest():
                    raise ValueError(f"consumer did not resolve the staged {artifact}")
                found.add(artifact)
    if found != expected:
        raise ValueError(f"packaged classpath {sorted(found)} != {sorted(expected)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", help="also verify and consume GitHub Packages or public Maven Central")
    args = parser.parse_args()
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
        if args.repository:
            verify_remote_artifacts(repository, args.repository, version)
        resolver = args.repository or repository.as_uri()
        consumer_env = {
            **env,
            "GLYPHORA_VERSION": version,
            "GLYPHORA_MAVEN_REPO": resolver,
            # No local Ivy/Maven fallback can conceal a missing staged artifact.
            "COURSIER_REPOSITORIES": f"{resolver}|https://repo1.maven.org/maven2",
        }
        if args.repository:
            # A fresh cache proves the files can be downloaded on another machine.
            consumer_env["COURSIER_CACHE"] = str(workspace / "remote-cache")
        mill(consumer, ["consumer.run", "+", "consumer.test"], consumer_env)
        production = json.loads(mill(consumer, ["show", "consumer.runClasspath"], consumer_env))
        tests = json.loads(mill(consumer, ["show", "consumer.test.runClasspath"], consumer_env))
        verify_classpath(production, repository, version, set(ARTIFACT_EDGES) - {"tui-test_3"})
        verify_classpath(tests, repository, version, set(ARTIFACT_EDGES))
        print("Packaged DSL/macro consumer and test-only Pilot consumer passed on the pinned JDK", flush=True)


if __name__ == "__main__":
    main()
