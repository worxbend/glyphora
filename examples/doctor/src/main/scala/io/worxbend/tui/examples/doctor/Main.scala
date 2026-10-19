package io.worxbend.tui.examples.doctor

import io.worxbend.tui.dsl.*

/** `doctor`: what glyphora detected about this terminal, then every decoded key, mouse event and paste echoed live —
  * the first thing to run when "my keys don't work".
  *
  * The top half is the report: the terminal's own name (XTVERSION), `TERM`, the colour depth and glyph tier the
  * environment resolves to, the background colour and light/dark scheme the terminal answered, every probed capability,
  * and the `GLYPHORA_*` overrides currently set. The bottom half is the event log, newest first. `q` quits; everything
  * else is echoed, including keys the app would never see under another program.
  *
  * `env` is the environment the report reads — `sys.env` for the real program, a fixed map in the test.
  */
class DoctorApp(env: Map[String, String] = sys.env) extends TuiApp:

  /** The backend this run draws on, attached by [[createBackend]] (or by a test before `runWith`). Written before the
    * run starts, read on the render thread.
    */
  @volatile private var attached: Option[Backend] = None

  /** Remembers `backend` as the one whose capabilities the report shows, and hands it back. */
  def attach(backend: Backend): Backend =
    attached = Some(backend)
    backend

  override protected def createBackend(): Either[BackendError, Backend] = super.createBackend().map(attach)

  // clicks and drags are what "the mouse does nothing" is about; all-motion tracking would bury the log in moves
  override def config: RunnerConfig = RunnerConfig().withMouseCapture

  private val events: Signal[Vector[String]] = Signal(Vector.empty)

  private def log(line: String): Unit = events.set((line +: events.peek).take(MaxEvents))

  override def onResize(size: Size): Unit = log(s"resize ${size.width}x${size.height}")

  override def onTerminalFocus(focused: Boolean): Unit = log(if focused then "focus  gained" else "focus  lost")

  def view(using ReactiveScope, Theme): Element =
    column(
      text("glyphora doctor").bold,
      text("q quits · every other key, click and paste is echoed below").dim,
      spacer.length(1),
      section("terminal", terminalRows),
      section("capabilities", capabilityRows),
      section("overrides", overrideRows),
      text("── events (newest first) ──").dim,
      column(events.get.map(text)*),
    ).focusable
      .onKeyEvent {
        case KeyEvent(KeyCode.Char('q'), modifiers) if modifiers.isEmpty =>
          quit()
          true
        case key                                                         =>
          log(s"key    ${KeySequence.show(key)}   $key")
          true
      }
      .onMouseEvent { mouse =>
        log(s"mouse  ${mouse.kind} ${mouse.button} at ${mouse.position.x},${mouse.position.y}  x${mouse.clickCount}")
        true
      }
      .onPaste { pasted =>
        log(s"paste  \"$pasted\"")
        true
      }

  private def section(title: String, rows: Seq[(String, String)]): Element =
    column(
      (text(s"── $title ──").dim +: rows.map((name, value) => text(f"$name%-24s $value")))*
    ).auto

  private def terminalRows: Seq[(String, String)] =
    val capabilities    = attached.map(_.capabilities)
    Seq(
      "XTVERSION"    -> capabilities.flatMap(_.terminalVersion).getOrElse("no answer"),
      "TERM"         -> env.getOrElse("TERM", "unset"),
      "TERM_PROGRAM" -> env.getOrElse("TERM_PROGRAM", "unset"),
      "colour depth" -> ColorDepth.detect(env).toString,
      "glyph tier"   -> TerminalGlyphs.detect(env).toString,
      "background"   -> capabilities.flatMap(_.backgroundColor).fold("no answer")(hex),
      "scheme"       -> capabilities.fold("unknown")(_.colorScheme.toString),
    )

  private def capabilityRows: Seq[(String, String)] =
    attached.map(_.capabilities).fold(Seq("probe" -> "no terminal attached")) { found =>
      Seq(
        "synchronized output"   -> found.synchronizedOutput.toString,
        "kitty keyboard"        -> found.kittyKeyboard.toString,
        "bracketed paste"       -> found.bracketedPaste.toString,
        "focus reporting"       -> found.focusReporting.toString,
        "colour-scheme updates" -> found.colorSchemeUpdates.toString,
        "in-band resize"        -> found.inBandResize.toString,
        "unicode width"         -> found.unicodeWidth.toString,
        "pointer shapes"        -> found.pointerShapes.toString,
        "desktop notifications" -> found.desktopNotifications.toString,
        "taskbar progress"      -> found.taskbarProgress.toString,
      )
    }

  private def overrideRows: Seq[(String, String)] =
    Overrides.map(name => name -> env.get(name).filter(_.nonEmpty).getOrElse("unset"))

  private def hex(color: Color): String =
    color match
      case Color.Rgb(r, g, b) => f"#$r%02x$g%02x$b%02x"
      case other              => other.toString

  private val MaxEvents = 12

  private val Overrides: Seq[String] = Seq(
    "GLYPHORA_COLOR_DEPTH",
    "GLYPHORA_KEYBOARD",
    "GLYPHORA_MOUSE",
    "GLYPHORA_ESCAPE_DELAY",
    "GLYPHORA_ASCII",
    "GLYPHORA_NO_CAPABILITY_PROBE",
    "GLYPHORA_DEBUG_LAYOUT",
    "NO_COLOR",
  )

/** `TuiApp` supplies `main`: with no terminal it exits 1 printing `terminal not supported`, like every example. */
object Main extends DoctorApp()
