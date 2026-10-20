package io.worxbend.tui.dsl

import java.nio.file.{Files, Path}
import java.util.logging.{Level, Logger}
import scala.util.control.NonFatal

/** Runs the user's editor on a file with the terminal handed back — the mechanics behind [[AppServices.editExternally]]
  * and [[AppServices.openInEditor]].
  *
  * The editor is whatever `VISUAL`, then `EDITOR`, names, split with single/double quotes and backslash escapes
  * (without shell evaluation), and `vi` when neither is set. It inherits the process's own standard streams rather than
  * whatever `System.out` currently is, which is what lets it drive the terminal while the app is suspended and the
  * captured streams are passing through. A launch that fails — no such editor, no permission — is logged at `WARNING`
  * on the `io.worxbend.tui.dsl` logger and reported as "nothing edited": a mistyped `EDITOR` is a configuration slip,
  * not a reason to end the app from inside a key handler.
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
      .map(splitCommand)
      .getOrElse(Seq("vi"))

  /** Quote/escape splitting only: shell operators, substitutions and variables remain literal argv text. */
  private def splitCommand(command: String): Seq[String] =
    val words               = scala.collection.mutable.ArrayBuffer.empty[String]
    val word                = new java.lang.StringBuilder
    var quote: Option[Char] = None
    var escaped             = false
    var started             = false
    command.foreach { char =>
      if escaped then
        if quote.contains('"') && char != '"' && char != '\\' && char != '$' && char != '`' then word.append('\\')
        word.append(char)
        escaped = false
        started = true
      else if char == '\\' && !quote.contains('\'') then
        escaped = true
        started = true
      else if quote.contains(char) then quote = None
      else if quote.isEmpty && (char == '\'' || char == '"') then
        quote = Some(char)
        started = true
      else if quote.isEmpty && char.isWhitespace then
        if started then
          words += word.toString
          word.setLength(0)
          started = false
      else
        word.append(char)
        started = true
    }
    if escaped then word.append('\\')
    if started then words += word.toString
    // Invalid quoting must fail as a launch/configuration slip, never run a different executable by accident.
    if quote.isDefined then Seq(command) else words.toSeq

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
      // A backend may translate escaping body exceptions into fatal terminal I/O failures. Isolate the editor here,
      // while allowing actual release/reacquisition failures from `suspend` to retain their backend semantics.
      try
        val process = new ProcessBuilder(argv*).inheritIO().start()
        val _       = process.waitFor()
      catch case NonFatal(error) => log.log(Level.WARNING, "could not launch external editor", error)
    }
