package io.worxbend.tui.dsl

import io.worxbend.tui.core.KeyEvent

/** A binding's stable name, which a user keymap rebinds it by (see [[Keymap]]).
  *
  * Unless a binding names one with `withId`, its id is its description slugged — lower case, every run of anything but
  * letters and digits turned into one `-` — so `binding("ctrl+s", "save")` is `save` and `"go to top"` is `go-to-top`.
  */
final case class BindingId(value: String):
  override def toString: String = value

object BindingId:

  /** The default id for a description: see [[BindingId]]. */
  def from(description: String): BindingId =
    BindingId(
      description
        .toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", "-")
        .stripPrefix("-")
        .stripSuffix("-")
    )

/** The name of one key mode — `normal`, `insert` — as declared by [[Mode]] and published by `AppServices.keyMode`. */
final case class ModeId(name: String):
  override def toString: String = name

object ModeId:

  /** What `AppServices.keyMode` holds in an app whose bindings declare no modes. */
  val Default: ModeId = ModeId("default")

/** A named set of bindings that are live only while `AppServices.keyMode` holds [[id]] — see `KeyBindings.modes`. */
final case class Mode(id: ModeId, bindings: Seq[KeyBinding])

object Mode:
  def apply(name: String, bindings: KeyBinding*): Mode = Mode(ModeId(name), bindings)

/** One declared application key: the key sequences that fire it, the short label shown in status-bar hints, a
  * description for the help overlay, and the action.
  *
  * `triggers` is a sequence because one command often answers to several keys — a vim-flavoured app wants `j` and
  * `down` to mean the same thing. Declaring the action twice would work for dispatch but would double it everywhere
  * else: two hints in the status bar, two rows in the help overlay, two identical entries in the command palette.
  * `label` is chosen rather than derived from `triggers`, so the app decides which of them the user is shown.
  *
  * Each trigger is a [[KeySequence]]; a single key is a one-step sequence. `explicitId` is the [[BindingId]] a keymap
  * rebinds this binding by when the default — the slugged description — will not do (see [[id]]). `mode` is the
  * [[Mode]] the binding belongs to, `None` for one that is live in every mode.
  */
final case class KeyBinding(
    triggers: Seq[KeySequence],
    label: String,
    description: String,
    action: () => Unit,
    showInHints: Boolean = true,
    explicitId: Option[BindingId] = None,
    mode: Option[ModeId] = None,
):

  /** The id a user keymap rebinds this binding by: `explicitId`, or the description slugged. */
  def id: BindingId = explicitId.getOrElse(BindingId.from(description))

  /** This binding under an id of its own choosing rather than its slugged description. */
  def withId(id: String): KeyBinding = copy(explicitId = Some(BindingId(id)))

/** Declares one binding from a key spec string: `"q"`, `"ctrl+s"`, `"shift+tab"`, `"esc"`, `"f2"`, `"up"`, `"+"`, or a
  * sequence of them — `"g g"`, `"ctrl+k ctrl+s"`, `"<leader> f"` (see [[KeySequence.parse]]). A malformed spec is a
  * programmer error and throws at construction (bindings are static app declarations).
  *
  * Each step's vocabulary is [[io.worxbend.tui.core.KeyEvent.parse]]'s — the same one `Pilot.press` takes, so a test
  * presses the string the app declared.
  */
def binding(key: String, description: String)(action: => Unit): KeyBinding =
  val sequence = KeySequence.parseOrThrow(key)
  KeyBinding(Seq(sequence), sequence.spelling, description, () => action)

/** Declares one binding that several keys fire: `binding(Seq("down", "j"), "next")(…)` answers to both and still shows
  * one `down next` hint, because the first spec is the label.
  *
  * A `Seq` rather than a `String*` variadic on purpose: `binding("down", "j")(…)` would be indistinguishable from the
  * single-key `binding(key, description)` form above, and would silently read the description as a second key.
  */
def binding(keys: Seq[String], description: String)(action: => Unit): KeyBinding =
  require(keys.nonEmpty, "a binding needs at least one key spec")
  val sequences = keys.map(KeySequence.parseOrThrow)
  KeyBinding(sequences, sequences.head.spelling, description, () => action)

