// A whole glyphora app in one file, no build tool: `scala run hello.scala` (scala-cli, which the `scala` command is
// since Scala 3.5). Needs JDK 25 or newer — the published artifacts are built with `-release:25`.
// Pinned to 0.19.0, the newest release on Maven Central when this file was written; it moves to 0.20.0 once that
// release is published.
//> using scala 3.9.0
//> using jvm 25
//> using dep com.worxbend::tui-dsl:0.19.0
//> using javaOpt --enable-native-access=ALL-UNNAMED

import io.worxbend.tui.dsl.*

/** A counter in a bordered panel: `+` and `-` change it, `q` quits. */
object Hello extends TuiApp:

  private val count = Signal(0)

  def view(using ReactiveScope, Theme): Element =
    panel("Hello, glyphora")(
      text(s"count: ${count.get}").bold.fg(Color.Cyan),
      spacer,
      text("+ / - to change it, q to quit").dim,
    ).rounded.onKeyEvent {
      case KeyEvent(KeyCode.Char('+'), _) =>
        count.update(_ + 1)
        true
      case KeyEvent(KeyCode.Char('-'), _) =>
        count.update(_ - 1)
        true
      case KeyEvent(KeyCode.Char('q'), _) =>
        quit()
        true
      case _                              => false
    }
