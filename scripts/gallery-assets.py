#!/usr/bin/env python3
"""Keeps the committed gallery screenshots equal to what the code renders, and limited to the ones the docs use.

`examples/gallery`'s golden suite writes one `<factory>.svg` per exported factory when `GLYPHORA_GALLERY_EXPORT` is
set (see `scripts/render-gallery.sh`). Only the screenshots that README.md or a page under `website/docs` actually
shows are committed, under `docs/assets/gallery/`; the rest would be a hundred files nobody reviews.

  sync  --rendered DIR   copy every referenced screenshot from DIR into docs/assets/gallery, delete unreferenced ones
  check --rendered DIR   fail when the committed set differs from the referenced set or from what DIR holds
"""

import argparse
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
ASSETS = Path("docs/assets/gallery")
# README.md links into the repository (`docs/assets/gallery/x.svg`); a docs page links into the site, which serves
# docs/assets at the base URL (`/glyphora/gallery/x.svg`). A factory name is a Scala identifier.
REFERENCE = re.compile(r"(?:docs/assets/|/glyphora/)gallery/([A-Za-z][A-Za-z0-9]*)\.svg")


def documents(root: Path) -> list[Path]:
    pages = sorted((root / "website" / "docs").glob("*.md"))
    return [path for path in [root / "README.md", *pages] if path.is_file()]


def referenced(root: Path) -> set[str]:
    """The factories whose screenshot the published prose shows."""
    return {name for page in documents(root) for name in REFERENCE.findall(page.read_text(encoding="utf-8"))}


def committed(root: Path) -> set[str]:
    directory = root / ASSETS
    return {path.stem for path in directory.glob("*.svg")} if directory.is_dir() else set()


def problems(root: Path, rendered: Path) -> list[str]:
    """Every way the committed screenshots disagree with the docs or with a fresh render; empty when they agree."""
    wanted, present = referenced(root), committed(root)
    found = []
    for name in sorted(wanted - present):
        found.append(f"{name}.svg is shown by the docs but not committed under {ASSETS}")
    for name in sorted(present - wanted):
        found.append(f"{ASSETS}/{name}.svg is committed but no page shows it")
    for name in sorted(wanted & present):
        fresh = rendered / f"{name}.svg"
        if not fresh.is_file():
            found.append(f"{name}.svg was not rendered: no gallery page for `{name}`")
        elif fresh.read_bytes() != (root / ASSETS / f"{name}.svg").read_bytes():
            found.append(f"{ASSETS}/{name}.svg differs from what the gallery renders now")
    return found


def sync(root: Path, rendered: Path) -> list[str]:
    """Makes docs/assets/gallery hold exactly the referenced screenshots, freshly rendered; returns what it wrote."""
    wanted = referenced(root)
    missing = sorted(name for name in wanted if not (rendered / f"{name}.svg").is_file())
    if missing:
        raise SystemExit(f"not rendered, so not copied: {', '.join(missing)}")
    target = root / ASSETS
    target.mkdir(parents=True, exist_ok=True)
    for name in committed(root) - wanted:
        (target / f"{name}.svg").unlink()
    for name in sorted(wanted):
        shutil.copyfile(rendered / f"{name}.svg", target / f"{name}.svg")
    return sorted(wanted)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=("sync", "check"))
    parser.add_argument("--rendered", type=Path, required=True)
    args = parser.parse_args()
    if not args.rendered.is_dir():
        parser.error(f"--rendered is not a directory: {args.rendered}")
    if args.mode == "sync":
        written = sync(ROOT, args.rendered)
        print(f"gallery-assets: {len(written)} screenshots under {ASSETS}")
        return
    found = problems(ROOT, args.rendered)
    if found:
        print("\n".join(found), file=sys.stderr)
        raise SystemExit("gallery screenshots drifted: run scripts/render-gallery.sh and commit the result")
    print(f"gallery-assets: {len(referenced(ROOT))} committed screenshots match the gallery")


if __name__ == "__main__":
    main()