/** Declares one binding from an already-parsed key, for the compiler-checked `key"ctrl+s"` spelling:
  * `binding(key"ctrl+s", "ctrl+s", "save") { … }`.
  *
  * The `label` is separate here, where the string form derives it from the spec, because a [[KeyEvent]] does not carry
  * the text it was written as — and the label is what the status bar and the help overlay show, so guessing at a
  * spelling for it would put a key on screen under a name its author did not choose.
  *
  * @param trigger
  *   the key that fires the action, usually written `key"ctrl+s"`
  * @param label
  *   how the key is spelled to the user in hints and in the help overlay
  */
def binding(trigger: KeyEvent, label: String, description: String)(action: => Unit): KeyBinding =
  KeyBinding(Seq(KeySequence.of(trigger)), label, description, () => action)

/** Declares one binding that several already-parsed keys fire — the [[KeyEvent]] counterpart of
  * `binding(Seq("down", "j"), …)`, with the same one-hint-per-command behaviour.
  */
def binding(triggers: Seq[KeyEvent], label: String, description: String)(action: => Unit): KeyBinding =
  require(triggers.nonEmpty, "a binding needs at least one key")
  KeyBinding(triggers.map(KeySequence.of(_)), label, description, () => action)

/** Declares one binding from a compiler-checked sequence: `binding(keyseq"g g", "go to top") { … }`. The label is the
  * sequence's own spelling.
  */
def binding(trigger: KeySequence, description: String)(action: => Unit): KeyBinding =
  KeyBinding(Seq(trigger), trigger.spelling, description, () => action)

/** Parses one spec or throws — bindings are static declarations, so a bad spec is a programmer error that should fail
  * at startup rather than turn into a key that never fires.
  *
  * One owner for that rule: it backs both `binding("ctrl+s", …)` here and the element-level `.onKey("ctrl+s") { … }` in
  * [[Grammar]], so a typo fails the same way wherever a key is named as a string.
  */
private[dsl] def parseSpec(key: String): KeyEvent =
  KeyEvent.parse(key) match
    case Right(trigger) => trigger
    case Left(problem)  => throw IllegalArgumentException(s"bad key spec '$key': $problem")

/** The key `<leader>` stands for when bindings are built: the space bar, unless a `given KeyLeader` says otherwise —
  * `given KeyLeader = KeyLeader(",")` inside the app object, before its `bindings`.
  */
final case class KeyLeader(key: KeyEvent)

object KeyLeader:

  /** A leader from a single key spec, which must parse. */
  def apply(spec: String): KeyLeader = KeyLeader(parseSpec(spec))

  /** The space bar, as in most modern modal editors; what `<leader>` means when no other leader is in scope. */
  given default: KeyLeader = KeyLeader(KeySequence.DefaultLeader)

/** What one key did to a [[KeyBindings]] dispatch: fired a binding, extended a pending prefix (the keys pressed so far,
  * which some binding's sequence continues), or matched nothing at all.
  */
enum KeyMatch:
  case Fired
  case Pending(prefix: Vector[KeyEvent])
  case NoMatch

/** The application's declared keys (bubbles' `key`+`help` pattern): one declaration drives dispatch, the status-bar
  * hints, and the help overlay. `TuiApp` consults these for events no element consumed.
  *
  * Bindings may be key sequences, and may belong to [[Mode]]s (see [[KeyBindings.modes]]). Construction rejects a
  * sequence that is a strict prefix of another binding live in the same mode — `"g"` next to `"g g"` — because the
  * shorter one could only ever fire by timing out the longer one, which is a guess rather than a binding.
  *
  * A value: `TuiApp` hands the chrome a copy tagged with the current mode and the pending prefix ([[mode]],
  * [[pending]]) so `statusBar(activeBindings)` can show both. Build it on any thread; `handle`/`dispatch` run the bound
  * actions on the calling thread, which for `TuiApp` is the render thread.
  */
