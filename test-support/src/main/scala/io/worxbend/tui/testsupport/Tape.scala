package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Buffer, BufferExport, KeyEvent, Size}
import io.worxbend.tui.runtime.{AsciicastRecorder, CompletedFrame, RunnerError}
import io.worxbend.tui.terminal.HeadlessBackend

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.util.Try

/** One line of a tape — the subset of [[https://github.com/charmbracelet/vhs VHS]] a `Pilot` can perform. */
enum TapeCommand:

  /** `Type "text"`: one key event per code point, a frame recorded after each. */
  case Type(text: String)

  /** `Key <spec>`, `Enter` and `Tab`: a key press in the spelling `binding` and `Pilot.press` take. */
  case Key(spec: String)

  /** `Sleep 500ms` / `Sleep 1.5s`: advances the recording's clock. No real time passes — the cast is deterministic. */
  case Sleep(duration: FiniteDuration)

  /** `Wait /regex/`: blocks until the screen text matches, or fails naming the pattern. */
  case Wait(regex: String)

  /** `Screenshot name`: writes `<name>.txt` and `<name>.svg` of the current frame. */
  case Screenshot(name: String)

  /** `Set Width n`: the terminal's columns — the initial size before the app starts, a resize afterwards. */
  case SetWidth(columns: Int)

  /** `Set Height n`: the terminal's rows, with the same two meanings as [[SetWidth]]. */
  case SetHeight(rows: Int)

/** A parsed tape: the commands in order. Build one with [[Tape.parse]]; perform it with [[Tape.run]]. */
final case class Tape(commands: Seq[TapeCommand])

/** What a run left on disk: the `.cast` recording, every screenshot file in the order written, and the frame behind
  * each screenshot by name — so a test can hand it to `GoldenFrames.assertMatches` after the app has exited.
  */
final case class TapeResult(cast: Path, screenshots: Seq[Path], frames: Map[String, Buffer])

/** Tape scripts: a text file of VHS-style commands that drives an app through a [[Pilot]] and leaves behind an
  * asciicast recording and screenshots. This is how documentation GIFs and screenshots become build outputs: the tape
  * is checked in next to the test that runs it, and `scripts/render-tapes.sh` regenerates every artefact.
  *
  * The recording's clock is virtual. A `Sleep` advances it and nothing else does, so the same tape over the same app
  * produces the same `.cast` byte for byte, and a tape never waits in real time except inside `Wait`, which polls the
  * screen the way `Pilot.waitUntil` does.
  *
  * Ownership and threading: [[run]] owns the pilot it starts and closes it, including when a command fails. It runs on
  * the calling (test) thread; the app runs on the pilot's thread, exactly as it does under `Pilot.using`.
  */
