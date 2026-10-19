package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Position, Rect, Style}

import scala.collection.mutable

/** One input identity: focus indices and pointer ids may have the same number, but never identify the same node. */
private[dsl] enum InputTarget:
  case Focus(index: Int)
  case Pointer(id: Int)

  def focusIndex: Option[Int] = this match
    case Focus(index) => Some(index)
    case Pointer(_)   => None

/** The last-painted input target under the pointer. `layoutArea` is translated but not clipped: handlers need the
  * control's original origin and size. Eligibility was already checked against its clipped hit area.
  */
private[dsl] final case class MouseHit(target: InputTarget, layoutArea: Rect)

/** Where an input target rendered this frame, and *when* in the frame's paint order it rendered.
  *
  * `sequence` is a counter that [[NodeBounds.recordTarget]] bumps on every recorded area and
  * [[NodeBounds.clearTargets]] resets at the start of each frame. A container paints itself before its children and
  * paints its children in order, so a larger `sequence` means "painted later", and on a terminal — where every cell
  * holds exactly one character — painted later means painted *over*. That makes the greatest sequence covering a cell
  * the thing the user actually sees there, which is the rule [[FocusTracker.hitTest]] resolves a click by.
  */
private[dsl] final case class PaintedArea(
    layoutArea: Rect,
    hitArea: Rect,
    sequence: Int,
    passThrough: Boolean = false,
    key: Option[String] = None,
)

/** An element in the tree about to render asking to be given focus: where it sits in the tab order, and what identifies
  * it across renders.
  *
  * The identity is the element's focus key when it has one and its position in the tab order otherwise, which is why
  * both fields are here and why the whole value is compared. [[FocusTracker.reconcile]] acts on a request only when it
  * differs from the one the previous frame carried, so an autofocusing element grabs focus once — when it appears — and
  * then leaves the keyboard wherever the user takes it. Without a key an element that *moves* in the tab order looks
  * like a different request and grabs focus again, which is why `.autofocus` reads better with a `.key(...)` beside it.
  */
private[dsl] final case class AutofocusRequest(index: Int, key: Option[String])

/** Where focus stands: a position in the depth-first tab order, and the focus key of the element that was at that
  * position when focus landed there.
  *
  * The two travel together because they are only ever written together — moving focus sets both, and a key without the
  * index it was resolved from says nothing about which element renders focused. Also what [[FocusTracker.pushLayer]]
  * remembers: focus as it stood before a layer covered it, to put the cursor back when that layer goes away.
  */
private final case class FocusAnchor(index: Int, key: Option[String])

private object FocusAnchor:

  /** Nothing focused — see the `-1` note on [[FocusTracker.focusedIndex]]. Named `Cleared` rather than `None` so it
    * cannot be misread as `Option.None` in a file whose focus keys are themselves `Option[String]`.
    */
  val Cleared: FocusAnchor = FocusAnchor(-1, scala.None)

/** Focus and the autofocus request already handled by one covered layer; both must survive its absence. */
private final case class CoveredFocus(anchor: FocusAnchor, autofocus: Option[AutofocusRequest])

/** Per-app focus bookkeeping, owned by a single `TuiApp.runWith` invocation and touched only on the render thread:
  * which focusable (by depth-first order index) has focus, how many exist, and where each rendered last frame (for
  * click-to-focus hit-testing).
  *
  * Where things rendered is [[NodeBounds]]' job — the host's one recorder, handed in — and this class forwards to it:
  * this class decides where focus *is*, that one remembers where the frame *put* everything.
  */
