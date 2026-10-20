#!/usr/bin/env python3
"""Compile the single-file quickstart against this checkout, not an older Central release.

Only its tui-dsl dependency directive is removed in an isolated temporary copy;
Scala, JDK, mainClass and the complete application body remain unchanged. Mill's
runClasspath compiles the current production sources before Scala CLI consumes
those local artifacts. No publication or mutation of the example is needed.
"""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEPENDENCY = re.compile(r"(?m)^//> using dep com\.worxbend::tui-dsl:([^\s]+)\s*$")


def local_source(source: str, version: str) -> str:
    pins = DEPENDENCY.findall(source)
    if pins != [version]:
        raise ValueError(f"quickstart dependency pin {pins!r} must equal publishVersion {version!r}")
    return DEPENDENCY.sub("", source, count=1)


def classpath_path(entry: str) -> str:
    return entry.split(":", 3)[3] if entry.startswith(("ref:", "qref:")) else entry


def mill_json(task: str, env: dict[str, str]):
    launcher = env.get("GLYPHORA_MILL", str(ROOT / "mill"))
    result = subprocess.run([launcher, "show", task], cwd=ROOT, env=env,
                            text=True, stdout=subprocess.PIPE, check=True, timeout=900)
    return json.loads(result.stdout)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scala-cli-jar", type=Path, help="pinned bootstrapped Scala CLI jar (CI)")
    args = parser.parse_args()
    env = dict(os.environ)
    env.pop("GLYPHORA_GOLDEN_UPDATE", None)
    version = mill_json("core.publishVersion", env)
    source = local_source((ROOT / "examples/scala-cli/hello.scala").read_text(), version)
    entries = [classpath_path(entry) for entry in mill_json("dsl.runClasspath", env)]
    # Mill lists optional resource directories even for modules with no resources.
    # Scala CLI requires existing paths; omit only these absent resource roots.
    entries = [entry for entry in entries if Path(entry).exists()
               or Path(entry).name not in {"resources", "compile-resources"}]
    missing = [entry for entry in entries if not Path(entry).exists()]
    expected = {str(ROOT / "out" / module / "compile.dest/classes")
                for module in ("core", "terminal", "widgets", "runtime", "macros", "dsl")}
    if missing or not expected.issubset(entries):
        raise ValueError(f"Mill did not supply current quickstart classes: missing paths={missing}, "
                         f"missing module outputs={sorted(expected - set(entries))}")
    if args.scala_cli_jar:
        command = ["java", "-jar", str(args.scala_cli_jar.resolve())]
    else:
        executable = shutil.which("scala-cli") or shutil.which("scala")
        if executable is None:
            raise SystemExit("Scala CLI is required: install scala-cli or pass --scala-cli-jar")
        command = [executable]
    with tempfile.TemporaryDirectory(prefix="glyphora-quickstart-", dir=env.get("TMPDIR")) as temporary:
        example = Path(temporary) / "hello.scala"
        example.write_text(source)
        subprocess.run([*command, "compile", "--server=false", "--classpath", os.pathsep.join(entries), str(example)],
                       cwd=temporary, env=env, check=True, timeout=600)
    print(f"Scala CLI quickstart compiled against current {version} checkout artifacts", flush=True)


if __name__ == "__main__":
    main()
