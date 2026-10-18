package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size

/** A coarse width band for a terminal, the vocabulary [[Element.responsive]] and [[TuiApp.breakpoint]] branch on.
  *
  * Bands are width-only and cumulative: `Medium` means "at least 80 columns", not "exactly 80 to 119". Height is left
  * out deliberately — a `Size => Element` builder still sees both axes, and short-but-wide terminals want a different
  * decision than narrow ones, so folding both into one band would hide the distinction rather than name it.
  *
  * The thresholds are the ones terminals actually cluster around: 80 columns is the historical default, 120 the common
  * width of a maximized modern window, and anything under 60 is a split pane or a phone-sized SSH session.
  */
enum Breakpoint:
  /** Under 60 columns — a split pane or a phone. Expect to drop everything but one column of content. */
  case XSmall

  /** 60 to 79 columns — a narrow window. Sidebars usually have to go. */
  case Small

  /** 80 to 119 columns — the classic terminal. */
  case Medium

  /** 120 columns or wider — a maximized window; room for a sidebar, a detail pane, and charts. */
  case Large

  /** Whether this band is `other` or wider — `if breakpoint.atLeast(Breakpoint.Medium) then …`. */
  def atLeast(other: Breakpoint): Boolean = ordinal >= other.ordinal

  /** Whether this band is narrower than `other`. */
  def isBelow(other: Breakpoint): Boolean = ordinal < other.ordinal

object Breakpoint:

  /** The band `size` falls in, by width alone. */
  def of(size: Size): Breakpoint = ofWidth(size.width)

  def ofWidth(width: Int): Breakpoint =
    if width < 60 then Breakpoint.XSmall
    else if width < 80 then Breakpoint.Small
    else if width < 120 then Breakpoint.Medium
    else Breakpoint.Large

/** Resolves [[ResponsiveElement]] nodes against the frame size, and builds each [[LazyListElement]]'s window, before
  * the focus pass runs.
  *
  * Order matters and is the whole reason this is a separate pass rather than something an element does while rendering:
  * `FocusPass.focusKeys` derives the tab order from the tree, and `EventRouter` routes keys and clicks through it, so
  * the subtree a breakpoint selected has to be *in* the tree by the time those run. An element that picked its children
  * at render time would be invisible to both — focusable widgets inside it would never receive a Tab stop and clicks on
  * them would miss.
  *
  * A responsive node on a layer a modal covers has the branch it builds suppressed here as well — that layer's
  * suppression happened before the branch existed.
  */