private[dsl] final class FocusTracker(private[dsl] val bounds: NodeBounds = NodeBounds()):

  private var anchor: FocusAnchor = FocusAnchor(0, scala.None)

  var focusableCount: Int = 0

  /** Which focusable holds focus, as a position in the depth-first tab order — or `-1`, meaning nothing does.
    *
    * `-1` is a real state, not an "uninitialised" marker: [[clearFocus]] puts the tracker there deliberately, and while
    * it lasts no element renders focused and every key goes straight past the tree to the app's bindings. `Tab` from
    * there lands on the first focusable and `Shift+Tab` on the last.
    */
  def focusedIndex: Int = anchor.index

  /** The focus key of the element focus is anchored to, if it declared one. */
  def focusedKey: Option[String] = anchor.key

  def focusedKey_=(key: Option[String]): Unit = anchor = anchor.copy(key = key)

  /** The focus keys of the tree the last [[reconcile]] saw, in tab order (`None` for an unkeyed focusable). Kept so
    * [[focusToKey]] can answer "which index is the element named `email`?" without walking the tree again.
    */
  private var focusKeysSeen = Vector.empty[Option[String]]

  /** The focus scope of every focusable in the last reconciled tree, aligned with [[focusKeysSeen]]; empty means "every
    * focusable is its own tab stop", which is what a caller that passes no scopes gets.
    */
  private var scopes = Vector.empty[FocusScope]

  /** The member each focus group was last left on, by group id — where the next `Tab` into the group lands. */
  private val groupEntry    = mutable.Map.empty[Int, Int]
  private var lastAutofocus = Option.empty[AutofocusRequest]

  /** Records where focusable `index` rendered — see [[NodeBounds.recordTarget]]. */
  def record(index: Int, area: Rect, key: Option[String] = None): Unit =
    bounds.recordTarget(InputTarget.Focus(index), area, key = key)

  /** Records where the non-focusable node with mouse behaviour numbered `id` rendered. */
  def recordPointer(id: Int, area: Rect, passThrough: Boolean = false, key: Option[String] = None): Unit =
    bounds.recordTarget(InputTarget.Pointer(id), area, passThrough, key)

  /** Published by a scroll view for exactly the duration of its content render, and paired with [[popViewport]] in a
    * `finally` so an exception mid-render cannot leak a translation into a sibling subtree.
    */
  def pushViewport(viewport: ViewportTransform): Unit = FrameCoordinates.push(viewport)

  def popViewport(): Unit = FrameCoordinates.pop()

  /** Drops the input targets the previous frame recorded — see [[NodeBounds.clearTargets]]. */
  def clearAreas(): Unit = bounds.clearTargets()

  /** Re-anchors focus against the focus keys of the tree that is about to render (depth-first order, `None` for unkeyed
    * focusables): a keyed element keeps focus even when its position moved, and the index is clamped into the new
    * range. Areas recorded for the previous frame are dropped — this frame's render re-records them.
    *
    * A remembered key outlives frames its element is missing from. While it is gone the clamped index decides which
    * element renders focused — some element has to — but the key is not overwritten from that index, so the element
    * reclaims focus on the first frame it is back in.
    *
    * The "nothing is focused" state survives this. A tracker at `-1` — which is where [[clearFocus]] leaves it — stays
    * at `-1` however the tree changed shape, because the clamp is what an *existing* focus needs and re-anchoring a
    * deliberately dropped one would put the cursor back where the app asked it not to be.
    *
    * `autofocus` is the element asking to be focused, if the tree contains one. It is honoured only when it differs
    * from the request the previous frame carried — that is, when the asking element has just appeared. Honouring it on
    * every frame instead would pin the keyboard to that one element forever: Tab would move focus and the next render
    * would take it straight back.
    */
  def reconcile(
      keys: Seq[Option[String]],
      autofocus: Option[AutofocusRequest],
      focusScopes: Seq[FocusScope] = Seq.empty,
  ): Unit =
    focusKeysSeen = keys.toVector
    scopes = focusScopes.toVector
    focusableCount = keys.size
    if anchor.index < 0 then anchor = FocusAnchor.Cleared
    else
      val reanchored = anchor.key.map(key => keys.indexOf(Some(key))).filter(_ >= 0)
      val index      = clampedIndex(reanchored.getOrElse(anchor.index))
      // A remembered key is *kept* while its element is missing, and only an anchor that never had one adopts the key
      // of whatever now sits at the index. A collapsed section or an untaken `if` branch takes its element out of the
      // tree for a frame; rewriting the key from the index there would hand the memory to a neighbouring element, and
      // the field coming back could never reclaim focus. An explicit move (`focusTo`, a click, Tab) clears the key
      // itself, so this only preserves a key nobody asked to give up.
      anchor = FocusAnchor(index, anchor.key.orElse(keys.lift(index).flatten))
    val appeared = autofocus.filterNot(request => lastAutofocus.contains(request))
    lastAutofocus = autofocus
    // an element that has just appeared takes focus even from the cleared state: asking for it is the whole point
    appeared.foreach { request =>
      val index = clampedIndex(request.index)
      anchor = FocusAnchor(index, keys.lift(index).flatten)
    }
    scopeOf(anchor.index).group.foreach(group => groupEntry(group) = anchor.index)
    clearAreas()

  /** An index pulled back inside the tab order, or zero when there is nothing focusable to point at. */
  private def clampedIndex(index: Int): Int =
    if focusableCount > 0 then math.max(0, math.min(index, focusableCount - 1)) else 0

  /** Moves focus to the focusable declared with `.key(name)` in the last reconciled tree; `false` — and nothing changed
    * — when no focusable carries that key, which is what happens when the element sits in a branch the view did not
    * render this frame.
    *
    * Unlike [[focusTo]] the key is *remembered*, so focus then follows that element across later renders even when the
    * tree changes shape: this is a lasting "the cursor belongs on the email field", not a one-off jump to a position.
    */
  def focusToKey(key: String): Boolean =
    val index = focusKeysSeen.indexOf(Some(key))
    if index < 0 then false
    else
      anchor = FocusAnchor(index, Some(key))
      true

  /** Drops focus entirely: no element renders focused, and keys go past the tree straight to the app's bindings until
    * `Tab`, a mouse press or [[focusToKey]] puts focus back.
    */
  def clearFocus(): Unit = anchor = FocusAnchor.Cleared

  /** Focus and autofocus history as each covering layer found them; innermost layer first. */
  private var covered = List.empty[CoveredFocus]

  /** Called when a layer goes over the current tree — a modal or full screen is pushed, the command palette opens.
    *
    * Remembers where focus was and anchors it at the top of the tab order, so the incoming layer starts on its *first*
    * control. Without this the old index is merely clamped into the new layer's range, which is why opening a
    * three-field dialog while the app's fifth control was focused used to land the cursor on the dialog's last field.
    * Autofocus starts fresh too: equal keys and indices on distinct layers are distinct requests.
    */
  def pushLayer(): Unit =
    covered = CoveredFocus(anchor, lastAutofocus) :: covered
    anchor = FocusAnchor(0, scala.None)
    lastAutofocus = None

  /** Called when that layer goes away. Puts focus back where the layer found it, including autofocus history so an
    * unchanged request underneath does not steal the restored focus. A genuinely changed request still runs normally.
    *
    * `reconcile` still runs afterwards, so a tree that changed shape underneath the layer cannot restore an index that
    * is now out of range: the restored key re-anchors when it is still there, and the index is clamped when it is not.
    * Popping more layers than were pushed anchors at the top rather than failing — the two calls are driven by a depth
    * comparison and are balanced by construction, and guessing at the tab order is a better failure than an exception
    * in the render pass.
    */
  def popLayer(): Unit =
    covered match
      case frame :: rest =>
        anchor = frame.anchor
        lastAutofocus = frame.autofocus
        covered = rest
      case Nil           =>
        anchor = FocusAnchor(0, scala.None)
        lastAutofocus = None

  private def scopeOf(index: Int): FocusScope = scopes.lift(index).getOrElse(FocusScope.Unscoped)

  private def membersOf(group: Int): Vector[Int] =
    (0 until focusableCount).filter(scopeOf(_).group.contains(group)).toVector

  /** The tab stops from where focus stands: every ungrouped focusable, one member per focus group (the focused one,
    * else the one the group was last left on, else its first), and — while focus is inside a `.trapFocus` subtree —
    * only those inside the innermost such subtree.
    */
  private def tabStops: Vector[Int] =
    val current = scopeOf(focusedIndex)
    val trapped = Option.when(focusedIndex >= 0)(current.traps.headOption).flatten
    (0 until focusableCount).filter { index =>
      val scope = scopeOf(index)
      trapped.forall(scope.traps.contains) && scope.group.forall { group =>
        val members = membersOf(group)
        val entry   =
          if members.contains(focusedIndex) then focusedIndex
          else groupEntry.get(group).filter(members.contains).getOrElse(members.head)
        index == entry
      }
    }.toVector

  /** Steps through [[tabStops]] from the focused index, wrapping; `false` when there is nowhere else to go. */
  private def stepStops(forward: Boolean): Boolean =
    val stops = tabStops
    if stops.isEmpty then false
    else if focusedIndex < 0 then
      focusTo(if forward then stops.head else stops.last)
      true
    else
      val next =
        if forward then stops.find(_ > focusedIndex).getOrElse(stops.head)
        else stops.findLast(_ < focusedIndex).getOrElse(stops.last)
      if next == focusedIndex then false
      else
        focusTo(next)
        true

  /** An arrow inside a focus group: the next (`forward`) or previous member, wrapping. `false` when focus is in no
    * group or the group has a single member, so the arrow keeps bubbling.
    */
  def moveInGroup(forward: Boolean): Boolean =
    scopeOf(focusedIndex).group.map(membersOf).filter(_.size > 1) match
      case Some(members) =>
        val at = members.indexOf(focusedIndex)
        focusTo(members((at + (if forward then 1 else members.size - 1)) % members.size))
        true
      case None          => false

  /** Spatial navigation: the nearest focusable painted in `direction` from the focused one, by the hit areas the last
    * frame recorded — see [[SpatialNavigation]]. Stays inside a `.trapFocus` subtree; `false` when nothing lies that
    * way or the focused element was not painted.
    */
  def focusInDirection(direction: SpatialDirection): Boolean =
    val trapped = scopeOf(focusedIndex).traps.headOption
    areaOf(focusedIndex) match
      case None       => false
      case Some(from) =>
        val candidates = (0 until focusableCount)
          .filter(index => index != focusedIndex && trapped.forall(scopeOf(index).traps.contains))
          .flatMap(index => areaOf(index).map(index -> _))
        SpatialNavigation.nearest(from, candidates, direction) match
          case Some(index) =>
            focusTo(index)
            true
          case None        => false

  /** `Tab`. From the no-focus state this lands on the *first* focusable rather than the second one. A focus group
    * counts once, and a `.trapFocus` subtree holding focus keeps it — see [[tabStops]].
    */
  def focusNext(): Boolean =
    if focusableCount == 0 then false
    else if scopes.nonEmpty then stepStops(forward = true)
    else if focusedIndex < 0 then
      focusTo(0)
      true
    else if focusableCount > 1 then
      focusTo((focusedIndex + 1) % focusableCount)
      true
    else false

  /** `Shift+Tab`. From the no-focus state this lands on the *last* focusable — stepping backwards out of "nothing"
    * arrives at the end of the tab order, the same way it wraps from the first element.
    */
  def focusPrevious(): Boolean =
    if focusableCount == 0 then false
    else if scopes.nonEmpty then stepStops(forward = false)
    else if focusedIndex < 0 then
      focusTo(focusableCount - 1)
      true
    else if focusableCount > 1 then
      focusTo((focusedIndex - 1 + focusableCount) % focusableCount)
      true
    else false

  /** Moves focus to `index` deliberately (Tab, a click), forgetting the remembered [[focusedKey]].
    *
    * The key exists so focus can *follow* an element whose position changed between renders. Keeping the old key here
    * would instead pull focus straight back to where it was on the next render, which is what an explicit move is
    * asking not to happen; the render pass re-derives the key from the new index.
    */
  def focusTo(index: Int): Unit =
    // clamped here as well as in `reconcile`, so the class holds its own invariant no matter which caller moves focus:
    // an index outside the range would render a frame with nothing focused at all
    anchor = FocusAnchor(clampedIndex(index), scala.None)

  /** The input target the pointer last rested over, as of the last mouse event — what the next focus pass marks
    * `hovered`. A target painted by a node with a `.key` is remembered by that key ([[hoveredKey]]), so the mark
    * follows the node when the tree reshapes before the next frame; an unkeyed one by this frame's numbering, stable as
    * long as the tree's shape is — the same bargain an unkeyed focus index makes.
    */
  var hoveredTarget: Option[InputTarget] = None

  /** The `.key` of the node behind [[hoveredTarget]], when it has one. */
  var hoveredKey: Option[String] = None

  /** The input target a button went down on, until it comes back up — what the next focus pass marks `pressed`, and
    * where pointer capture keeps delivering the drag. Remembered by key like [[hoveredTarget]].
    */
  var pressedTarget: Option[InputTarget] = None

  /** The `.key` of the node behind [[pressedTarget]], when it has one. */
  var pressedKey: Option[String] = None

  /** Whether the node with `key` painted as `target` this frame is the one remembered as `remembered` /
    * `rememberedKey`: by key when the remembered one had a key, by numbering otherwise.
    */
  def isSame(
      remembered: Option[InputTarget],
      rememberedKey: Option[String],
      target: Option[InputTarget],
      key: Option[String],
  ): Boolean =
    rememberedKey match
      case Some(name) => key.contains(name)
      case None       => remembered.isDefined && remembered == target

  /** The visible, clipped screen rect focusable `index` rendered into — for hit eligibility and spatial navigation. */
  def areaOf(index: Int): Option[Rect] = bounds.targetBounds(InputTarget.Focus(index)).map(_.hitArea)

  /** The visible screen rect the pointer-only target `id` rendered into. */
  def pointerAreaOf(id: Int): Option[Rect] = bounds.targetBounds(InputTarget.Pointer(id)).map(_.hitArea)

  /** The focus index of the target painted last at `pos`, if it has one. Never looks through a pointer-only target. */
  def hitTest(pos: Position): Option[Int] = mouseHit(pos).flatMap(_.target.focusIndex)

  /** One resolution shared by mouse routing and click-to-focus. */
  def mouseHit(pos: Position): Option[MouseHit] = bounds.targetAt(pos)

