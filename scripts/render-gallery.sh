#!/usr/bin/env bash
# Regenerates the gallery screenshots the docs show — `docs/assets/gallery/<factory>.svg` for every factory whose
# screenshot README.md or a page under `website/docs` references — or, with `--check`, fails when the committed ones
# differ from what the code renders now (the CI drift check).
#
# The gallery's golden suite renders every page of `examples/gallery` exactly as its golden frame records it and, with
# `GLYPHORA_GALLERY_EXPORT` set, writes each one through `BufferExport.svg`, so a screenshot can never show a frame the
# goldens do not. No browser, VHS or font is needed. The suite still compares against its goldens while it exports:
# when a page changed on purpose, re-record first with `GLYPHORA_GOLDEN_UPDATE=$PWD/examples/gallery/src/test/resources`.
# `scripts/gallery-assets.py` then keeps only the referenced screenshots; to show a new one, reference
# `docs/assets/gallery/<factory>.svg` (README) or `/glyphora/gallery/<factory>.svg` (a docs page) and run this again.
#
#   scripts/render-gallery.sh            # refresh docs/assets/gallery
#   scripts/render-gallery.sh --check    # fail on drift; writes nothing under docs/
set -euo pipefail

cd "$(dirname "$0")/.."

mode=sync
if [[ "${1:-}" == "--check" ]]; then
  mode=check
elif [[ $# -gt 0 ]]; then
  echo "usage: $0 [--check]" >&2
  exit 2
fi

rendered=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/glyphora-gallery.XXXXXX")
trap 'rm -rf "$rendered"' EXIT
# the suite runs in a forked JVM whose working directory is Mill's sandbox, so the path it is handed is absolute
export GLYPHORA_GALLERY_EXPORT="$rendered"
unset GLYPHORA_GOLDEN_UPDATE

"${MILL:-./mill}" examples.gallery.test.testOnly io.worxbend.tui.examples.gallery.GallerySpec

python3 scripts/gallery-assets.py "$mode" --rendered "$rendered"
