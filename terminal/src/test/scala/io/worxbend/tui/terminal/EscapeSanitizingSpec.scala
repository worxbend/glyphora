package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Rect, Style}

import org.scalatest.funsuite.AnyFunSuite

/** Anything caller-supplied that reaches the terminal uninterpreted is a security boundary, not a cosmetic one.
  *
  * An OSC string ends at BEL or ST (XTerm `ctlseqs.ms`, "Operating System Commands"), so a control byte smuggled into a
  * hyperlink target closes the sequence early and lets the remainder execute as terminal commands — OSC 52 writes the
  * system clipboard, OSC 0 rewrites the window title. Link targets routinely come from untrusted text: Markdown, log
  * lines, API responses.
  */
final class EscapeSanitizingSpec extends AnyFunSuite:

  private val Esc = ''
  private val Bel = ''
  private val Csi = '' // the single-byte C1 introducer, on terminals honouring 8-bit controls

  test("a hyperlink target cannot terminate its own OSC string"):
    val hostile = s"http://x$Esc\\$Esc]52;c;cHduZWQ=$Esc\\"
    val emitted = AnsiSequences.linkOpen(hostile)
    // exactly two ESC bytes survive: the opener and its terminator, both ours
    assert(emitted.count(_ == Esc) == 2)
    assert(emitted.startsWith(s"$Esc]8;;"))
    assert(emitted.endsWith(s"$Esc\\"))
    assert(!emitted.contains(s"$Esc]52"))

  test("BEL also cannot terminate a hyperlink target"):
    val emitted = AnsiSequences.linkOpen(s"http://x${Bel}0;PWNED")
    assert(!emitted.contains(Bel))

  test("C1 controls are stripped from hyperlink targets"):
    assert(!AnsiSequences.linkOpen(s"http://x${Csi}31m").contains(Csi))

  test("an ordinary URL is passed through untouched"):
    val url = "https://example.com/a/b?c=d&e=%20f#frag"
    assert(AnsiSequences.linkOpen(url) == s"$Esc]8;;$url$Esc\\")

  test("stripControls keeps tab but drops every other control"):
    assert(AnsiSequences.stripControls("a\tb") == "a\tb")
    assert(AnsiSequences.stripControls(s"a$Esc[2Jb") == "a[2Jb")
    assert(AnsiSequences.stripControls("a\r\nb") == "ab")
    assert(AnsiSequences.stripControls(s"a${Csi}b") == "ab")
    assert(AnsiSequences.stripControls("plain text") == "plain text")

  test("escape bytes in ordinary text never reach a cell"):
    // the buffer path was already safe; this pins it so a future width change cannot quietly open it
    val buffer = Buffer(Rect(0, 0, 8, 1))
    buffer.setString(0, 0, s"a$Esc[31mb", Style.Default)
    val row    = (0 until 8).map(x => buffer.get(x, 0).symbol).mkString
    assert(!row.contains(Esc))
    assert(row.startsWith("a[31mb"))

  test("the blind emergency restore sequence resets idempotent modes without consuming a keyboard push"):
    val restore = AnsiSequences.RestoreAll
    Seq(
      "[?2026l",
      "[?25h",
      "[?1049l",
      "[?2004l",
      "[?1004l",
      "[?1006l",
      "[?1015l",
      "[?1003l",
      "[?1002l",
      "[?1000l",
    )
      .foreach(sequence => assert(restore.contains(s"$Esc$sequence"), s"restore is missing $sequence"))
    assert(!restore.contains(AnsiSequences.PopKittyKeyboard))

  test("the emergency restore closes a synchronized update before anything else it writes"):
    // a frame killed between `?2026h` and `?2026l` leaves the terminal buffering: anything the restore writes after
    // the still-open update would be held back with it, so the close has to come first
    val restore = AnsiSequences.RestoreAll
    assert(restore.startsWith(AnsiSequences.EndSynchronized))
    assert(restore.indexOf(AnsiSequences.EndSynchronized) < restore.indexOf(AnsiSequences.ShowCursor))

  test("a window title cannot terminate its own OSC string"):
    val emitted = AnsiSequences.setTitle(s"report$Esc\\$Esc]52;c;cHduZWQ=$Esc\\")
    // exactly two ESC bytes survive: the OSC 2 opener and its ST terminator, both ours
    assert(emitted.count(_ == Esc) == 2)
    assert(emitted.startsWith(s"$Esc]2;"))
    assert(emitted.endsWith(s"$Esc\\"))
    assert(!emitted.contains(s"$Esc]52"))

  test("BEL and C1 controls are stripped from a window title"):
    assert(!AnsiSequences.setTitle(s"a${Bel}0;PWNED").contains(Bel))
    assert(!AnsiSequences.setTitle(s"a${Csi}31m").contains(Csi))

  test("an ordinary title, including non-ASCII, is passed through untouched"):
    assert(AnsiSequences.setTitle("glyphora — 日本語 ✓") == s"$Esc]2;glyphora — 日本語 ✓$Esc\\")
