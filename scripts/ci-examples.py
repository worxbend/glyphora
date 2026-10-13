#!/usr/bin/env python3
"""One validated discovery path for example matrices, native selectors and smoke tests."""

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NAME = re.compile(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*")


def discover(directory: Path) -> list[str]:
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError("examples must be a real directory")
    names = []
    for child in sorted(directory.iterdir()):
        if not child.is_dir():
            if child.is_symlink():
                raise ValueError(f"symlink in examples: {child.name}")
            continue
        if child.is_symlink() or not NAME.fullmatch(child.name):
            raise ValueError(f"unsafe example name or symlink: {child.name!r}")
        package = child / "package.mill"
        if not package.is_file() or package.is_symlink():
            raise ValueError(f"missing regular package.mill: {child.name}")
        if any(path != package for path in child.rglob("package.mill")):
            raise ValueError(f"nested package.mill: {child.name}")
        names.append(child.name)
    if not names:
        raise ValueError("no example modules discovered")
    return names


def native_selectors(names: list[str]) -> list[str]:
    selectors = []
    for name in names:
        if not NAME.fullmatch(name):
            raise ValueError(f"unsafe example name: {name!r}")
        if selectors:
            selectors.append("+")
        selectors.append(f"examples.{name}.nativeImage")
    if not selectors:
        raise ValueError("no native selectors")
    return selectors


def smoke(binary: Path, timeout: float = 10) -> str:
    """Detach from any controlling TTY; kill the entire process group on a hang."""
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise ValueError(f"missing executable: {binary}")
    with subprocess.Popen(
        [str(binary.resolve())], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, start_new_session=True,
    ) as process:
        try:
            output, _ = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
            raise ValueError(f"native binary timed out after {timeout}s: {binary}") from None
        if process.returncode != 1 or "terminal not supported" not in output:
            raise ValueError(f"unclean non-TTY exit: {binary}: status={process.returncode} output={output!r}")
        if re.search(r"(?m)^\s+at \S+\(|Exception in thread|Caused by:", output):
            raise ValueError(f"stack trace in non-TTY exit: {binary}: {output!r}")
        return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("discover", "test", "build-native", "smoke-native"))
    parser.add_argument("--example")
    args = parser.parse_args()
    names = discover(ROOT / "examples")
    if args.mode == "discover":
        print(json.dumps(names, separators=(",", ":")))
    elif args.mode == "test":
        if args.example not in names:
            parser.error("--example must name a discovered module")
        subprocess.run([str(ROOT / "mill"), f"examples.{args.example}.test"], cwd=ROOT, check=True, timeout=900)
    elif args.mode == "build-native":
        subprocess.run([str(ROOT / "mill"), *native_selectors(names)], cwd=ROOT, check=True, timeout=2700)
    else:
        failures = []
        for name in names:
            binary = ROOT / "out/examples" / name / "nativeImage.dest/native-executable"
            try:
                print(f"{name}: {smoke(binary).strip()}", flush=True)
            except ValueError as error:
                failures.append(str(error))
        if failures:
            raise SystemExit("\n".join(failures))


if __name__ == "__main__":
    main()
