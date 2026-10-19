package io.worxbend.tui.dsl

import io.worxbend.tui.terminal.{Backend, BackendError, JLine3Backend, StandardOutput}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable.ArrayBuffer

/** Where the [[Prompt]] calls made under it read their answers from and leave their summaries.
  *
  * Three shapes, picked by the factory that built it:
  *
  *   - [[PromptSession.terminal]] (the `given` every prompt finds when the caller names none): each prompt is a
  *     one-shot app in a `Viewport.Inline` strip at the bottom of the terminal, which it collapses to its one-line
  *     summary when it is answered. When the process has no terminal to be interactive on — `fallbackWhen` says so, or
  *     the backend cannot be opened (`TERM=dumb`, no TTY), or the backend fails while the prompt runs — it degrades to
  *     the plain shape over standard input and output instead of failing.
  *   - [[PromptSession.plain]]: line-based question and answer over a reader and a writer, with no cursor movement and
  *     no escape sequences. What a pipe, a CI log or a screen reader wants.
  *   - [[PromptSession.scripted]] (also spelled [[Prompt.answers]]): every answer is pre-supplied, in order, and parsed
  *     exactly as a typed one would be. What a test and an unattended CI run want. An answer the prompt rejects, or a
  *     prompt left without an answer, throws `IllegalArgumentException` — a script that no longer matches its program
  *     is a bug to surface, not a question to hang on.
  *
  * Every session keeps the summary line of each prompt answered under it in [[transcript]], in order — `"? Name alpha"`
  * — which is how a test asserts on what the user would have seen left in their scrollback.
  *
  * Threads: a session is used by one prompt at a time, from the thread that calls the prompt (never a render thread: a
  * terminal prompt starts a runner of its own and blocks until it is answered). The transcript is synchronized, so it
  * can be read from anywhere.
  */
final class PromptSession private (private[dsl] val mode: PromptSession.Mode, echo: String => Unit):

  private val lines = ArrayBuffer.empty[String]

  /** The summary lines of every prompt answered under this session, oldest first. */
  def transcript: Seq[String] = lines.synchronized(lines.toVector)

  /** Appends `summary` to the transcript and hands it to the session's echo — which only the scripted shape has: the
    * user typed nothing there, so this line is the only record of what was answered. The other shapes echo nothing
    * here, because the line is already on screen.
    */
  private[dsl] def record(summary: String): Unit =
    lines.synchronized { val _ = lines += summary }
    echo(summary)

  /** The shape the next prompt runs in, with every fallback already applied: a terminal session whose `fallbackWhen`
    * holds, or whose backend does not open, resolves to its plain fallback. The one place the three shapes are told
    * apart, so no prompt re-implements the fallback rule.
    */
  private[dsl] def resolve(): PromptSession.Shape =
    mode match
      case PromptSession.Mode.Scripted(answers)                          => PromptSession.Shape.Scripted(answers)
      case plain: PromptSession.Mode.Plain                               => PromptSession.Shape.Plain(plain)
      case PromptSession.Mode.Terminal(open, fallbackWhen, theme, plain) =>
        if fallbackWhen() then PromptSession.Shape.Plain(plain())
        else
          open() match
            case Left(_)        => PromptSession.Shape.Plain(plain())
            case Right(backend) => PromptSession.Shape.Terminal(backend, theme, plain)

object PromptSession:

  /** How a session asks. Internal: the factories below are the way to build one. */
  private[dsl] enum Mode:
    case Terminal(
        open: () => Either[BackendError, Backend],
        fallbackWhen: () => Boolean,
        theme: Theme,
        plain: () => Plain,
    )
    case Plain(read: () => Option[String], write: String => Unit, echoInput: Boolean)
    case Scripted(answers: Iterator[String])

  /** A resolved [[Mode]]: what one prompt actually runs in. A terminal shape keeps its plain fallback, for a backend
    * that fails once the prompt is already running.
    */
  private[dsl] enum Shape:
    case Terminal(backend: Backend, theme: Theme, fallback: () => Mode.Plain)
    case Plain(plain: Mode.Plain)
    case Scripted(answers: Iterator[String])

  /** The session every prompt uses when the caller provides none: the controlling terminal, falling back to plain
    * standard input and output when there is no terminal to draw on.
    */
  given default: PromptSession = terminal()

  /** Prompts drawn in an inline strip of the terminal `open` returns — the controlling terminal through JLine by
    * default.
    *
    * @param fallbackWhen
    *   asked before every prompt; `true` answers it in the plain shape over standard input and output instead. The
    *   default is [[Console.isNonInteractive]] (standard output is not a terminal, or `CI` is set) or standard input
    *   not being a terminal — piped answers are read from the pipe, never from `/dev/tty`. A backend that fails to open
    *   falls back the same way, whatever this says.
    * @param theme
    *   the theme the prompts draw with
    * @param open
    *   opens the backend each prompt runs on; the prompt's runner closes it when the prompt is answered
    */
  def terminal(
      fallbackWhen: => Boolean = Console.isNonInteractive() || !StandardOutput.isInputTerminal,
      theme: Theme = Theme.auto,
      open: () => Either[BackendError, Backend] = () => JLine3Backend.create(),
  ): PromptSession =
    new PromptSession(Mode.Terminal(open, () => fallbackWhen, theme, () => standardPlain), _ => ())

  /** Prompts drawn on one already-open `backend` — a `HeadlessBackend` under `Pilot`, an embedding host's own. Each
    * prompt's runner closes the backend when it ends, so a backend serves one prompt (or one `Prompt.form`).
    */
  def on(backend: Backend, theme: Theme = Theme.Dark): PromptSession =
    new PromptSession(Mode.Terminal(() => Right(backend), () => false, theme, () => standardPlain), _ => ())

  /** A terminal session over `open` whose plain fallback is `plain` rather than standard input and output — the seam a
    * test uses to see what a failing terminal falls back to.
    */
  private[dsl] def terminalOver(
      open: () => Either[BackendError, Backend],
      plain: Mode.Plain,
      theme: Theme,
  ): PromptSession =
    new PromptSession(Mode.Terminal(open, () => false, theme, () => plain), _ => ())

  /** Line-based prompts: each question is written with `write` (no trailing newline) and its answer read with `read`,
    * whose `None` means the input ended — read as a cancellation, the way `Ctrl-C` is in the terminal shape. With
    * `echoInput` each answer read is written back followed by a newline, which is what a reader that is not a terminal
    * (and so echoes nothing) needs for the transcript on screen to read as question-answer pairs.
    */
  def plain(read: () => Option[String], write: String => Unit, echoInput: Boolean = false): PromptSession =
    new PromptSession(Mode.Plain(read, write, echoInput), _ => ())

  /** Pre-supplied answers, consumed in order — one per prompt, one per field of a `Prompt.form`. Each summary line is
    * passed to `echo` as well as kept in the transcript.
    */
  def scripted(answers: Seq[String], echo: String => Unit = line => Console.write(line + "\n")): PromptSession =
    new PromptSession(Mode.Scripted(answers.iterator), echo)

  /** Plain prompts over the process's standard input and output — the fallback of [[terminal]]. */
  private[dsl] lazy val standardPlain: Mode.Plain =
    // one reader for the process: a reader per prompt would buffer ahead and swallow the next prompt's piped answer
    val reader = BufferedReader(InputStreamReader(System.in, UTF_8))
    Mode.Plain(() => Option(reader.readLine()), Console.write, echoInput = !StandardOutput.isInputTerminal)
