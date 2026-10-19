package io.worxbend.tui.widgets

import io.worxbend.tui.widgets.DiffLine.{Added, Context, Removed}

import org.scalatest.funsuite.AnyFunSuite

/** Expected edit scripts are worked by hand (and, for the `ABCABBA` case, taken from the worked example in James
  * Coglan's walk-through of Myers' paper), never derived from the implementation.
  */
final class PatchSpec extends AnyFunSuite:

  test("Patch.of(\"a\\nb\\nc\", \"a\\nc\\nd\") deletes b and appends d around the common a and c"):
    val patch = Patch.of("a\nb\nc", "a\nc\nd")
    assert(patch.hunks == Seq(Hunk(1, 1, Seq(Context("a"), Removed("b"), Context("c"), Added("d")))))
    assert(patch.hunks.head.oldCount == 3 && patch.hunks.head.newCount == 3)

  test("Patch.of follows Myers' greedy tie-break: deletions before insertions (ABCABBA → CBABAC)"):
    val before = "A\nB\nC\nA\nB\nB\nA"
    val after  = "C\nB\nA\nB\nA\nC"
    val lines  = Patch.of(before, after).hunks.flatMap(_.lines)
    assert(
      lines == Seq(
        Removed("A"),
        Removed("B"),
        Context("C"),
        Added("B"),
        Context("A"),
        Context("B"),
        Removed("B"),
        Context("A"),
        Added("C"),
      )
    )

  test("identical texts produce no hunks; an empty side is all insertions or all deletions"):
    assert(Patch.of("x\ny", "x\ny").hunks.isEmpty)
    assert(Patch.of("", "p\nq").hunks == Seq(Hunk(0, 1, Seq(Added("p"), Added("q")))))
    assert(Patch.of("p\nq", "").hunks == Seq(Hunk(1, 0, Seq(Removed("p"), Removed("q")))))

  test("a trailing newline terminates the last line rather than adding an empty one"):
    assert(Patch.of("a\nb\n", "a\nb").hunks.isEmpty)

  test("Patch.of keeps `context` unchanged lines around each change and splits distant changes into hunks"):
    val before = (1 to 20).map(n => s"l$n").mkString("\n")
    val after  = before.replace("l3\n", "L3\n").replace("l18\n", "L18\n")
    val hunks  = Patch.of(before, after, context = 2).hunks
    assert(
      hunks == Seq(
        Hunk(1, 1, Seq(Context("l1"), Context("l2"), Removed("l3"), Added("L3"), Context("l4"), Context("l5"))),
        Hunk(16, 16, Seq(Context("l16"), Context("l17"), Removed("l18"), Added("L18"), Context("l19"), Context("l20"))),
      )
    )
    // two changes whose context windows touch share one hunk
    assert(Patch.of(before, after, context = 7).hunks.size == 1)

  private val twoHunks =
    """diff --git a/src/app.txt b/src/app.txt
      |index 3b18e51..a2c3f1d 100644
      |--- a/src/app.txt
      |+++ b/src/app.txt
      |@@ -1,3 +1,3 @@ object App
      | one
      |-two
      |+TWO
      | three
      |@@ -10,2 +10,3 @@
      | ten
      |+ten and a half
      | eleven
      |""".stripMargin

  test("Patch.parse reads a two-hunk git diff into its file paths and hunks"):
    val parsed = Patch.parse(twoHunks)
    assert(
      parsed == Right(
        Patch(
          Seq(
            FilePatch(
              Some("src/app.txt"),
              Some("src/app.txt"),
              Seq(
                Hunk(1, 1, Seq(Context("one"), Removed("two"), Added("TWO"), Context("three")), "object App"),
                Hunk(10, 10, Seq(Context("ten"), Added("ten and a half"), Context("eleven"))),
              ),
            )
          )
        )
      )
    )

  test("Patch.parse round-trips: the unified text of a parsed patch parses back to the same hunks"):
    val parsed = Patch.parse(twoHunks).toOption.get
    assert(Patch.parse(parsed.unified) == Right(parsed))
    assert(
      parsed.unified ==
        """--- a/src/app.txt
          |+++ b/src/app.txt
          |@@ -1,3 +1,3 @@ object App
          | one
          |-two
          |+TWO
          | three
          |@@ -10,2 +10,3 @@
          | ten
          |+ten and a half
          | eleven
          |""".stripMargin
    )

  test("Patch.parse reads omitted counts as 1, /dev/null as no path, and several files in order"):
    val text   =
      """--- /dev/null
        |+++ b/new.txt
        |@@ -0,0 +1 @@
        |+hello
        |\ No newline at end of file
        |--- a/old.txt
        |+++ /dev/null
        |@@ -1 +0,0 @@
        |-bye
        |""".stripMargin
    val parsed = Patch.parse(text)
    assert(
      parsed == Right(
        Patch(
          Seq(
            FilePatch(None, Some("new.txt"), Seq(Hunk(0, 1, Seq(Added("hello"))))),
            FilePatch(Some("old.txt"), None, Seq(Hunk(1, 0, Seq(Removed("bye"))))),
          )
        )
      )
    )

  test("Patch.parse rejects a hunk whose body is shorter than its header says, naming the line"):
    val text = "--- a/x\n+++ b/x\n@@ -1,3 +1,3 @@\n a\n-b\n+c\n@@ -9 +9 @@\n-z\n+y\n"
    assert(Patch.parse(text).left.map(_.line) == Left(7))

  test("Patch.parse rejects a malformed hunk header"):
    assert(Patch.parse("@@ -x +1 @@\n+a\n").left.map(_.line) == Left(1))

  test("past maxEditDistance the middle of the texts is one replacement, and the patch says it is approximate"):
    val before = "s\na\nb\nm\nc\nd\ne"
    val after  = "s\nx\nb\nm\ny\nd\ne"
    val exact  = Patch.of(before, after, context = 0)
    assert(!exact.approximate)
    val capped = Patch.of(before, after, context = 0, maxEditDistance = 1)
    assert(capped.approximate)
    // the common prefix `s` and suffix `d e` are still matched; everything between them is removed, then added
    assert(
      capped.hunks.map(h => (h.oldStart, h.newStart, h.lines)) == Seq(
        (
          2,
          2,
          Seq(
            DiffLine.Removed("a"),
            DiffLine.Removed("b"),
            DiffLine.Removed("m"),
            DiffLine.Removed("c"),
            DiffLine.Added("x"),
            DiffLine.Added("b"),
            DiffLine.Added("m"),
            DiffLine.Added("y"),
          ),
        )
      )
    )

  test("two unrelated 20 000-line texts diff within the default cap instead of exhausting the heap"):
    val before = (0 until 20000).map(i => s"a$i").mkString("\n")
    val after  = (0 until 20000).map(i => s"b$i").mkString("\n")
    val patch  = Patch.of(before, after)
    assert(patch.approximate)
    assert(patch.hunks.map(h => (h.oldCount, h.newCount)) == Seq((20000, 20000)))
    assert(Patch.of("a\nb", "a\nc").approximate == false, "a small diff stays exact")
