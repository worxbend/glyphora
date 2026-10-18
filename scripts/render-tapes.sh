#!/usr/bin/env bash
# Regenerates every tape-driven artefact — asciicast recordings and SVG/text screenshots — by running the test suites
# that perform the tapes, with `GLYPHORA_TAPE_OUTPUT` pointing at the output directory (default `build/tapes`).
#
# Tapes are VHS-subset scripts (see `Tape.parse` in tui-test and website/docs/testing.md) checked in next to the suite
# that runs them; the suite is what knows which app a tape drives, so this script only runs suites. Pass suite class
# names to run a different set; the default is the tape suite in test-support. No agg, ffmpeg or asciinema is needed
# to produce the files — convert a `.cast` to a GIF afterwards with `agg` if a GIF is what the page needs.
#
#   scripts/render-tapes.sh                                   # default suites into build/tapes
#   GLYPHORA_TAPE_OUTPUT=docs/assets/tapes scripts/render-tapes.sh
#   scripts/render-tapes.sh examples.showcase io.worxbend.tui.examples.showcase.ShowcaseTapeSpec
set -euo pipefail

cd "$(dirname "$0")/.."

export GLYPHORA_TAPE_OUTPUT="${GLYPHORA_TAPE_OUTPUT:-build/tapes}"
mkdir -p "$GLYPHORA_TAPE_OUTPUT"

if [ "$#" -eq 0 ]; then
  set -- test-support io.worxbend.tui.testsupport.TapeSpec
fi

# arguments come in pairs: <mill module> <suite class>
while [ "$#" -ge 2 ]; do
  module="$1"; suite="$2"; shift 2
  echo "render-tapes: $module -> $suite"
  ./mill "$module.test.testOnly" "$suite"
done

echo "render-tapes: artefacts under $GLYPHORA_TAPE_OUTPUT"
find "$GLYPHORA_TAPE_OUTPUT" -type f \( -name '*.cast' -o -name '*.svg' -o -name '*.txt' \) | sort