object Tape:

  /** The environment variable that tells a tape-running suite where to leave its casts and screenshots; unset, a suite
    * uses a temporary directory. `scripts/render-tapes.sh` sets it to `build/tapes`.
    */
  val OutputEnvVar: String = "GLYPHORA_TAPE_OUTPUT"

  /** The directory `GLYPHORA_TAPE_OUTPUT` names, if any. */
  def outputDirectory: Option[Path] = sys.env.get(OutputEnvVar).filter(_.nonEmpty).map(Path.of(_))

  /** The virtual time one typed key takes in a recording, so `Type` plays back as typing rather than a paste. */
  val TypingDelay: FiniteDuration = 50.millis

  private val DefaultSize: Size = Size(80, 24)

  private val ScreenshotName: Pattern = Pattern.compile("[A-Za-z0-9._-]+")
  private val SleepSpec: Pattern      = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s)")

  /** Parses `script`, one command per line, `#` comments and blank lines ignored. `Left` names the first line that is
    * not in the subset and what is wrong with it, so a typo in a tape is reported rather than skipped.
    */
  def parse(script: String): Either[String, Tape] =
    val lines    = script.linesIterator.zipWithIndex.toSeq
    val commands = lines.map { (raw, index) =>
      val line = raw.trim
      if line.isEmpty || line.startsWith("#") then Right(None)
      else parseLine(line).left.map(problem => s"line ${index + 1}: $problem").map(Some(_))
    }
    commands.collectFirst { case Left(problem) => problem } match
      case Some(problem) => Left(problem)
      case None          => Right(Tape(commands.flatMap(_.toOption).flatten))

  private def parseLine(line: String): Either[String, TapeCommand] =
    val (command, rest) = line.span(!_.isWhitespace)
    val argument        = rest.trim
    command match
      case "Type"       =>
        quoted(argument).toRight("Type takes a double-quoted string: Type \"text\"").map(TapeCommand.Type(_))
      case "Enter"      => Right(TapeCommand.Key("enter"))
      case "Tab"        => Right(TapeCommand.Key("tab"))
      case "Key"        =>
        KeyEvent
          .parse(argument)
          .left
          .map(problem => s"bad key spec '$argument': $problem")
          .map(_ => TapeCommand.Key(argument))
      case "Sleep"      => sleep(argument).toRight(s"Sleep takes a duration such as 500ms or 1.5s, not '$argument'")
      case "Wait"       =>
        if argument.length >= 2 && argument.startsWith("/") && argument.endsWith("/") then
          val regex = argument.drop(1).dropRight(1)
          Try(Pattern.compile(regex)).toEither.left
            .map(error => s"Wait: bad regex: ${error.getMessage}")
            .map(_ => TapeCommand.Wait(regex))
        else Left("Wait takes a /regex/")
      case "Screenshot" =>
        if ScreenshotName.matcher(argument).matches() then Right(TapeCommand.Screenshot(argument))
        else Left(s"Screenshot takes a file name of letters, digits, '.', '-' and '_', not '$argument'")
      case "Set"        =>
        val (dimension, value) = argument.span(!_.isWhitespace)
        val number             = value.trim.toIntOption.filter(_ > 0)
        dimension match
          case "Width"  =>
            number.map(TapeCommand.SetWidth(_)).toRight(s"Set Width takes a positive number, not '${value.trim}'")
          case "Height" =>
            number.map(TapeCommand.SetHeight(_)).toRight(s"Set Height takes a positive number, not '${value.trim}'")
          case other    => Left(s"Set knows Width and Height, not '$other'")
      case other        => Left(s"unknown command '$other'")

  /** The contents of a double-quoted literal with `\"` and `\\` escapes, or `None` when `text` is not one. */
  private def quoted(text: String): Option[String] =
    if text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"") then None
    else
      val body = text.drop(1).dropRight(1)
      val out  = StringBuilder()
      var i    = 0
      var ok   = true
      while ok && i < body.length do
        val c = body.charAt(i)
        if c == '\\' && i + 1 < body.length then
          out += body.charAt(i + 1)
          i += 2
        else if c == '"' || c == '\\' then ok = false
        else
          out += c
          i += 1
      Option.when(ok)(out.result())

  private def sleep(text: String): Option[TapeCommand.Sleep] =
    val matcher = SleepSpec.matcher(text)
    Option.when(matcher.matches()) {
      val amount = BigDecimal(matcher.group(1))
      val millis = if matcher.group(2) == "s" then amount * 1000 else amount
      TapeCommand.Sleep(millis.toLong.millis)
    }

  /** Performs `tape` against the app `app` starts over the backend it is handed, writing `<name>.cast` and every
    * screenshot into `directory` (created if need be). The terminal starts at the size the leading `Set Width`/`Set
    * Height` lines give (80x24 otherwise); a `Set` after the first interaction is a resize.
    *
    * A `Wait` that does not match within `waitTimeout` fails with an `AssertionError` naming the pattern, and so does
    * an app that crashes or returns a `RunnerError`; the pilot is closed either way.
    */
  def run(tape: Tape, directory: Path, name: String, waitTimeout: FiniteDuration = Pilot.DefaultTimeout)(
      app: HeadlessBackend => Either[RunnerError, Unit]
  ): TapeResult =
    Files.createDirectories(directory)
    val leading  = tape.commands.takeWhile {
      case _: TapeCommand.SetWidth | _: TapeCommand.SetHeight => true
      case _                                                  => false
    }
    val size     = leading.foldLeft(DefaultSize) {
      case (size, TapeCommand.SetWidth(columns)) => size.copy(width = columns)
      case (size, TapeCommand.SetHeight(rows))   => size.copy(height = rows)
      case (size, _)                             => size
    }
    val castPath = directory.resolve(s"$name.cast")
    var clock    = 0L
    val recorder = AsciicastRecorder(Files.newBufferedWriter(castPath, StandardCharsets.UTF_8), () => clock, () => 0L)
    val screenshots                 = Seq.newBuilder[Path]
    var frames: Map[String, Buffer] = Map.empty
    var recordedFrames              = 0L
    val pilot                       = Pilot.start(size)(app)
    def record(): Unit              =
      pilot.waitForIdle(waitTimeout)
      if pilot.backend.drawCount > 0 then
        val frame = pilot.lastFrame
        recorder(CompletedFrame(frame, frame.area, recordedFrames))
        recordedFrames += 1
    try
      record()
      tape.commands.drop(leading.length).foreach {
        case TapeCommand.Type(text)        =>
          text.codePoints().forEach { codePoint =>
            pilot.typeText(Character.toString(codePoint))
            clock += TypingDelay.toNanos
            record()
          }
        case TapeCommand.Key(spec)         =>
          pilot.press(spec)
          record()
        case TapeCommand.Sleep(duration)   =>
          clock += duration.toNanos
        case TapeCommand.Wait(regex)       =>
          val pattern = Pattern.compile(regex)
          pilot.waitUntil(s"the screen to match /$regex/", waitTimeout)(pattern.matcher(pilot.screenText).find())
          record()
        case TapeCommand.Screenshot(label) =>
          record()
          val frame = pilot.lastFrame
          val text  = directory.resolve(s"$label.txt")
          val svg   = directory.resolve(s"$label.svg")
          Files.write(text, BufferAssertions.text(frame).getBytes(StandardCharsets.UTF_8))
          Files.write(svg, BufferExport.svg(frame).getBytes(StandardCharsets.UTF_8))
          screenshots += text += svg
          frames = frames.updated(label, frame)
        case TapeCommand.SetWidth(columns) =>
          pilot.resize(columns, pilot.lastFrame.area.height)
          record()
        case TapeCommand.SetHeight(rows)   =>
          pilot.resize(pilot.lastFrame.area.width, rows)
          record()
      }
    finally
      try pilot.close()
      finally recorder.close()
    TapeResult(castPath, screenshots.result(), frames)
