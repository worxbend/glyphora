package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Size, Text, Widget}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.terminal.{ColorDepth, FrameEncoder, StandardOutput}
import io.worxbend.tui.widgets.Paragraph

import java.io.{FileDescriptor, FileOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Print mode: render any view, widget or [[Text]] *once* to standard output as styled text, with no runner, no event
  * loop and no terminal taken over — the way `Rich` prints a table or `Ink` renders to a string.
  *
  * This is what opens every widget — tables, trees, charts, Markdown, panels — to a command-line tool that will never
  * be a full-screen application: `Console.print(panel("Build")(table(rows, header)))` lays the view out at the
  * terminal's width, measures the rows it needs, composes a frame through [[Snapshot]] and writes it as
  * newline-separated rows with the colour depth the environment allows. The encoding is `FrameEncoder.encodeLines` in
  * `tui-terminal`: no cursor movement, styles as deltas from the terminal default, a reset on every row that set one —
  * and, when colour is off, no escape sequence at all, so `NO_COLOR=1 mytool > report.txt` leaves a plain text file
  * behind.
  *
  * `width` and `colors` default to what [[detectedWidth]] and [[detectedColors]] read from the process: the terminal's
  * width when standard output is one, else `COLUMNS`, else 80 columns; the colour depth [[ColorDepth.detect]] resolves
  * (`NO_COLOR`, `FORCE_COLOR`, `CLICOLOR_FORCE`, `TERM=dumb` and the terminal's capability) when standard output is a
  * terminal, and *no* colour when it is not, unless one of the force variables says otherwise. The height is the view's
  * own measurement — `Element.intrinsicHeight` at that width — falling back to `LINES`, then 24 rows, for a view that
  * cannot say (one whose root is `.fill`, say); pass `height` to decide yourself.
  *
  * What it deliberately is not: interactive or animated, for the same reasons [[Snapshot]] is not. Focus is drawn on
  * the first focusable element, an animated element renders at whatever position it computes for the time it is handed,
  * and reactive reads subscribe nothing. A `TuiApp` that should degrade to this when it has no terminal sets
  * `RunnerConfig.withNonInteractive` instead of calling this directly — see
  * [[io.worxbend.tui.runtime.NonInteractivePolicy]].
  *
  * Shadowing, stated plainly because it bites: under `import io.worxbend.tui.dsl.*` the name `Console` resolves here
  * rather than to `scala.Console`, so an app that wrote `Console.println(...)` or `Console.err` with the one import has
  * to write `scala.Console.err` (or plain `println`). The name is kept because it is the one every terminal toolkit
  * uses for exactly this object.
  *
  * Callable from any thread: it registers no render loop and writes no signal. The `print` variants write to
  * `System.out` as UTF-8 regardless of the platform charset — every border and glyph is non-ASCII, and a POSIX locale
  * would otherwise print them as `?` — and flush after each call.
  */
object Console:

  /** The width print mode falls back to when neither the terminal nor `COLUMNS` says. */
  val FallbackWidth: Int = 80

  /** The height print mode falls back to when the view cannot measure itself and `LINES` does not say. */
  val FallbackHeight: Int = 24

  // ---------------------------------------------------------------- render: text out, nothing written

  /** `view` as styled text: laid out at `width`, `height` rows tall when given and the view's own measurement
    * otherwise, encoded for `colors`. Rows are separated by `\n`; there is no trailing newline, so the result slots
    * into a larger message. `theme` is the one the view's themed factories draw with.
    */
  def render(
      view: View,
      width: Int,
      colors: ColorDepth,
      theme: Theme = Theme.Dark,
      height: Option[Int] = None,
  ): String =
    val element        = view(using ReactiveScope.untracked, theme)
    val rows           = height.getOrElse(element.intrinsicHeight(width).getOrElse(detectedHeight()))
    val composed: View = element
    val buffer         = Snapshot.render(Size(math.max(0, width), math.max(0, rows)), theme)(composed)
    FrameEncoder.encodeLines(buffer, colors)

  /** A raw widget rendered into a `size`-shaped buffer, encoded for `colors` — for a widget that is not wrapped in an
    * element, or one whose size the caller already knows. Named apart from [[render]] rather than overloading it: a
    * [[View]] is a context function, and an overloaded parameter gives the compiler no expected type to build one from,
    * so `render(panel(...))` would stop compiling.
    */
  def renderWidget(widget: Widget, size: Size, colors: ColorDepth): String =
    val buffer = Buffer(Rect(0, 0, math.max(0, size.width), math.max(0, size.height)))
    widget.render(buffer.area, buffer)
    FrameEncoder.encodeLines(buffer, colors)

  /** A [[Text]] as styled lines: one row per `Line`, as wide as its widest line, each span's style encoded for
    * `colors`.
    */
  def renderLines(text: Text, colors: ColorDepth): String =
    renderWidget(Paragraph(text), Size(text.width, text.height), colors)

  // ---------------------------------------------------------------- print: render, then write to standard output

  /** [[render]]s `view` and writes it to standard output followed by a newline. Every parameter defaults to what the
    * environment says; see the object notes.
    */
  def print(
      view: View,
      width: Int = detectedWidth(),
      colors: ColorDepth = detectedColors(),
      theme: Theme = Theme.Dark,
      height: Option[Int] = None,
  ): Unit =
    write(render(view, width, colors, theme, height) + "\n")

  /** [[render]]s a raw widget at `size` with the detected colour depth and writes it, newline-terminated. */
  def print(widget: Widget, size: Size): Unit =
    write(renderWidget(widget, size, detectedColors()) + "\n")

  /** Writes `text` as styled lines with the detected colour depth, newline-terminated. */
  def printLines(text: Text): Unit =
    write(renderLines(text, detectedColors()) + "\n")

  // ---------------------------------------------------------------- what the environment says

  /** How wide to print: the terminal's own width when standard output is one, else a positive `COLUMNS`, else
    * [[FallbackWidth]]. Both inputs are parameters so a test can inject them; the defaults read the process.
    */
  def detectedWidth(env: Map[String, String] = sys.env, terminalWidth: => Option[Int] = StandardOutput.width): Int =
    terminalWidth.orElse(positive(env, "COLUMNS")).getOrElse(FallbackWidth)

  /** How tall a no-TTY run composes its frame, and the height a view that cannot measure itself prints at: a positive
    * `LINES`, else [[FallbackHeight]].
    */
  def detectedHeight(env: Map[String, String] = sys.env): Int =
    positive(env, "LINES").getOrElse(FallbackHeight)

  /** The colour depth to print with. On a terminal, what [[ColorDepth.detect]] resolves from `env`. Off a terminal — a
    * file, a pipe — no colour at all, because `TERM` is still set in a piped process's environment and would otherwise
    * fill the file with escape sequences; `FORCE_COLOR` or `CLICOLOR_FORCE` overrides that, which is how a pager or a
    * CI log viewer that renders ANSI asks for it.
    */
  def detectedColors(env: Map[String, String] = sys.env, terminal: Boolean = StandardOutput.isTerminal): ColorDepth =
    if terminal || ColorDepth.colorForced(env) then ColorDepth.detect(env) else ColorDepth.NoColor

  /** Whether a run has no terminal to be interactive on: standard output is not a terminal, or `CI` is set to anything
    * but the empty string, `0` or `false`. What `TuiApp.printTarget` consults before applying a
    * [[io.worxbend.tui.runtime.NonInteractivePolicy]].
    */
  def isNonInteractive(env: Map[String, String] = sys.env, terminal: Boolean = StandardOutput.isTerminal): Boolean =
    !terminal || env.get("CI").exists(value => value.nonEmpty && value != "0" && !value.equalsIgnoreCase("false"))

  /** Writes `text` to standard output as UTF-8 and flushes — the sink every `print` here and every no-TTY run uses.
    *
    * Writes to the process's standard output *descriptor*, not to whatever `System.out` currently is: a `TuiApp` run
    * may have redirected `System.out` into its stdio capture, and a frame printed into the capture would never reach
    * the file or pipe it was meant for.
    */
  def write(text: String): Unit =
    val out = PrintStream(FileOutputStream(FileDescriptor.out), false, UTF_8)
    out.print(text)
    out.flush()

  private def positive(env: Map[String, String], name: String): Option[Int] =
    env.get(name).flatMap(_.trim.toIntOption).filter(_ > 0)

/** Where a `TuiApp` run that has no terminal prints, and at what size and colour depth — what `TuiApp.printTarget`
  * answers when the process is non-interactive, and what a test overrides it with to capture the output.
  *
  * `size` is the frame the app composes (the terminal's size has nothing to say when there is no terminal), `colors`
  * the depth `PrintFinalFrame`/`PrintEveryFrame` encode with (`Plain` ignores it), and `write` the sink — standard
  * output in production, a `StringBuilder` in a test. `write` is called on the render thread, once per frame written.
  */
final case class PrintTarget(size: Size, colors: ColorDepth, write: String => Unit)

object PrintTarget:

  /** The target a non-interactive process gets: [[Console.detectedWidth]] × [[Console.detectedHeight]], the depth
    * [[Console.detectedColors]] resolves, and standard output.
    */
  def detect(env: Map[String, String] = sys.env): PrintTarget =
    PrintTarget(
      Size(Console.detectedWidth(env), Console.detectedHeight(env)),
      Console.detectedColors(env),
      Console.write,
    )
