package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Rect, Style}

import org.jline.terminal.impl.LineDisciplineTerminal
import org.jline.terminal.{Attributes, Terminal}
import org.jline.utils.{InfoCmp, NonBlockingReader}

import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, IOException, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8

/** What [[JLine3Backend]] assumes about JLine, asserted against a real JLine terminal.
  *
  * Every other suite in this module tests a pure helper, so a green `terminal.test` says nothing about whether the
  * JLine version on the classpath still behaves the way the backend was written against. That gap is not theoretical:
  * `org.jline` appears in two import lines of one main source and, before this file, in no test at all, while the
  * dependency has moved across a major version.
  *
  * The seam is `JLine3Backend.wrapping`, which exists for exactly this. The terminal under it is a
  * `LineDisciplineTerminal`: a real JLine terminal, with JLine's own terminfo parsing, attribute handling and output
  * encoder, whose "device" is a byte array. Two properties make it the right one and `TerminalBuilder.streams(…)` the
  * wrong one on JLine 4:
  *
  *   - the builder now hands non-system streams to a provider that opens an actual pty, so the bytes reach the sink on
  *     a pump thread (races) and JLine writes its own capability queries into the sink on construction (noise);
  *   - a `LineDisciplineTerminal` writes through synchronously and emits nothing of its own, so a byte in the sink is a
  *     byte this backend wrote.
  *
  * Nothing here ever waits on input beyond a bounded timeout. The reader is fed by nobody, so it answers `READ_EXPIRED`
  * when its timeout elapses; the only calls that read at all are `enableRawMode`'s capability probe and `windowSize`'s
  * pixel query, each bounded at 100 ms by construction. An earlier attempt at a JLine-backed suite blocked on a reply
  * that a stream pair could never deliver and had to be deleted — see [[JLine3BackendRedrawSpec]]. This one cannot
  * repeat that, because every read has a timeout and the suite asserts only on bytes written.
  */
