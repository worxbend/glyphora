"""Offline generator boundaries; never fetch UCD or rewrite checked-in Scala."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / "tools" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


width = load("generate-width-table")
world = load("generate-world-table")


class WidthGeneratorTests(unittest.TestCase):
    def test_merge_sorts_mutates_input_and_fuses_overlap_and_adjacency(self):
        ranges = [(10, 11), (3, 5), (1, 3), (7, 7), (6, 6), (10, 10)]
        self.assertEqual(width.merge(ranges), [(1, 7), (10, 11)])
        self.assertEqual(ranges, [(1, 3), (3, 5), (6, 6), (7, 7), (10, 10), (10, 11)])
        self.assertEqual(width.merge([]), [])

    def test_parse_explicit_properties_and_first_version_only(self):
        source = """# EastAsianWidth-fixture.txt
# EastAsianWidth-later.txt
# @missing: 0000..10FFFF; W
0042; A # fixture
0040..0041; A
0030..0031; W
0032; F
0043; N
0044; Na
0045; H
invalid
"""
        self.assertEqual(width.parse_ranges(source), ([(0x30, 0x32)], [(0x40, 0x42)], "EastAsianWidth-fixture.txt"))
        self.assertEqual(width.parse_ranges("# ignored\n"), ([], [], "unknown"))

    def test_parser_currently_requires_uppercase_and_column_zero(self):
        self.assertEqual(width.parse_ranges(" 0041; W\n00ab; A\n0042; W trailing fixture\n"),
                         ([(0x42, 0x42)], [], "unknown"))

    def test_emission_is_stable_inclusive_hex_and_empty_arrays(self):
        self.assertEqual(width.format_pairs([(1, 2), (0x10FFFF, 0x10FFFF)]),
                         "    0x00001, 0x00002,\n    0x10ffff, 0x10ffff,")
        emitted = width.emit([(1, 2)], [], "fixture version")
        self.assertIn("from fixture version", emitted)
        self.assertIn("WideRanges: Array[Int] = Array(\n    0x00001, 0x00002,\n  )", emitted)
        self.assertIn("AmbiguousRanges: Array[Int] = Array(\n\n  )", emitted)

    def test_main_uses_local_fetch_fixture_and_scratch_destination(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "nested/WidthTable.scala"
            with patch.object(width, "fetch", return_value="0041; W\n"), patch.object(width, "OUTPUT", destination):
                width.main()
            self.assertEqual(destination.read_text(), width.emit([(0x41, 0x41)], [], "unknown"))


class WorldGeneratorTests(unittest.TestCase):
    def test_read_points_ignores_blanks_comments_and_preserves_order(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "coordinates"
            source.write_text("# fixture\n\n  180 -90  \n  # indented comment\n-1.25 2.5\n180 -90\n")
            self.assertEqual(world.read_points(source), [(180.0, -90.0), (-1.25, 2.5), (180.0, -90.0)])

    def test_malformed_coordinate_rows_keep_existing_errors(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "coordinates"
            for row in ("1", "1 2 3", "bad 2", "1 2 # inline comment"):
                with self.subTest(row=row):
                    source.write_text(row)
                    with self.assertRaises(ValueError):
                        world.read_points(source)

    def test_quantization_clamps_bounds_and_uses_python_rounding(self):
        for minimum, maximum in ((-180.0, 180.0), (-90.0, 90.0)):
            with self.subTest(minimum=minimum):
                self.assertEqual(world.quantize(minimum, minimum, maximum), 0x100)
                self.assertEqual(world.quantize(maximum, minimum, maximum), 0x80FF)
                self.assertEqual(world.quantize(minimum - 1, minimum, maximum), 0x100)
                self.assertEqual(world.quantize(maximum + 1, minimum, maximum), 0x80FF)
        self.assertEqual(world.quantize(0, -180, 180), 0x4100)
        self.assertEqual(world.quantize(0.5, 0, world.STEPS), world.BIAS)
        self.assertEqual(world.quantize(1.5, 0, world.STEPS), world.BIAS + 2)

    def test_encode_interleaves_lon_lat_and_has_bounded_chunks(self):
        self.assertEqual(world.encode([(-180, 90), (180, -90)]), [r"\u0100\u80ff\u80ff\u0100"])
        self.assertEqual(world.encode([]), [])
        chunks = world.encode([(0, 0)] * 241)
        self.assertEqual([len(chunk) for chunk in chunks], [world.CHUNK * 6, 2 * 6])
        values = [int(chunk[index + 2:index + 6], 16) for chunk in chunks for index in range(0, len(chunk), 6)]
        self.assertTrue(all(0x100 <= value <= 0x80FF for value in values))
        self.assertTrue(all(not 0xD800 <= value <= 0xDFFF for value in values))

    def test_emit_chunks_empty_and_escaped_literals(self):
        self.assertEqual(world.emit_chunks("fixture", []), "  private val fixture: Array[String] = Array(\n  )")
        self.assertIn('    "\\u0100",', world.emit_chunks("fixture", [r"\u0100"]))

    def test_cli_generates_only_scratch_scala_with_fixture_counts_and_decoder(self):
        with tempfile.TemporaryDirectory() as temp:
            low, high, output = [Path(temp) / name for name in ("low", "high", "WorldTable.scala")]
            low.write_text("-180 90\n")
            high.write_text("180 -90\n0 0\n")
            result = subprocess.run([sys.executable, str(ROOT / "tools/generate-world-table.py"),
                                     "--low", str(low), "--high", str(high), "--output", str(output)],
                                    text=True, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            text = output.read_text()
            self.assertIn("1 points", text)
            self.assertIn("2 points", text)
            self.assertIn(r"\u0100\u80ff", text)
            self.assertIn("step * 360.0 / 32767 - 180.0", text)
            self.assertIn("step * 180.0 / 32767 - 90.0", text)
