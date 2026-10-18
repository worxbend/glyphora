package io.worxbend.tui.dsl

import java.nio.file.{Files, Path}
import java.util.logging.{Level, Logger}
import scala.util.control.NonFatal

/** Runs the user's editor on a file with the terminal handed back — the mechanics behind [[AppServices.editExternally]]
  * and [[AppServices.openInEditor]].
  *
  * The editor is whatever `VISUAL`, then `EDITOR`, names, split on blanks so `code --wait` works, and `vi` when neither
  * is set. It inherits the process's own standard streams rather than whatever `System.out` currently is, which is what
  * lets it drive the terminal while the app is suspended and the captured streams are passing through. A launch that
  * fails — no such editor, no permission — is logged at `WARNING` on the `io.worxbend.tui.dsl` logger and reported as
  * "nothing edited": a mistyped `EDITOR` is a configuration slip, not a reason to end the app from inside a key
  * handler.
  *
  * `suspend` is the app's own, so everything here runs on the render thread inside an event handler, like the handle
  * method it wraps.
  */
private[dsl] object ExternalEditor:

  private val log = Logger.getLogger("io.worxbend.tui.dsl")

  /** The editor command, with its arguments, from the environment: `VISUAL`, then `EDITOR`, then `vi`. */
  def command(environment: Map[String, String]): Seq[String] =
    Seq("VISUAL", "EDITOR").iterator
      .flatMap(environment.get)
      .map(_.trim)
      .filter(_.nonEmpty)
      .nextOption()
      .map(_.split("\\s+").toSeq)
      .getOrElse(Seq("vi"))

  /** Writes `initial` to a temporary file with `suffix`, runs the editor on it, reads it back and deletes it. `Some` of
    * the new content when the editor changed it, `None` when it did not or could not run.
    */
  def edit(command: Seq[String], initial: String, suffix: String, suspend: (=> Unit) => Unit): Option[String] =
    val file = Files.createTempFile("glyphora-edit-", suffix)
    try
      Files.writeString(file, initial)
      launch(command :+ file.toString, suspend)
      val edited = Files.readString(file)
      Option.when(edited != initial)(edited)
    catch
      case NonFatal(error) =>
        log.log(Level.WARNING, s"could not edit externally with ${command.mkString(" ")}", error)
        None
    finally
      val _ = Files.deleteIfExists(file)

  /** Opens `path` in the editor, at `line` when given (`+N`, which vi, vim, nano and emacs all accept). */
  def open(command: Seq[String], path: Path, line: Option[Int], suspend: (=> Unit) => Unit): Unit =
    try launch(command ++ line.map(n => s"+$n") :+ path.toString, suspend)
    catch case NonFatal(error) => log.log(Level.WARNING, s"could not open $path with ${command.mkString(" ")}", error)

  private def launch(argv: Seq[String], suspend: (=> Unit) => Unit): Unit =
    suspend {
      val process = new ProcessBuilder(argv*).inheritIO().start()
      val _       = process.waitFor()
    }
