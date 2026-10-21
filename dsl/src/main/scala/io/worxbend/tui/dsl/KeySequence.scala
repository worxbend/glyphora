package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, KeyModifiers}

import scala.quoted.{Expr, Quotes, Varargs}

/** One step of a [[KeySequence]]: a concrete key, or the placeholder `<leader>` that the owning [[KeyBindings]]
  * resolves to its configured leader key (see `KeyBindings.withLeader`).
  */
enum KeyStep:
  case Key(event: KeyEvent)
  case Leader

/** The keys a binding answers to, pressed one after another: `"g g"`, `"ctrl+k ctrl+s"`, `"<leader> f"`. A single-key
  * spec is a one-step sequence, so every binding is a sequence and the single-key case needs no separate path.
  *
  * Equality is over [[steps]] only. [[spelling]] is how the sequence was written — kept because it is what hints, the
  * help overlay and the keymap export show the user, and a [[KeyEvent]] does not remember its own text — so `"esc"` and
  * `"escape"` are the same sequence spelled two ways.
  *
  * An immutable value, safe to share between threads.
  */
final case class KeySequence(steps: Vector[KeyStep])(val spelling: String):

  /** The concrete keys, with every `<leader>` step replaced by `leader`. */
  def resolve(leader: KeyEvent): Vector[KeyEvent] =
    steps.map:
      case KeyStep.Key(event) => event
      case KeyStep.Leader     => leader

  /** The steps as concrete keys, for a sequence with no `<leader>` left in it — every `<leader>` reads as the
    * [[KeySequence.DefaultLeader]] here; bindings resolve theirs through [[withLeader]] when they are built.
    */
  def keys: Vector[KeyEvent] = resolve(KeySequence.DefaultLeader)

  /** This sequence with every `<leader>` step replaced by `leader`, still spelled as written. */
  def withLeader(leader: KeyEvent): KeySequence = KeySequence(resolve(leader).map(KeyStep.Key(_)))(spelling)

  override def toString: String = spelling