final class JLineContractSpec extends AnyFunSuite:

  /** A backend over a real JLine terminal whose output is a byte sink and whose input never delivers. */
  private final class Wired(termType: String):

    private val sink = ByteArrayOutputStream()

    val terminal: Terminal =
      LineDisciplineTerminal("glyphora-contract", termType, sink, UTF_8, Terminal.SignalHandler.SIG_IGN)

    val backend: JLine3Backend = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)

    /** Everything written to the terminal since the last [[forget]], decoded the way the terminal encoded it. */
    def written: String = sink.toString(UTF_8)

    /** Drops what has been written so far, so the next assertion reads one operation's output and not the set-up's. */
    def forget(): Unit = sink.reset()

  private def wired[A](termType: String)(body: Wired => A): A =
    val harness = Wired(termType)
    // closing twice is part of the contract — the shutdown hook races the runner's teardown — so a test that has
    // already closed the backend itself is not disturbed by this one
    try body(harness)
    finally harness.backend.close()

  private def occurrences(haystack: String, needle: String): Int =
    var count = 0
    var at    = haystack.indexOf(needle)
    while at >= 0 do
      count += 1
      at = haystack.indexOf(needle, at + needle.length)
    count

  // ---------------------------------------------------------------- the reader sentinels

  /** [[InputDecoder]] hand-copies JLine's two reader sentinels into its own constants, with a comment saying "the value
    * is JLine's", and nothing has ever checked that claim. They are not interchangeable: `-1` makes the decoder answer
    * `Event.EndOfInput` and the runner shut down, `-2` means "nothing right now" and sends it round the loop. If a
    * JLine release renumbered them, the decoder would read every expired read as end of input — or, the other way
    * round, spin at 100% CPU on a closed stream. Neither shows up as a compile error, and both show up first in
    * someone's terminal.
    */
  test("the reader sentinels InputDecoder hand-copies are still the values JLine uses"):
    assert(InputDecoder.EndOfStream == NonBlockingReader.EOF)
    assert(InputDecoder.ReadExpired == NonBlockingReader.READ_EXPIRED)
    // spelled out too, because the pair only means anything if they stay distinct
    assert(InputDecoder.EndOfStream != InputDecoder.ReadExpired)

  // ---------------------------------------------------------------- the alternate screen gate

  test("enterAlternateScreen switches and clears on a terminal whose terminfo has smcup"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.enterAlternateScreen().isRight)
      assert(harness.written == AnsiSequences.EnterAlternateScreen + AnsiSequences.clear(ClearType.All))
    }

  /** The guard that stops the app painting over a Linux console's scrollback, exercised end to end rather than on the
    * flag it sets. `ansi` stands in for `TERM=linux` because JLine carries `ansi.caps` inside its own jar, so the
    * assertion does not depend on an `infocmp` binary being installed on the machine running the suite — it has no
    * `smcup` either, which is the whole of what the gate reads.
    *
    * The `clear_screen` assertion is what keeps this honest: without it the test would still pass if JLine's terminfo
    * lookup returned nothing for *every* capability, which is the failure mode a migration actually produces.
    */
  test("a terminal whose terminfo has no smcup is refused rather than painted over"):
    wired("ansi") { harness =>
      assert(Option(harness.terminal.getStringCapability(InfoCmp.Capability.clear_screen)).isDefined)
      assert(Option(harness.terminal.getStringCapability(InfoCmp.Capability.enter_ca_mode)).isEmpty)

      val refused = harness.backend.enterAlternateScreen()
      assert(refused == Left(BackendError.UnsupportedTerminal("ansi has no alternate screen (no smcup capability)")))
      assert(harness.written.isEmpty) // refused *before* writing, or the screen is already gone
    }

  // ---------------------------------------------------------------- the dumb-terminal refusal

  /** `create` with no TTY is the path every example's native binary takes in CI, and the message it produces is the
    * string `ci.yml` greps for — [[BackendErrorMessageSpec]] pins the wording, and this pins that `create` actually
    * reaches it. What connects the two is JLine's own decision to fall back to a dumb terminal when it cannot open the
    * controlling one; that fallback is the part no test covered.
    *
    * Cancelled rather than failed when the JVM running the suite does have a controlling terminal: there is then no
    * dumb terminal for `create` to refuse, and a test that failed there would be punishing a working machine.
    */
  test("create refuses the dumb terminal JLine falls back to when there is no TTY"):
    JLine3Backend.create(ColorDepth.NoColor) match
      case Left(error)    =>
        assert(error == BackendError.UnsupportedTerminal("dumb terminal (no TTY attached)"))
        assert(error.message == "terminal not supported: dumb terminal (no TTY attached)")
      case Right(backend) =>
        val _ = backend.close()
        cancel("this JVM has a controlling terminal, so `create` had no dumb terminal to refuse")

  // ---------------------------------------------------------------- the frame diff, through JLine's writer

  /** The diff is the reason `draw` is cheap, and the forced repaint is the reason a disturbed screen recovers. Both are
    * asserted on the bytes that left the terminal, which is the one place `HeadlessBackend` cannot reach: it snapshots
    * the composed buffer and never asks what would have been written.
    *
    * The wide CJK glyph is not decoration. `create` configures three separate JLine encodings precisely because a
    * locale-derived encoder renders every non-ASCII glyph in the UI as `?`; decoding the sink as UTF-8 and finding the
    * glyph again is what proves the writer encoded rather than mangled it.
    */
  test("an identical second frame writes nothing, and a requested redraw writes every cell again"):
    wired("xterm-256color") { harness =>
      val buffer = Buffer(Rect(0, 0, 6, 1))
      buffer.setString(0, 0, "ok漢", Style.Default)

      // composed with the same encoder the backend owns, so this pins the whole composition — synchronised-output
      // wrapper included, which an unprobed terminal gets because silence means "use it"
      val expected =
        AnsiSequences.frame(FrameEncoder(ColorDepth.TrueColor).encodeAll(buffer), synchronizedOutput = true)

      assert(harness.backend.draw(buffer).isRight)
      assert(harness.written == expected)
      assert(harness.written.contains("漢"))

      harness.forget()
      assert(harness.backend.draw(buffer).isRight)
      assert(harness.written.isEmpty) // nothing changed, so a redraw-on-tick app stays silent

      harness.backend.requestFullRedraw()
      assert(harness.backend.draw(buffer).isRight)
      assert(harness.written == expected) // every cell again, blanks included
    }

  // ---------------------------------------------------------------- teardown

  /** The JLine half of raw mode: `enterRawMode` hands back the *previous* attributes, and putting those back is the
    * whole of `disableRawMode`. A JLine that changed either side of that bargain would leave the shell in raw mode
    * after every glyphora app — the single most user-visible failure this library has, and one no pure helper can
    * detect. `cookedAttributes` doubling as "are we in raw mode?" is checked on the same run, because the flag and the
    * restore have to agree.
    */
  test("raw mode is entered and released on the terminal itself, not merely recorded"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.disableRawMode() == Left(BackendError.NotInRawMode))

      assert(harness.backend.enableRawMode().isRight)
      assert(harness.written.startsWith(AnsiSequences.SaveCursor)) // where the shell's prompt was
      assert(!harness.terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ICANON))
      assert(!harness.terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ECHO))

      harness.forget()
      assert(harness.backend.disableRawMode().isRight)
      // the shell's own line discipline, exactly as it was handed over
      assert(harness.terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ICANON))
      assert(harness.terminal.getAttributes.getLocalFlag(Attributes.LocalFlag.ECHO))
      // last of all, so the shell resumes on the line it started on: the restore pairs with the save above
      assert(harness.written.endsWith(AnsiSequences.RestoreCursor))
      assert(harness.backend.disableRawMode() == Left(BackendError.NotInRawMode))
    }

  /** Undressing has to happen in this order or the user pays for it: the frame has to stop being on the alternate
    * screen before cooked mode comes back, and the cursor has to be visible again before the app lets go. The order is
    * `releaseTerminal`'s and is reachable from nowhere else.
    *
    * Nothing is asked of the terminal after `close()`, because a closed JLine terminal throws on every accessor — which
    * is itself why `releaseTerminal` runs before the handle is released rather than after.
    */
  test("close undresses the terminal in the order that keeps the shell usable"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.enableRawMode().isRight)
      assert(harness.backend.enterAlternateScreen().isRight)
      assert(harness.backend.hideCursor().isRight)
      assert(harness.backend.enableMouseCapture().isRight)
      harness.forget()

      assert(harness.backend.close().isRight)

      val undressed = harness.written
      val mouse     = undressed.indexOf(AnsiSequences.DisableMouseCapture)
      val cursor    = undressed.indexOf(AnsiSequences.ShowCursor)
      val screen    = undressed.indexOf(AnsiSequences.LeaveAlternateScreen)
      val prompt    = undressed.indexOf(AnsiSequences.RestoreCursor)
      assert(mouse >= 0, undressed)
      assert(cursor > mouse, undressed)
      assert(screen > cursor, undressed)
      assert(prompt > screen, undressed)
    }

  test("close restores owned cursor shape and blink before making the cursor visible"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.hideCursor().isRight)
      assert(harness.backend.setCursorShape(CursorShape.SteadyBar).isRight)
      assert(harness.backend.setCursorBlink(false).isRight)
      harness.forget()

      assert(harness.backend.close().isRight)
      assert(
        harness.written == AnsiSequences.ResetCursorShape + AnsiSequences.EnableCursorBlink + AnsiSequences.ShowCursor
      )
      harness.forget()
      assert(harness.backend.close().isRight)
      assert(harness.written.isEmpty)
    }

  test("cursor restoration failures do not skip later releases or closing the JLine handle"):
    val sink     = ByteArrayOutputStream()
    val first    = new IOException("shape reset failed")
    val second   = new IOException("blink restore failed")
    val attempts = List.newBuilder[String]
    var faults   = Map.empty[String, IOException]
    val terminal = new LineDisciplineTerminal(
      "glyphora-teardown-failure",
      "xterm-256color",
      sink,
      UTF_8,
      Terminal.SignalHandler.SIG_IGN,
    ):
      override def writer(): PrintWriter =
        val delegate = super.writer()
        if faults.isEmpty then delegate
        else
          new PrintWriter(delegate):
            override def write(sequence: String): Unit =
              attempts += sequence
              faults.get(sequence) match
                case Some(error) => throw error
                case None        => super.write(sequence)
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      assert(backend.enableRawMode().isRight)
      assert(backend.enterAlternateScreen().isRight)
      assert(backend.hideCursor().isRight)
      assert(backend.setCursorShape(CursorShape.SteadyBar).isRight)
      assert(backend.setCursorBlink(false).isRight)
      faults = Map(AnsiSequences.ResetCursorShape -> first, AnsiSequences.EnableCursorBlink -> second)
      sink.reset()
      assert(backend.close() == Left(BackendError.Io(first)))
      assert(
        attempts.result() == List(
          AnsiSequences.ResetCursorShape,
          AnsiSequences.EnableCursorBlink,
          AnsiSequences.ShowCursor,
          AnsiSequences.LeaveAlternateScreen,
          AnsiSequences.PopKittyKeyboard,
          AnsiSequences.DisableFocusReporting,
          AnsiSequences.DisableBracketedPaste,
          AnsiSequences.RestoreCursor,
        )
      )
      assert(sink.toString(UTF_8).contains(AnsiSequences.ShowCursor))
      intercept[IllegalStateException](terminal.writer()) // close was attempted despite both mode failures
    finally terminal.close()

  /** The title stack is the one piece of terminal state where doing the work twice is worse than not doing it: a second
    * push leaves an entry nobody pops, and a second pop discards the title of whatever is above us. `close()` runs
    * twice in the ordinary case — the shutdown hook races the runner's teardown — so "exactly once" has to survive it.
    */
  test("the title stack is pushed once per app and popped once per app, whatever the call counts"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.setTitle("first").isRight)
      assert(harness.backend.setTitle("second").isRight)

      val retitled = harness.written
      assert(occurrences(retitled, AnsiSequences.PushTitle) == 1)
      assert(retitled.contains(AnsiSequences.setTitle("first")))
      assert(retitled.contains(AnsiSequences.setTitle("second")))

      harness.forget()
      assert(harness.backend.close().isRight)
      assert(harness.written == AnsiSequences.PopTitle)

      harness.forget()
      assert(harness.backend.close().isRight)
      assert(occurrences(harness.written, AnsiSequences.PopTitle) == 0)
    }

  /** An app that never set a title must leave the stack alone entirely: popping an entry it never pushed would hand the
    * shell back whatever title the window had two programs ago.
    */
  test("an app that never set a title neither pushes nor pops"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.close().isRight)
      assert(occurrences(harness.written, AnsiSequences.PushTitle) == 0)
      assert(occurrences(harness.written, AnsiSequences.PopTitle) == 0)
    }

  // ---------------------------------------------------------------- suspend and the capability probe

  /** The probe is a *read*, so taking the terminal back must not run it. `reacquireTerminal` runs on JLine's
    * signal-dispatch thread after SIGCONT, and in the `finally` of every `suspend` — on either, reading would race the
    * render thread's in-flight decode of the same decoder state, and a 100 ms DA1 round trip would tax every `suspend`,
    * `printAbove` and `insertBefore`. The probe is paid once, at the first raw-mode entry; the re-dress re-applies the
    * modes that first probe established.
    */
  test("taking the terminal back re-dresses raw mode without re-probing capabilities"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.enableRawMode().isRight)
      harness.forget()

      assert(harness.backend.suspend(()) == Right(()))

      val redressed = harness.written
      // no query went out — not the DA1 fence, not the DECRQM mode queries
      assert(!redressed.contains(AnsiSequences.QueryPrimaryDeviceAttributes))
      assert(!redressed.contains(AnsiSequences.queryPrivateMode(CapabilityReplies.SynchronizedOutputMode)))
      // the input modes the retained probe answer established are back: silence means "use it", so all three return
      assert(redressed.contains(AnsiSequences.EnableBracketedPaste))
      assert(redressed.contains(AnsiSequences.EnableFocusReporting))
      assert(redressed.contains(AnsiSequences.PushKittyKeyboard))
    }

  test("a temporary handover restores the shell modes and then the exact app cursor shape"):
    for shape <- CursorShape.values if shape != CursorShape.Default do
      wired("xterm-256color") { harness =>
        assert(harness.backend.hideCursor().isRight)
        assert(harness.backend.setCursorShape(shape).isRight)
        assert(harness.backend.setCursorBlink(false).isRight)
        harness.forget()

        assert(harness.backend.suspend {
          assert(
            harness.written == AnsiSequences.ResetCursorShape + AnsiSequences.EnableCursorBlink + AnsiSequences.ShowCursor
          )
          harness.forget()
        } == Right(()))
        assert(
          harness.written == AnsiSequences.HideCursor + AnsiSequences.cursorShape(
            shape
          ) + AnsiSequences.DisableCursorBlink
        )
        harness.forget()
        assert(harness.backend.close().isRight)
        assert(
          harness.written == AnsiSequences.ResetCursorShape + AnsiSequences.EnableCursorBlink + AnsiSequences.ShowCursor
        )
      }

  test("untouched or explicitly reset cursor modes are not changed by close or handover"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.suspend(()) == Right(()))
      assert(harness.written.isEmpty)
      assert(harness.backend.setCursorShape(CursorShape.SteadyBar).isRight)
      assert(harness.backend.setCursorShape(CursorShape.Default).isRight)
      assert(harness.backend.setCursorBlink(false).isRight)
      assert(harness.backend.setCursorBlink(true).isRight)
      harness.forget()
      assert(harness.backend.suspend(()) == Right(()))
      assert(harness.written.isEmpty)
      assert(harness.backend.close().isRight)
      assert(harness.written.isEmpty)
    }

  test("handover restores shape-only and blink-only ownership independently"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.setCursorShape(CursorShape.SteadyUnderline).isRight)
      harness.forget()
      assert(harness.backend.suspend {
        assert(harness.written == AnsiSequences.ResetCursorShape)
        harness.forget()
      } == Right(()))
      assert(harness.written == AnsiSequences.cursorShape(CursorShape.SteadyUnderline))
      assert(harness.backend.setCursorShape(CursorShape.Default).isRight)
      assert(harness.backend.setCursorBlink(false).isRight)
      harness.forget()
      assert(harness.backend.suspend {
        assert(harness.written == AnsiSequences.EnableCursorBlink)
        harness.forget()
      } == Right(()))
      assert(harness.written == AnsiSequences.DisableCursorBlink)
    }

  test("a failing handover body still reacquires its remembered cursor modes"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.setCursorShape(CursorShape.SteadyBar).isRight)
      assert(harness.backend.setCursorBlink(false).isRight)
      harness.forget()
      val failure = new IllegalStateException("body failed")
      assert(harness.backend.suspend(throw failure) == Left(BackendError.Io(failure)))
      assert(
        harness.written == AnsiSequences.ResetCursorShape + AnsiSequences.EnableCursorBlink +
          AnsiSequences.cursorShape(CursorShape.SteadyBar) + AnsiSequences.DisableCursorBlink
      )
    }

  // ---------------------------------------------------------------- windowSize's pixel query

  /** Asked before raw mode there is no reader to receive a reply, so the query must be skipped entirely — and skipping
    * it must not mark it asked, or the first real opportunity to measure a cell shape (raw mode entered) silently never
    * comes.
    */
  test("windowSize asked before raw mode spends nothing and does not suppress the later ask"):
    wired("xterm-256color") { harness =>
      val askedEarly = harness.backend.windowSize
      assert(askedEarly.map(_.pixels) == Right(None))
      assert(askedEarly.map(_.cells) == harness.backend.size)
      assert(!harness.written.contains(AnsiSequences.RequestTextAreaPixels)) // skipped, not merely timed out

      assert(harness.backend.enableRawMode().isRight)
      harness.forget()
      val _ = harness.backend.windowSize // pays the round trip now that raw mode is on and nobody answers
      assert(harness.written.contains(AnsiSequences.RequestTextAreaPixels))
    }

  /** The query is asked at most once. This terminal never answers, so the first ask caches "no pixels" and the second
    * call must not write `ESC[14t` again.
    */
  test("the pixel query is asked once and its silence is cached"):
    wired("xterm-256color") { harness =>
      assert(harness.backend.enableRawMode().isRight)
      harness.forget()
      val _ = harness.backend.windowSize
      assert(harness.written.contains(AnsiSequences.RequestTextAreaPixels))
      harness.forget()
      val _ = harness.backend.windowSize
      assert(!harness.written.contains(AnsiSequences.RequestTextAreaPixels))
    }
