package io.worxbend.tui.dsl

import io.worxbend.tui.macros.{FieldInput, FieldValidation, FormSpec, deriveForm}
import io.worxbend.tui.runtime.LogRing
import io.worxbend.tui.widgets.{ProgressColumn, ProgressTask}

import java.nio.file.{Files, Path, Paths}

import scala.compiletime.summonInline
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** One-shot questions for a command-line program that is not a full-screen app: ask, get a value back, leave one line
  * behind.
  *
  * {{{
  * val name: Option[String]  = Prompt.text("Project name", validate = Prompt.required)
  * val tier: Option[Tier]    = Prompt.select("Tier", Tier.values.toSeq)
  * val ok: Option[Boolean]   = Prompt.confirm("Deploy?")
  * val cfg: Option[Config]   = Prompt.form[Config]
  * }}}
  *
  * Each call blocks the calling thread until the question is answered and returns `Some(answer)`, or `None` when the
  * user cancels — `Ctrl-C` in the terminal, the end of input when answering over a pipe. In a terminal the prompt is
  * drawn in a `Viewport.Inline` strip at the bottom of the screen, never the alternate screen, and when it is answered
  * the strip collapses to its summary line, `? Project name glyphora`, which stays in the scrollback like any other
  * command output.
  *
  * Where the answers come from is the [[PromptSession]] in scope. With none named, prompts use the controlling terminal
  * and fall back to plain line-based questions over standard input and output when there is no terminal to draw on (no
  * TTY, `TERM=dumb`, `CI` set — see [[PromptSession.terminal]]'s `fallbackWhen`). A test or an unattended run brings
  * its own: `given PromptSession = Prompt.answers(Seq("glyphora", "Pro", "y"))`.
  *
  * Threads: call from a plain thread — `main`, a test — never from a render thread. A terminal prompt starts a runner
  * of its own for the duration of the question.
  */
object Prompt:

  /** A [[PromptSession]] that answers from `answers`, in order: one per prompt, one per field of a [[form]]. Each
    * answer is parsed exactly as the typed one would be — an option by its label or 1-based number, `y`/`n`, a
    * comma-separated list for [[multiSelect]], an empty string for the default. Summaries go to `echo` (standard output
    * by default).
    */
  def answers(answers: Seq[String], echo: String => Unit = line => Console.write(line + "\n")): PromptSession =
    PromptSession.scripted(answers, echo)

  /** A `validate` for [[text]] that rejects blank input. */
  val required: String => Either[String, Unit] =
    value => if value.trim.isEmpty then Left(Messages.active.promptRequired) else Right(())

  /** Free text. An empty answer is `default`; `validate` may reject the text with a message shown under the field. */
  def text(
      label: String,
      default: String = "",
      validate: String => Either[String, Unit] = _ => Right(()),
  )(using session: PromptSession): Option[String] =
    single(session, TextStep[String](label, default, raw => validate(raw).map(_ => raw), identity))

  /** [[text]] with the input hidden as it is typed; the summary shows a fixed mask, never the length. */
  def password(label: String, validate: String => Either[String, Unit] = required)(using
      session: PromptSession
  ): Option[String] =
    single(session, TextStep[String](label, "", raw => validate(raw).map(_ => raw), identity, masked = true))

  /** Yes or no: `y` or `n` answer at once, Enter takes `default`. */
  def confirm(label: String, default: Boolean = false)(using session: PromptSession): Option[Boolean] =
    single(session, ConfirmStep(label, default))

  /** One of `options`: Up/Down (or `k`/`j`) move, Enter answers. `render` is how an option is shown and matched. */
  def select[A](label: String, options: Seq[A], render: A => String = (option: A) => option.toString)(using
      session: PromptSession
  ): Option[A] =
    single(
      session,
      ChoiceStep[A, A](
        label,
        options,
        render,
        multi = false,
        picked => picked.headOption.toRight("there is nothing to choose from"),
        render,
      ),
    )

  /** Any subset of `options`: Space ticks the one under the cursor, Enter answers with the ticked ones in list order.
    * `preselected` starts ticked; `min` is the fewest the answer may hold.
    */
  def multiSelect[A](
      label: String,
      options: Seq[A],
      render: A => String = (option: A) => option.toString,
      preselected: Seq[A] = Nil,
      min: Int = 0,
  )(using session: PromptSession): Option[Seq[A]] =
    val initial = preselected.map(options.indexOf).filter(_ >= 0).toSet
    single(
      session,
      ChoiceStep[A, Seq[A]](
        label,
        options,
        render,
        multi = true,
        picked => if picked.size < min then Left(s"choose at least $min") else Right(picked),
        picked => picked.map(render).mkString(", "),
        initial,
      ),
    )

  /** A whole number within `[min, max]`. An empty answer is `default`, when there is one. */
  def number(
      label: String,
      default: Option[Long] = None,
      min: Long = Long.MinValue,
      max: Long = Long.MaxValue,
  )(using session: PromptSession): Option[Long] =
    val parse: String => Either[String, Long] = raw =>
      raw.trim.toLongOption match
        case None                          => Left(s"'$raw' is not a whole number")
        case Some(n) if n < min || n > max => Left(s"enter a number from $min to $max")
        case Some(n)                       => Right(n)
    single(session, TextStep[Long](label, default.fold("")(_.toString), parse, _.toString))

  /** A file-system path, relative ones resolved against `base`. Tab completes against the directory being typed: one
    * match fills it in, several fill in their common prefix and are listed under the field. With `mustExist` an answer
    * naming nothing is rejected.
    */
  def path(
      label: String,
      default: String = "",
      base: Path = Paths.get("").toAbsolutePath,
      mustExist: Boolean = false,
  )(using session: PromptSession): Option[Path] =
    val parse: String => Either[String, Path] = raw =>
      if raw.trim.isEmpty then Left("a path is required")
      else
        val resolved = base.resolve(raw.trim).normalize
        if mustExist && !Files.exists(resolved) then Left(s"$raw does not exist") else Right(resolved)
    val display: Path => String               = chosen =>
      if chosen.startsWith(base) then base.relativize(chosen).toString else chosen.toString
    single(session, TextStep[Path](label, default, parse, display, complete = Some(PathCompletion.candidates(base))))

  /** A form over a case class, one field per step: the fields `deriveForm[A]` derives, asked in declaration order. A
    * Boolean field is a [[confirm]], a closed set of labels a [[select]], everything else typed text checked by the
    * field type's own parser. `Ctrl-U` goes back to the previous field with its answer kept.
    */
  inline def form[A](using session: PromptSession): Option[A] =
    formOf(deriveForm[A](using summonInline[Mirror.ProductOf[A]]))(using session)

  /** [[form]] over an explicit spec, with the spec's typed `validators` checked once every field is answered — a
    * failing one sends the user back to its field.
    */
  def formOf[A](spec: FormSpec[A], validators: FieldValidation[A]*)(using session: PromptSession): Option[A] =
    spec.checkValidators(validators)
    val steps: Vector[PromptStep[?]] = spec.defaults.toVector.map { field =>
      val name = field.spec.name
      field.spec.input match
        case FieldInput.BoolField            => ConfirmStep(name, default = false)
        case FieldInput.SelectField(options) =>
          ChoiceStep[String, String](name, options, identity, false, _.headOption.toRight("no option"), identity)
        case _                               =>
          TextStep[String](name, "", raw => field.parse(raw).map(_ => raw), identity)
    }
    var built: Option[A]             = None
    val check: PromptRun.Check       = () =>
      val values = spec.defaults.zip(steps).map((field, step) => field.parse(rawOf(step)))
      values.zipWithIndex.collectFirst { case (Left(message), index) => (index, message) }.orElse {
        val candidate = spec.assemble(values.collect { case Right(value) => value })
        val invalid   = spec.validate(candidate, validators)
        spec.fields.zipWithIndex
          .collectFirst {
            case (field, index) if invalid.contains(field.name) =>
              (index, invalid(field.name))
          }
          .orElse {
            built = Some(candidate)
            None
          }
      }
    if PromptRun.ask(session, steps, StepNavigation.CanStepBack, check) == PromptOutcome.Answered then built else None

  /** Runs `work` under a spinner labelled `label`, with the last few lines it logs scrolling underneath, and leaves one
    * summary line — `✓ Downloading` — when it ends. Answers the work's result, or `None` when the user pressed
    * `Ctrl-C`; a throwable out of `work` is rethrown after the summary (`✗ Downloading <message>`) is drawn.
    *
    * In a terminal the work runs on a thread of its own, so it may block; standard output written while it runs is
    * captured into the log rather than drawn over the strip. Cancelling interrupts that thread and does not wait for
    * it. In the plain and scripted shapes the work runs on the calling thread and each log line is printed as it comes.
    */
  def task[A](label: String)(work: TaskLog => A)(using session: PromptSession): Option[A] =
    PromptTask.run[A](
      session,
      label,
      PromptTask.LogRows,
      ring => logTail(ring),
      (log, _) => work(log),
      _ => "",
    )

  /** Runs `f` over every item under a one-row progress bar — label, bar, percentage, ETA — and answers the results in
    * order, or `None` when the user pressed `Ctrl-C`. The `tqdm` of this toolkit: wrap a loop and see it move.
    */
  def loader[A, B](label: String, items: Iterable[A])(f: A => B)(using session: PromptSession): Option[Seq[B]] =
    val all      = items.toVector
    val progress = ProgressTask(label, Some(all.size.toLong))
    PromptTask.run[Seq[B]](
      session,
      label,
      1,
      _ =>
        Element.multiProgress(Seq(progress), Seq(ProgressColumn.Bar(), ProgressColumn.Percentage, ProgressColumn.Eta)),
      (_, onUi) =>
        onUi(() => progress.start())
        all.map { item =>
          val result = f(item)
          onUi(() => progress.advance())
          result
        }
      ,
      results => s"${results.size}/${all.size}",
    )

  private def logTail(ring: LogRing): Element =
    val lines =
      ring.snapshot.takeRight(PromptTask.LogRows).map(record => Element.text("  " + record.message).dim.length(1))
    Element.column((lines :+ Element.spacer.fill)*)

  /** The raw text a form step's answer is handed to its field's parser as. */
  private def rawOf(step: PromptStep[?]): String =
    step match
      case confirm: ConfirmStep => confirm.accepted.fold("false")(_.toString)
      case other                => other.accepted.fold("")(_.toString)

  private def single[A](session: PromptSession, step: PromptStep[A]): Option[A] =
    if PromptRun.ask(session, Vector(step), StepNavigation.ForwardOnly) == PromptOutcome.Answered then step.accepted
    else None

/** Tab completion for [[Prompt.path]]: the entries of the directory being typed whose names start with what follows its
  * last separator. Directories come back with a trailing separator, so a second Tab descends into them.
  */
private[dsl] object PathCompletion:

  def candidates(base: Path)(typed: String): Seq[String] =
    val separator   = typed.lastIndexOf('/')
    val (dir, stem) =
      if separator < 0 then ("", typed)
      else (typed.take(separator + 1), typed.drop(separator + 1))
    val directory   = base.resolve(if dir.isEmpty then "." else dir).normalize
    if !Files.isDirectory(directory) then Nil
    else
      Using(Files.list(directory))(_.iterator.asScala.toVector)
        .getOrElse(Vector.empty)
        .map(entry => entry -> entry.getFileName.toString)
        .filter((_, name) => name.startsWith(stem) && (stem.startsWith(".") || !name.startsWith(".")))
        .map((entry, name) => dir + name + (if Files.isDirectory(entry) then "/" else ""))
        .sorted