private[dsl] object FocusPass:

  /** A copy of the tree with every element made unfocusable and marked `inert` — how a layer *below* a modal drops out
    * of the tab order *and* out of event routing while remaining visible. Suppression covers input, not merely tabbing:
    * no element in the returned tree receives a key or a mouse event, whether or not the layer above it contains a
    * focusable of its own ([[EventRouter]] refuses to descend into an inert subtree).
    */
  def suppressFocus(element: Element): Element =
    val cleared = element.withProps(
      element.props.copy(focusable = false, focusState = element.props.focusState.copy(inert = true))
    )
    cleared.withChildren(cleared.children.map(suppressFocus))

  /** The focus keys of every focusable in depth-first order (`None` for unkeyed ones) — the domain of
    * [[FocusTracker.focusedIndex]], and what lets focus follow an element across renders when the tree changes shape.
    */
  def focusKeys(element: Element): Vector[Option[String]] =
    if element.props.inert || element.props.disabled then Vector.empty
    else
      val own = if element.props.focusable then Vector(element.props.focusKey) else Vector.empty
      own ++ element.children.flatMap(focusKeys)

  /** The first focusable in the tab order that asked for focus with `.autofocus`, if any.
    *
    * "First" rather than "the one", because nothing stops a tree from carrying two — a screen with an autofocusing
    * search box pushed over a form with an autofocusing first field, say. Taking the first in depth-first order makes
    * that case decided rather than arbitrary. (A modal covers the layer below it through [[FocusPass.suppressFocus]],
    * which clears `focusable` on every node underneath, so a covered element's request is not in the tab order at all
    * and cannot win.)
    */
  def autofocusRequest(root: Element): Option[AutofocusRequest] =
    def search(element: Element, index: Int): (Option[AutofocusRequest], Int) =
      if element.props.inert || element.props.disabled then (None, index)
      else
        val own  =
          if element.props.focusable && element.props.autofocus then
            Some(AutofocusRequest(index, element.props.focusKey))
          else None
        val next = if element.props.focusable then index + 1 else index
        element.children.foldLeft((own, next)) { case ((found, position), child) =>
          val (childFound, after) = search(child, position)
          (found.orElse(childFound), after)
        }
    search(root, 0)._1

  /** Rebuilds the tree with the theme's focus cue stamped on every node, the focused element marked (`props.focused =
    * true`), and every focusable wrapped in a [[TrackedElement]] that records its rendered area; a non-focusable
    * element with user or built-in mouse behavior, or with a pointer shape of its own, is wrapped in a
    * [[PointerElement]] instead, which records its area for pointer-filtered mouse delivery and pointer-shape lookup.
    * Focus indices are assigned in depth-first pre-order — the tab order; pointer ids are a separate numbering that no
    * other pass reads.
    *
    * `focusStyle` goes onto *every* node, not only the focused one, because it is the app's theme cue and not a
    * per-element flag: a collection element paints its selected row in it whether or not it currently holds focus (see
    * [[ListElement]]), so a list sitting beside the focused one — or one in a subtree [[suppressFocus]] cleared for a
    * modal — still has to draw the app's highlight rather than the plain reverse-video default. `props.focused` is what
    * distinguishes the one element keystrokes go to, and it is set here on exactly one node.
    *
    * `bounds` is the host's node-bounds recorder when recording is on. Then every node `fullBounds` asks for — all of
    * them when it is true, else only keyed nodes, containers, input targets, nodes with a pointer role and everything
    * under a [[BoundsAwareElement]], inert ones included since they still paint — is additionally wrapped in a
    * [[BoundsElement]] carrying its pre-order id and the id of its last descendant, placed *inside* any input wrapper
    * so [[EventRouter]] still finds the `TrackedElement`/`PointerElement` where it looks for them. With `None` no
    * wrapper is built and the tree is exactly what it was before recording existed — and `onBoundsDemand` is called for
    * every [[BoundsAwareElement]] met on the way, which is how a host that is not recording learns that the tree it is
    * about to paint has a consumer and records the next frame.
    *
    * `onFocusPath` is handed the child positions, root first, that lead to the focused element in the returned tree
    * (`None` when nothing is focused) — the path key routing would otherwise search the whole tree for on every event.
    *
    * `gestures` is the host's [[PointerGestures]]. A node the gestures have a role for — selectable text — is wrapped
    * in a [[BoundsElement]] carrying its [[PointerRoles]] even when `bounds` is `None`, recording into the same
    * recorder, so a press can find it and its overlay is composed as it paints. Inert nodes take no role.
    */
  def decorate(
      root: Element,
      tracker: FocusTracker,
      focusStyle: Style,
      bounds: Option[NodeBounds] = None,
      onBoundsDemand: () => Unit = () => (),
      disabledStyle: Style = Style.Default.dim,
      onMotionDemand: () => Unit = () => (),
      onCueDemand: () => Unit = () => (),
      theme: Theme = Theme.default,
      onFocusPath: Option[Vector[Int]] => Unit = _ => (),
      gestures: Option[PointerGestures] = None,
      fullBounds: Boolean = true,
  ): Element =
    var counter        = 0
    var pointerCounter = 0
    var nodeCounter    = 0
    // child positions from the root down to the node being transformed, innermost first, and the trail that led to the
    // focused node — the path key routing follows instead of searching the tree for it
    var trail          = List.empty[Int]
    var focusedTrail   = Option.empty[Vector[Int]]
    // the scroll views enclosing the node being transformed, innermost first, each gathering the visibility request it
    // will act on before it paints — the focused descendant's, or the first `.ensureVisible` mark's
    var revealing      = List.empty[PendingReveal]

    def themed(
        element: Element,
        focused: Boolean,
        disabled: Boolean,
        target: Option[InputTarget],
        component: StateStyles,
        inherited: Style,
    ): Element =
      val live = !disabled && target.isDefined
      element.withProps(
        element.props.copy(
          style = if inherited eq Style.Default then element.props.style else inherited.patch(element.props.style),
          focusState = element.props.focusState.copy(
            focused = focused,
            focusStyle = focusStyle,
            disabled = disabled,
            disabledStyle = disabledStyle,
            hovered = live && tracker.isSame(tracker.hoveredTarget, tracker.hoveredKey, target, element.props.focusKey),
            pressed = live && tracker.isSame(tracker.pressedTarget, tracker.pressedKey, target, element.props.focusKey),
            component = component,
            theme = theme,
          ),
        )
      )

    def transform(
        built: Element,
        ancestorInert: Boolean,
        ancestorDisabled: Boolean,
        inherited: Style,
        ancestorUnselectable: Boolean = false,
        ancestorAware: Boolean = false,
    ): Element =
      // a node a themed factory built against the ambient default theme takes this frame's theme instead; a `withStyle`
      // scope is marked applied, because the cascade it asks for happens right here, on the way down
      val element      = LateBinding.resolve(built, theme) match
        case scope: StyleScopeElement => scope.copy(applied = true)
        case other                    => other
      val cascade      = element match
        case scope: StyleScopeElement => inherited.patch(scope.cascade)
        case _                        => inherited
      val inert        = ancestorInert || element.props.inert
      // a node that reads where its subtree painted — the click-outside backdrop — needs every node under it recorded
      val aware        = ancestorAware || (element match
        case _: BoundsAwareElement => true
        case _                     => false)
      // `.selectable(false)` opts the whole subtree out of text selection
      val unselectable = ancestorUnselectable || element.props.selectable.contains(false)
      val disabled     = ancestorDisabled || element.props.disabled
      val nodeId       = nodeCounter
      nodeCounter += 1
      if bounds.isEmpty then
        element match
          case _: BoundsAwareElement => onBoundsDemand()
          case _                     => ()
      // a hover cue, a pressed cue or a pointer shape asked for by name needs all-motion tracking; a theme component
      // with a hovered or pressed style asks for the same as an explicit `.hoverStyle`/`.pressedStyle` does
      val states       = componentOf(element, theme)
      val hoverCue     = element.props.hoverStyle.isDefined || states.hovered != Style.Default
      val pressCue     = element.props.pressedStyle.isDefined || states.pressed != Style.Default
      // a pressed cue asks too: once the button comes back up the control returns to hovered or plain, and only
      // motion reports say which
      if hoverCue || pressCue || element.props.pointerShape.isDefined then onMotionDemand()
      // a hover or pressed cue is what makes a change of hovered/pressed target change the next frame
      if hoverCue || pressCue then onCueDemand()
      val current      =
        if inert then themed(element, focused = false, disabled, None, states, inherited)
        else if element.props.focusable && !disabled then
          val index  = counter
          counter += 1
          val target = Some(InputTarget.Focus(index))
          if index == tracker.focusedIndex then
            focusedTrail = Some(trail.reverse.toVector)
            revealing.foreach(_.focus(RevealProbe.focusId(index)))
          TrackedElement(
            themed(element, focused = index == tracker.focusedIndex, disabled, target, states, inherited),
            index,
            tracker,
          )
        // a node that only asks for a pointer shape is a pointer target too: the shape is resolved by hit-testing, so a
        // `link` with no handler still has to record where it painted. A disabled control is one as well — it is out of
        // the tab order, but a press on it must land on it (and do nothing) rather than on whatever is drawn beneath.
        else if element.props.focusable || element.props.onMouse.isDefined || element.props.onMouseContext.isDefined ||
          element.builtinMouseHandler.isDefined || element.pointerShape.isDefined
        then
          val id = pointerCounter
          pointerCounter += 1
          PointerElement(
            themed(element, focused = false, disabled, Some(InputTarget.Pointer(id)), states, inherited),
            id,
            tracker,
          )
        else themed(element, focused = false, disabled, None, states, inherited)
      if element.props.ensureVisible && !inert then
        val id = RevealProbe.visibleId(element.props.focusKey.getOrElse(nodeId.toString))
        revealing.foreach(_.visible(id))
      val enclosing    = revealing
      val gathering    = element match
        case scroll: ScrollViewElement if !inert => Some(PendingReveal(scroll.followsFocus))
        case _                                   => None
      gathering.foreach(pending => revealing = pending :: revealing)
      // Inert trees still paint, including deferred portals: keep theme and coordinate wrappers, but no input records.
      val outer        = trail
      var position     = 0
      val children     =
        viewportWrapped(
          element,
          current.children.map { child =>
            // a scroll view's content sits one level down, under the viewport wrapper `viewportWrapped` adds
            trail = element match
              case _: ScrollViewElement => 0 :: position :: outer
              case _                    => position :: outer
            position += 1
            transform(child, inert, disabled, cascade, unselectable, aware)
          },
        )
      trail = outer
      revealing = enclosing
      element match
        case scroll: ScrollViewElement => scroll.state.pendingReveal = gathering.flatMap(_.chosen)
        case _                         => ()
      def rolesOf(node: Element, g: PointerGestures, disabled: Boolean): PointerRoles =
        // an unfocusable leaf takes the role only while a selection may need it — see `TextSelection.recordsLeaves`
        val selectable = g.selection.enabled && !unselectable &&
          (node.props.selectable.contains(true) || TextSelection.selectableByDefault(node)) &&
          (node.props.focusable || g.leavesThisFrame)
        PointerRoles(
          selectable = selectable,
          drag = if disabled then None else node.props.dragPayload,
          drop = if disabled then None else node.props.dropTarget,
          dragSelects = selectable && TextSelection.selectsOnDrag(node),
        )
      val roles        =
        gestures.fold(PointerRoles.Empty)(g => if inert then PointerRoles.Empty else rolesOf(element, g, disabled))
      // a selection holds on to the node by its key, or else by its path of child positions — both survive nodes being
      // added elsewhere in the tree, which a pre-order id does not
      val anchor       =
        Option.when(roles.selectable)(TextSelection.anchorFor(element.props.focusKey, outer))
      // narrow recording keeps what a reader asks for by name or by role: keyed nodes, containers, input targets, nodes
      // with a pointer role, and everything under a bounds-aware node; full recording keeps every node
      val inputTarget  = current match
        case _: TrackedElement | _: PointerElement => true
        case _                                     => false
      val wanted       = fullBounds || aware || inputTarget || element.props.focusKey.isDefined ||
        element.children.nonEmpty || !roles.isEmpty
      val recorded     = bounds.filter(_ => wanted).orElse(gestures.filter(_ => !roles.isEmpty).map(_.recorder)) match
        case Some(recorder) => boundsWrapped(current, nodeId, nodeCounter - 1, recorder, roles, gestures, anchor)
        case None           => current
      val revealed     =
        if element.props.ensureVisible && !inert then
          revealWrapped(recorded, element.props.focusKey.getOrElse(nodeId.toString))
        else recorded
      revealed.withChildren(children)

    val decorated = transform(root, ancestorInert = false, ancestorDisabled = false, inherited = Style.Default)
    onFocusPath(focusedTrail)
    decorated

  /** The theme's component styles for `element`'s role: its `.variant(...)`, else the role its node type declares. */
  private def componentOf(element: Element, theme: Theme): StateStyles =
    element.props.variant.orElse(element.componentRole).fold(StateStyles.Empty)(theme.component)

  /** `current` with a [[BoundsElement]] around the themed node — *under* an input wrapper, never over it, so the walks
    * in [[EventRouter]] that match on `TrackedElement`/`PointerElement` keep finding them at the node's own level.
    */
  private def boundsWrapped(
      current: Element,
      id: Int,
      lastDescendantId: Int,
      recorder: NodeBounds,
      roles: PointerRoles,
      gestures: Option[PointerGestures],
      anchor: Option[String],
  ): Element =
    def wrap(node: Element): BoundsElement =
      BoundsElement(node, id, lastDescendantId, recorder, roles, if roles.isEmpty then None else gestures, anchor)
    current match
      case tracked: TrackedElement => tracked.copy(inner = wrap(tracked.inner))
      case pointer: PointerElement => pointer.copy(inner = wrap(pointer.inner))
      case other                   => wrap(other)

  /** `current` with a [[RevealElement]] around the node — under any input wrapper, for the reason [[boundsWrapped]]
    * gives.
    */
  private def revealWrapped(current: Element, id: String): Element =
    current match
      case tracked: TrackedElement => tracked.copy(inner = RevealElement(tracked.inner, id))
      case pointer: PointerElement => pointer.copy(inner = RevealElement(pointer.inner, id))
      case other                   => RevealElement(other, id)

  /** A scroll view renders its content into an offscreen buffer, so every rect the content subtree is handed — and
    * hence every area recorded underneath it — is in content coordinates. Wrapping the content re-anchors those records
    * onto the screen.
    */
  private def viewportWrapped(element: Element, children: Seq[Element]): Seq[Element] =
    element match
      case scroll: ScrollViewElement => children.map(child => ScrollViewportElement(child, scroll.state))
      case _                         => children

/** One scroll view's visibility request as the focus pass finds it, before anything paints: an `.ensureVisible` mark
  * wins over the focused descendant, which counts only when the view follows focus. Built and read inside one
  * [[FocusPass.decorate]] call.
  */
private[dsl] final class PendingReveal(followFocus: Boolean):
  private var marked: Option[String]  = None
  private var focused: Option[String] = None

  def visible(id: String): Unit = if marked.isEmpty then marked = Some(id)

  def focus(id: String): Unit = if followFocus && focused.isEmpty then focused = Some(id)

  def chosen: Option[String] = marked.orElse(focused)
