#!/usr/bin/env bash
# Regenerates the gallery screenshots — one `<factory>.svg` per exported element factory — by running the gallery's
# golden suite with `GLYPHORA_GALLERY_EXPORT` pointing at the output directory (default `docs/assets/gallery`).
#
# The suite renders every page of `examples/gallery` exactly as its golden frame records it and writes each one
# through `BufferExport.svg`, so a screenshot can never show a frame the goldens do not. No browser, VHS or font is
# needed. The suite still compares against its goldens while it exports: when a page changed on purpose, re-record
# first with `GLYPHORA_GOLDEN_UPDATE=$PWD/examples/gallery/src/test/resources`.
#
#   scripts/render-gallery.sh                                  # into docs/assets/gallery
#   GLYPHORA_GALLERY_EXPORT=build/gallery scripts/render-gallery.sh
set -euo pipefail

cd "$(dirname "$0")/.."

output="${GLYPHORA_GALLERY_EXPORT:-docs/assets/gallery}"
mkdir -p "$output"
# the suite runs in a forked JVM whose working directory is Mill's sandbox, so the path it is handed must be absolute
GLYPHORA_GALLERY_EXPORT="$(cd "$output" && pwd)"
export GLYPHORA_GALLERY_EXPORT

./mill examples.gallery.test.testOnly io.worxbend.tui.examples.gallery.GallerySpec

echo "render-gallery: $(find "$GLYPHORA_GALLERY_EXPORT" -name '*.svg' | wc -l) screenshots under $GLYPHORA_GALLERY_EXPORT"