final class KeyBindings private (
    val bindings: Seq[KeyBinding],
    val modes: Seq[ModeId],
    private val currentMode: Option[ModeId],
    val pending: Option[KeySequence],
):

  /** The mode dispatch reads: the one `TuiApp` tagged this value with when it is one of [[modes]], else the first
    * declared mode; `None` when no modes are declared.
    */
  def mode: Option[ModeId] = currentMode.filter(modes.contains).orElse(modes.headOption)

  /** The bindings live in [[mode]]: every mode-less binding plus the current mode's own. */
  def active: Seq[KeyBinding] = bindings.filter(bound => bound.mode.isEmpty || bound.mode == mode)

  /** Runs the first live binding one of whose single-key triggers is `event`; `true` if one fired. A key that only
    * starts a sequence does not fire here — sequences need the pending state `TuiApp` keeps (see [[dispatch]]).
    */
  def handle(event: KeyEvent): Boolean = dispatch(Vector.empty, event) == KeyMatch.Fired

  /** One step of sequence dispatch: `prefix` is what has been pressed so far, `event` the key just pressed.
    *
    * A sequence matched exactly fires (the first live binding in declaration order); otherwise, when some live sequence
    * continues past `prefix :+ event`, the answer is the longer prefix to wait on; otherwise nothing matched.
    */
  def dispatch(prefix: Vector[KeyEvent], event: KeyEvent): KeyMatch =
    val typed = prefix :+ event
    val live  = active
    live.find(_.triggers.exists(_.keys == typed)) match
      case Some(bound) =>
        bound.action()
        KeyMatch.Fired
      case None        =>
        if live.exists(_.triggers.exists(_.keys.startsWith(typed))) then KeyMatch.Pending(typed)
        else KeyMatch.NoMatch

  /** What may follow `prefix`: for each live binding a sequence of which continues it, the rest of that sequence
    * spelled out and the binding's description — the rows of the which-key popup, in declaration order.
    */
  def continuations(prefix: Vector[KeyEvent]): Seq[KeyHint] =
    for
      bound   <- active
      trigger <- bound.triggers.find(t => t.keys.sizeIs > prefix.size && t.keys.startsWith(prefix))
    yield KeyHint(trigger.keys.drop(prefix.size).map(KeySequence.show).mkString(" "), bound.description)

  /** `(label, description)` pairs for the status bar, in declaration order — one per live binding, whatever its trigger
    * count, so a command with several keys is advertised under the one its author chose.
    */
  def hints: Seq[(String, String)] =
    active.filter(_.showInHints).map(bound => (bound.label, bound.description))

  /** Every hinted binding grouped for the help overlay: the mode-less ones first under no heading, then each mode's
    * under its id. Without modes this is one untitled section holding exactly [[hints]].
    */
  def helpSections: Seq[HelpSection] =
    def rows(of: Seq[KeyBinding]) = of.filter(_.showInHints).map(bound => KeyHint(bound.label, bound.description))
    val global                    = rows(bindings.filter(_.mode.isEmpty))
    val modal                     = modes.map(id => HelpSection(Some(id), rows(bindings.filter(_.mode.contains(id)))))
    (if global.nonEmpty || modes.isEmpty then Seq(HelpSection(None, global)) else Seq.empty) ++ modal

  /** These bindings followed by `other`'s, checked for prefix collisions across their combined modes. */
  def ++(other: KeyBindings): KeyBindings = KeyBindings.checked(concat(other))

  /** Screen-over-app composition deliberately permits a screen's single key to shadow an app sequence. */
  private[dsl] def concat(other: KeyBindings): KeyBindings =
    new KeyBindings(
      bindings ++ other.bindings,
      (modes ++ other.modes).distinct,
      currentMode.orElse(other.currentMode),
      pending.orElse(other.pending),
    )

  /** Replaces the triggers of every binding whose [[KeyBinding.id]] is in `keymap` with that one sequence, which also
    * becomes its label, so hints and the help overlay show the user's key. Ids nothing declares are ignored; the result
    * is re-checked for collisions. A `<leader>` in a new sequence is resolved against the [[KeyLeader]] in scope.
    */
  def rebind(keymap: Map[BindingId, KeySequence])(using leader: KeyLeader): KeyBindings =
    rebindEither(keymap).fold(error => throw IllegalArgumentException(error.message), identity)

  /** Rebinds user-supplied keys without throwing for a prefix collision. */
  def rebindEither(keymap: Map[BindingId, KeySequence])(using leader: KeyLeader): Either[RebindError, KeyBindings] =
    val rebound = bindings.map(bound =>
      keymap
        .get(bound.id)
        .fold(bound)(sequence => bound.copy(triggers = Seq(sequence.withLeader(leader.key)), label = sequence.spelling))
    )
    KeyBindings.validate(new KeyBindings(rebound, modes, currentMode, pending))

  /** The bindings with `keep` true, everything else about this value unchanged. */
  private[dsl] def filter(keep: KeyBinding => Boolean): KeyBindings =
    new KeyBindings(bindings.filter(keep), modes, currentMode, pending)

  /** This value as `TuiApp` hands it to the chrome: tagged with the mode it is in and the prefix pressed so far. */
  private[dsl] def snapshot(mode: ModeId, pending: Option[KeySequence]): KeyBindings =
    new KeyBindings(bindings, modes, Some(mode), pending)

