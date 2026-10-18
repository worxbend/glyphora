package io.worxbend.tui.terminal

import org.jline.terminal.spi.{SystemStream, TerminalProvider}

import scala.util.control.NonFatal

/** What the process's standard output is attached to — the two questions print mode and the no-TTY policies ask before
  * they decide how to write.
  *
  * Answered through JLine's JNI provider without ever *building* a terminal: `TerminalBuilder.system(true)` installs
  * signal handlers and reconfigures the TTY, which is the right thing for an app that is about to take the screen and
  * the wrong thing for a probe that only wants to know whether there is a screen. The provider's `isSystemStream` and
  * `systemStreamWidth` are the `isatty(3)` and `TIOCGWINSZ` calls, no more.
  *
  * Every answer is best-effort and conservative: a provider that cannot be loaded (an unsupported platform, a JNI
  * library that failed to load) makes [[isTerminal]] fall back to `System.console().isTerminal` and [[width]] to
  * `None`, so a caller always has a fallback to apply — print mode's is `COLUMNS`, then 80 columns. Callable from any
  * thread; each call asks the operating system afresh, because a window can be resized between two calls.
  */
object StandardOutput:

  /** Whether standard output is a terminal, as opposed to a file, a pipe or an IDE's output panel. */
  def isTerminal: Boolean =
    provider match
      case Some(jni) => jni.isSystemStream(SystemStream.Output)
      case None      => Option(System.console()).exists(_.isTerminal)

  /** Whether standard *input* is a terminal — the question an interactive prompt asks before opening one. With stdin a
    * pipe, JLine's system terminal cannot be built (it degrades to a dumb terminal, logging a warning), and it does not
    * fall back to reading `/dev/tty`; a prompt that asks this first answers over the pipe instead. Same provider and
    * fallback as [[isTerminal]].
    */
  def isInputTerminal: Boolean =
    provider match
      case Some(jni) => jni.isSystemStream(SystemStream.Input)
      case None      => Option(System.console()).exists(_.isTerminal)

  /** The terminal's width in columns, or `None` when standard output is not a terminal or the width cannot be read. */
  def width: Option[Int] =
    if !isTerminal then None
    else
      provider.flatMap { jni =>
        try
          val columns = jni.systemStreamWidth(SystemStream.Output)
          Option.when(columns > 0)(columns)
        catch case NonFatal(_) => None
      }

  /** The JNI provider, loaded on each call rather than cached: loading is a cheap class lookup once the library is in,
    * and caching a failure would make a transient one permanent for the process.
    */
  private def provider: Option[TerminalProvider] =
    try Option(TerminalProvider.load("jni"))
    catch case NonFatal(_) => None