object KeySequence:

  /** The leader `<leader>` stands for until `KeyBindings.withLeader` names another: the space bar, as in most modern
    * modal editors.
    */
  val DefaultLeader: KeyEvent = KeyEvent.char(' ')

  private val LeaderToken = "<leader>"

  /** Parses a sequence spec: whitespace-separated steps, each one a
    * [[io.worxbend.tui.core.KeyEvent.parse KeyEvent.parse]] spec or `<leader>`.
    *
    * A spec with no inner whitespace is handed to `KeyEvent.parse` whole, so its padding rules hold unchanged — `" "`
    * is still the space bar and `"ctrl+ "` still Ctrl+Space. Inside a multi-step sequence the space bar is spelled
    * `space`.
    */
  def parse(spec: String): Either[String, KeySequence] =
    val tokens = spec.trim.split("\\s+").toVector.filter(_.nonEmpty)
    if spec.trim == "none" then Right(unbound)
    else if tokens.sizeIs <= 1 then
      if tokens.headOption.contains(LeaderToken) then Right(KeySequence(Vector(KeyStep.Leader))(LeaderToken))
      else KeyEvent.parse(spec).map(event => KeySequence(Vector(KeyStep.Key(event)))(spec.trim.nonEmptyOr(spec)))
    else
      val steps = tokens.map: token =>
        if token == LeaderToken then Right(KeyStep.Leader)
        else KeyEvent.parse(token).map(KeyStep.Key(_)).left.map(problem => s"step '$token': $problem")
      steps.collectFirst { case Left(problem) => problem } match
        case Some(problem) => Left(problem)
        case None          => Right(KeySequence(steps.collect { case Right(step) => step })(tokens.mkString(" ")))

  /** [[parse]], throwing `IllegalArgumentException` on a bad spec — for static declarations, where a typo is a
    * programmer error that should fail at startup.
    */
  def parseOrThrow(spec: String): KeySequence =
    parse(spec) match
      case Right(sequence) => sequence
      case Left(problem)   => throw IllegalArgumentException(s"bad key spec '$spec': $problem")

  /** The sequence of no keys: bound to it, a framework key never fires (see `FrameworkKeys.unbind`). Spelled `none`. */
  val unbound: KeySequence = KeySequence(Vector.empty)("none")

  /** A sequence of already-parsed keys, spelled with [[show]]. */
  def of(events: KeyEvent*): KeySequence =
    KeySequence(events.toVector.map(KeyStep.Key(_)))(events.map(show).mkString(" "))

  /** Spells one key the way a spec would name it — `ctrl+s`, `space`, `f5`, `pageup` — such that `KeyEvent.parse` reads
    * it back as the same key.
    */
  def show(event: KeyEvent): String =
    val modifiers =
      Seq(KeyModifiers.Ctrl -> "ctrl+", KeyModifiers.Alt -> "alt+", KeyModifiers.Shift -> "shift+").collect {
        case (flag, text) if event.modifiers.hasAny(flag) => text
      }.mkString
    modifiers + showCode(event.code)

  private def showCode(code: KeyCode): String =
    code match
      case KeyCode.Char(' ')       => "space"
      case KeyCode.Char(codePoint) => Character.toString(codePoint)
      case KeyCode.F(n)            => s"f$n"
      case other                   => CodeNames.getOrElse(other, other.toString.toLowerCase(java.util.Locale.ROOT))

  /** The preferred spelling of every named key, built by parsing the spellings rather than restating the table
    * `KeyEvent.parse` reads, so the two cannot drift apart.
    */
  private val CodeNames: Map[KeyCode, String] =
    Seq(
      "enter",
      "esc",
      "tab",
      "backspace",
      "delete",
      "insert",
      "home",
      "end",
      "pageup",
      "pagedown",
      "up",
      "down",
      "left",
      "right",
      "capslock",
      "scrolllock",
      "numlock",
      "printscreen",
      "pause",
      "menu",
      "play",
      "mediapause",
      "playpause",
      "reverse",
      "stop",
      "fastforward",
      "rewind",
      "next",
      "prev",
      "record",
      "volumedown",
      "volumeup",
      "mute",
    ).flatMap(name => KeyEvent.parse(name).toOption.map(_.code -> name)).toMap

  extension (text: String) private def nonEmptyOr(fallback: String): String = if text.isEmpty then fallback else text

/** A key sequence checked when the code is compiled: `keyseq"g g"`, `keyseq"ctrl+k ctrl+s"`, `keyseq"<leader> f"`.
  *
  * The `key"…"` interpolator's counterpart for sequences. A spec [[KeySequence.parse]] rejects stops the build with the
  * parser's message; a spec with a `$` hole cannot be checked and is a compile error too — use `KeySequence.parse` for
  * a spec read at run time. The checked spec is parsed once more when the expression first runs, which cannot fail.
  */
extension (inline sc: StringContext)
  inline def keyseq(inline args: Any*): KeySequence = ${ KeySequenceLiteral.expand('sc, 'args) }

/** The implementation behind `keyseq"…"`. Public only because an inline method's body is expanded in the calling
  * module; it is not an API.
  */
object KeySequenceLiteral:

  def expand(sc: Expr[StringContext], args: Expr[Seq[Any]])(using q: Quotes): Expr[KeySequence] =
    import q.reflect.report
    (sc.value, args) match
      case (Some(context), Varargs(Seq())) =>
        val spec = context.parts.mkString
        KeySequence.parse(spec) match
          case Right(_)      => '{ KeySequence.parseOrThrow(${ Expr(spec) }) }
          case Left(problem) => report.errorAndAbort(s"bad key sequence \"$spec\": $problem", sc)
      case (Some(_), _)                    =>
        report.errorAndAbort("a key sequence cannot interpolate a value; use KeySequence.parse at run time", sc)
      case (None, _)                       =>
        report.errorAndAbort("a key sequence must be a string literal", sc)