object KeyBindings:

  val empty: KeyBindings = new KeyBindings(Seq.empty, Seq.empty, None, None)

  /** Mode-less bindings, live whatever mode the app is in. Throws on a prefix collision (see [[KeyBindings]]).
    *
    * Every `<leader>` step is resolved here, against the [[KeyLeader]] in scope — the space bar unless the caller has a
    * `given KeyLeader` of its own — so a collision the leader creates is caught at construction too.
    */
  def apply(declared: KeyBinding*)(using leader: KeyLeader): KeyBindings =
    checked(new KeyBindings(declared.map(resolved(leader)), Seq.empty, None, None))

  /** Bindings grouped into modes — `KeyBindings.modes(Mode("normal", …), Mode("insert", …))`. The first mode is the one
    * an app starts in; a binding is live only while `AppServices.keyMode` holds its mode. Combine with mode-less
    * bindings through `++`.
    */
  def modes(declared: Mode*)(using leader: KeyLeader): KeyBindings =
    require(declared.map(_.id).distinct.sizeIs == declared.size, "two modes share one name")
    val tagged =
      declared.flatMap(mode => mode.bindings.map(bound => resolved(leader)(bound.copy(mode = Some(mode.id)))))
    checked(new KeyBindings(tagged, declared.map(_.id), None, None))

  private def resolved(leader: KeyLeader)(bound: KeyBinding): KeyBinding =
    bound.copy(triggers = bound.triggers.map(_.withLeader(leader.key)))

  /** `bindings`, or an `IllegalArgumentException` naming the first two bindings live in the same mode one of whose
    * sequences is a strict prefix of the other's.
    */
  private def checked(bindings: KeyBindings): KeyBindings =
    validate(bindings).fold(error => throw IllegalArgumentException(error.message), identity)

  private def validate(bindings: KeyBindings): Either[RebindError, KeyBindings] =
    val scopes   = if bindings.modes.isEmpty then Seq(None) else bindings.modes.map(Some(_))
    val problems = for
      scope <- scopes.iterator
      live = bindings.bindings.filter(bound => bound.mode.isEmpty || bound.mode == scope)
      short    <- live.iterator
      shortSeq <- short.triggers.iterator
      long     <- live.iterator
      longSeq  <- long.triggers.iterator
      a = shortSeq.keys
      b = longSeq.keys
      if a.nonEmpty && b.sizeIs > a.size && b.startsWith(a)
    yield
      val where = scope.fold("")(mode => s" in mode '$mode'")
      RebindError(
        Seq(short.id, long.id),
        s"key binding collision$where: \"${shortSeq.spelling}\" (${short.description}) is a prefix of " +
          s"\"${longSeq.spelling}\" (${long.description}); a key that starts a sequence cannot also be a binding",
      )
    val reserved = bindings.bindings.find(_.id.value.startsWith("framework.")).map { bound =>
      RebindError(Seq(bound.id), s"application binding '${bound.id}' uses the reserved framework. id namespace")
    }
    reserved.orElse(problems.nextOption()).toLeft(bindings)

/** A recoverable keymap collision or unsupported framework sequence, naming the affected binding ids. */
final case class RebindError(ids: Seq[BindingId], message: String):
  override def toString: String = message

/** One row of key help: the keys as the user types them — `"ctrl+s"`, or the rest of a sequence after a typed prefix —
  * and what they do. Rows of the which-key popup and the help overlay.
  */
final case class KeyHint(keys: String, description: String)

/** One block of the help overlay: the hints of the bindings that belong to `mode`, or of the mode-less ones when `mode`
  * is `None`. See [[KeyBindings.helpSections]].
  */
final case class HelpSection(mode: Option[ModeId], hints: Seq[KeyHint])