private[dsl] object ResponsivePass:

  /** A copy of `element` with every media-query node holding the branch it builds at `size`, top-down. Container
    * queries (`responsive.local`) are left unresolved — they need [[resolveFrame]]'s pre-pass.
    */
  def resolve(element: Element, size: Size): Element = resolveAt(element, size, NestingLimit, Round(first = true))

  /** [[resolve]] plus the layout pre-pass container queries need.
    *
    * After the ordinary resolution, while the tree still holds unresolved local nodes, `dryPaint` paints it (into a
    * scratch buffer the caller owns) under [[LocalProbe.measure]], each unresolved local node records the area it was
    * handed, and a further round builds those nodes' branches for the areas recorded. A branch can hold local nodes of
    * its own, so this repeats — at most [[LocalRounds]] times, bounding a pathological nesting the way [[NestingLimit]]
    * bounds a self-returning builder. A local node the dry paint never reached (scrolled out of a scroll view's window,
    * on a hidden tab) stays unresolved and falls back to building at render time. A tree with no local node costs
    * nothing extra: no dry paint, no second round.
    */
  def resolveFrame(element: Element, size: Size, dryPaint: Element => Unit): Element =
    val first = Round(first = true)
    var tree  = resolveAt(element, size, NestingLimit, first)
    var round = first
    var count = 0
    while round.pending > 0 && count < LocalRounds do
      val areas = LocalProbe.measure(dryPaint(tree))
      round = Round(first = false, areas = areas, next = round.next)
      tree = resolveAt(tree, size, NestingLimit, round)
      count += 1
    tree

  /** One walk's bookkeeping. `first` is the ordinary resolution of a freshly evaluated view, which builds every node it
    * meets; a later round keeps what earlier rounds built and only fills in the local nodes `areas` now has a size for.
    */
  private final class Round(val first: Boolean, val areas: Map[Int, Size] = Map.empty, var next: Int = 0):
    /** Unresolved local nodes this walk left behind with a fresh probe, which the next dry paint will measure. */
    var pending: Int = 0

    def probe(node: ResponsiveElement): ResponsiveElement =
      val token = next
      next += 1
      pending += 1
      node.probedAs(token)

  /** `fuel` bounds *consecutive* responsive nesting — a builder that returns another responsive node — and resets at
    * every ordinary node, so it never constrains how deep a tree may be. Exhausting it leaves the node unresolved,
    * degrading to its own render-time fallback; a builder that returns itself is a programmer error that should show up
    * as odd focus behavior, not as a hung render thread.
    */
  private def resolveAt(element: Element, size: Size, fuel: Int, round: Round): Element =
    element match
      case responsive: ResponsiveElement =>
        responsive.resolved match
          // a later round: what an earlier one built stays, and only local nodes inside it can still need work
          case Some(branch) if !round.first =>
            responsive.resolvedAt(resolveAt(branch, size, fuel - 1, round))
          case _ if fuel <= 0               => responsive
          case _ if responsive.local        =>
            round.areas.get(responsive.probe).filter(_ => !round.first) match
              case Some(area)                                  => branchOf(responsive, area, size, fuel, round)
              case None if responsive.probe < 0 || round.first => round.probe(responsive)
              // probed, but the dry paint never reached it: leave it to its render-time fallback
              case None                                        => responsive
          case _                            => branchOf(responsive, size, size, fuel, round)
      case lazyList: LazyListElement     =>
        val built = if round.first || lazyList.window.isEmpty then lazyList.builtFor(size.height) else lazyList
        built.withChildren(built.children.map(resolveAt(_, size, NestingLimit, round)))
      case other                         =>
        other.withChildren(other.children.map(resolveAt(_, size, NestingLimit, round)))

  /** `responsive` holding the branch it builds at `at`, resolved in turn against the frame's `size`. */
  private def branchOf(responsive: ResponsiveElement, at: Size, size: Size, fuel: Int, round: Round): Element =
    val branch = resolveAt(responsive.build(at), size, fuel - 1, round)
    // suppression runs in TuiApp.effectiveView, before this pass, so a branch that did not exist yet when the modal
    // was composed has to inherit its node's suppression here or it rejoins the tab order and the router
    responsive.resolvedAt(if responsive.props.inert then FocusPass.suppressFocus(branch) else branch)

  private val NestingLimit: Int = 16

  /** How many pre-pass rounds one frame may take: one per level of local-inside-local nesting. */
  private val LocalRounds: Int = 4

/** The container-query pre-pass's recorder: while [[measure]] runs a dry paint, each unresolved local
  * [[ResponsiveElement]] reports the size of the area it was handed instead of drawing.
  *
  * Per thread, like the other paint-time registers ([[CaretRegister]], the portal queue), because each runner paints on
  * its own render thread.
  */
private[dsl] object LocalProbe:

  private val current: ThreadLocal[Option[scala.collection.mutable.HashMap[Int, Size]]] =
    ThreadLocal.withInitial(() => Option.empty[scala.collection.mutable.HashMap[Int, Size]])

  /** Whether a dry paint is in progress on this thread — a paint whose output is thrown away. */
  def measuring: Boolean = current.get().isDefined

  /** Records that the local node holding `token` was handed an area of `size`; the first report wins. */
  def record(token: Int, size: Size): Unit =
    current.get().foreach(areas => if token >= 0 && !areas.contains(token) then areas.update(token, size))

  /** Runs `paint` as a dry paint and returns the sizes the local nodes in it reported. */
  def measure(paint: => Unit): Map[Int, Size] =
    val previous = current.get()
    val areas    = scala.collection.mutable.HashMap.empty[Int, Size]
    current.set(Some(areas))
    try paint
    finally current.set(previous)
    areas.toMap
