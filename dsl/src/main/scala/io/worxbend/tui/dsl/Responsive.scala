package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Size, Versioned}

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

/** What the focus pass and the version watch need from a resolved tree, collected by the very walk that resolved it.
  *
  * Each field is what one of the separate walks the frame used to make answers — [[FocusPass.focusKeys]],
  * [[FocusPass.autofocusRequest]], [[FocusScope.of]] and [[StateWatch.collect]] — and `FramePassSpec` holds the fused
  * walk to exactly their answers. `states` are the versioned states the tree paints, each once, in pre-order; their
  * versions are read after the paint, not here, because a render may itself adjust a state.
  */
private[dsl] final case class FrameAnalysis(
    focusKeys: Vector[Option[String]],
    autofocus: Option[AutofocusRequest],
    scopes: Vector[FocusScope],
    states: Vector[Versioned],
)

private[dsl] object FrameAnalysis:

  /** The analysis of a tree that needs no resolving — one walk, collecting only. */
  def of(tree: Element): FrameAnalysis =
    val collector                                            = FrameCollector()
    def walk(node: Element, scope: Option[FocusScope]): Unit =
      val inside = collector.visit(node, scope)
      node.children.foreach(walk(_, inside))
    walk(tree, Some(FocusScope.Unscoped))
    collector.result

/** A resolved tree with its [[FrameAnalysis]] — what [[ResponsivePass.resolveFrame]] hands the focus pass. */
private[dsl] final case class ResolvedFrame(tree: Element, analysis: FrameAnalysis)

/** The accumulators of one fused walk. A node is *visited* by the focus walks unless an inert or disabled ancestor cut
  * it off, which `visit` expresses by handing its children `None` for their scope — exactly where the separate walks
  * stopped descending. The version watch, unlike them, sees every node.
  */
private final class FrameCollector:
  private val keys      = Vector.newBuilder[Option[String]]
  private var focusable = 0
  private var autofocus = Option.empty[AutofocusRequest]
  private val scopes    = Vector.newBuilder[FocusScope]
  private var nodeId    = 0
  private val seen      = java.util.IdentityHashMap[Versioned, java.lang.Boolean]()
  private val states    = Vector.newBuilder[Versioned]

  /** Collects `node`, reached under `scope` (`None` once a cut-off ancestor stopped the focus walks), and answers the
    * scope its children are reached under.
    */
  def visit(node: Element, scope: Option[FocusScope]): Option[FocusScope] =
    node.watchedStates.foreach { state =>
      if !seen.containsKey(state) then
        val _ = seen.put(state, java.lang.Boolean.TRUE)
        states += state
    }
    scope.flatMap { outer =>
      val props = node.props
      val id    = nodeId
      nodeId += 1
      if props.inert || props.disabled then None
      else
        val traps  = if props.trapFocus then id :: outer.traps else outer.traps
        val inside = node match
          case _: FocusGroupElement => FocusScope(Some(id), traps)
          case _                    => outer.copy(traps = traps)
        if props.focusable then
          if autofocus.isEmpty && props.autofocus then autofocus = Some(AutofocusRequest(focusable, props.focusKey))
          keys += props.focusKey
          scopes += inside
          focusable += 1
        Some(inside)
    }

  def result: FrameAnalysis = FrameAnalysis(keys.result(), autofocus, scopes.result(), states.result())

/** The frame's resolution pass: resolves [[ResponsiveElement]] nodes against the frame size, builds each
  * [[LazyListElement]]'s window, and — in the same walk — collects the [[FrameAnalysis]] the focus pass runs on.
  *
  * Order matters and is the whole reason this is a separate pass rather than something an element does while rendering:
  * the focus pass derives the tab order from the tree, and `EventRouter` routes keys and clicks through it, so the
  * subtree a breakpoint selected has to be *in* the tree by the time those run. An element that picked its children at
  * render time would be invisible to both — focusable widgets inside it would never receive a Tab stop and clicks on
  * them would miss.
  *
  * The resolution and the collection are one traversal because they used to be five — resolve, tab order, autofocus,
  * focus scopes, watched states — each a full walk of a tree only the first of them changed. Collection happens on the
  * way down, at each node before its children, which is the pre-order every one of those walks used.
  *
  * A responsive node on a layer a modal covers has the branch it builds suppressed here as well — that layer's
  * suppression happened before the branch existed.
  */
private[dsl] object ResponsivePass:

  /** A copy of `element` with every media-query node holding the branch it builds at `size`, top-down. Container
    * queries (`responsive.local`) are left unresolved — they need [[resolveFrame]]'s pre-pass.
    */
  def resolve(element: Element, size: Size): Element =
    resolveAt(element, size, NestingLimit, Round(first = true), Some(FocusScope.Unscoped))

  /** [[resolve]] plus the layout pre-pass container queries need, plus the tree's [[FrameAnalysis]].
    *
    * After the ordinary resolution, while the tree still holds unresolved local nodes, `dryPaint` paints it (into a
    * scratch buffer the caller owns) under [[LocalProbe.measure]], each unresolved local node records the area it was
    * handed, and a further round builds those nodes' branches for the areas recorded. A branch can hold local nodes of
    * its own, so this repeats — at most [[LocalRounds]] times, bounding a pathological nesting the way [[NestingLimit]]
    * bounds a self-returning builder. The dry paint paints only the way down to the unresolved local nodes: every
    * subtree without one is stubbed out by [[DryStub.around]] — laid out as itself, painted as nothing — so a widget
    * whose render adjusts caller-owned state (a scroll offset, a cached layout) runs once per frame, not twice, unless
    * it is an ancestor of a local node. A local node the dry paint never reached (scrolled out of a scroll view's
    * window, on a hidden tab) stays unresolved and falls back to building at render time. A tree with no local node
    * costs nothing extra: no dry paint, no second round. The analysis is the last round's, so it describes the tree
    * returned.
    */
  def resolveFrame(element: Element, size: Size, dryPaint: Element => Unit): ResolvedFrame =
    var round = Round(first = true)
    var tree  = resolveAt(element, size, NestingLimit, round, Some(FocusScope.Unscoped))
    var count = 0
    while round.pending > 0 && count < LocalRounds do
      val areas = LocalProbe.measure(dryPaint(DryStub.around(tree)))
      round = Round(first = false, areas = areas, next = round.next)
      tree = resolveAt(tree, size, NestingLimit, round, Some(FocusScope.Unscoped))
      count += 1
    ResolvedFrame(tree, round.collector.result)

  /** One walk's bookkeeping. `first` is the ordinary resolution of a freshly evaluated view, which builds every node it
    * meets; a later round keeps what earlier rounds built and only fills in the local nodes `areas` now has a size for.
    */
  private final class Round(val first: Boolean, val areas: Map[Int, Size] = Map.empty, var next: Int = 0):
    /** Unresolved local nodes this walk left behind with a fresh probe, which the next dry paint will measure. */
    var pending: Int = 0

    /** What this walk collected for the focus pass and the version watch. */
    val collector: FrameCollector = FrameCollector()

    def probe(node: ResponsiveElement): ResponsiveElement =
      val token = next
      next += 1
      pending += 1
      node.probedAs(token)

  /** `fuel` bounds *consecutive* responsive nesting — a builder that returns another responsive node — and resets at
    * every ordinary node, so it never constrains how deep a tree may be. Exhausting it leaves the node unresolved,
    * degrading to its own render-time fallback; a builder that returns itself is a programmer error that should show up
    * as odd focus behavior, not as a hung render thread.
    *
    * `scope` is what the collection needs from the ancestors: see [[FrameCollector.visit]].
    */
  private def resolveAt(element: Element, size: Size, fuel: Int, round: Round, scope: Option[FocusScope]): Element =
    val inside = round.collector.visit(element, scope)
    element match
      case responsive: ResponsiveElement =>
        responsive.resolved match
          // a later round: what an earlier one built stays, and only local nodes inside it can still need work
          case Some(branch) if !round.first =>
            responsive.resolvedAt(resolveAt(branch, size, fuel - 1, round, inside))
          case _ if fuel <= 0               => responsive
          case _ if responsive.local        =>
            round.areas.get(responsive.probe).filter(_ => !round.first) match
              case Some(area)                                  => branchOf(responsive, area, size, fuel, round, inside)
              case None if responsive.probe < 0 || round.first => round.probe(responsive)
              // probed, but the dry paint never reached it: leave it to its render-time fallback
              case None                                        => responsive
          case _                            => branchOf(responsive, size, size, fuel, round, inside)
      case lazyList: LazyListElement     =>
        val built = if round.first || lazyList.window.isEmpty then lazyList.builtFor(size.height) else lazyList
        withResolved(built, built.children.map(resolveAt(_, size, NestingLimit, round, inside)))
      case other                         =>
        withResolved(other, other.children.map(resolveAt(_, size, NestingLimit, round, inside)))

  /** `node` with `resolved` as its children — or `node` itself when resolving changed none of them, which for a tree
    * with no responsive node or lazy list in a subtree is every node of that subtree: rebuilding a container around the
    * very children it already holds would only allocate a copy of it.
    */
  private def withResolved(node: Element, resolved: Seq[Element]): Element =
    val current = node.children.iterator
    val fresh   = resolved.iterator
    var same    = true
    while same && current.hasNext && fresh.hasNext do same = current.next() eq fresh.next()
    if same && !current.hasNext && !fresh.hasNext then node else node.withChildren(resolved)

  /** `responsive` holding the branch it builds at `at`, resolved in turn against the frame's `size`. */
  private def branchOf(
      responsive: ResponsiveElement,
      at: Size,
      size: Size,
      fuel: Int,
      round: Round,
      scope: Option[FocusScope],
  ): Element =
    val branch = resolveAt(responsive.build(at), size, fuel - 1, round, scope)
    // suppression runs in TuiApp.effectiveView, before this pass, so a branch that did not exist yet when the modal
    // was composed has to inherit its node's suppression here or it rejoins the tab order and the router. The branch
    // was collected unsuppressed, which is the same answer: an inert node already cut the focus walks off below it,
    // and suppression changes no node's watched states.
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

  /** Whether a dry paint is in progress on this thread — a paint whose output is thrown away. */
  def measuring: Boolean = FrameContext.current.localSizes.isDefined

  /** Records that the local node holding `token` was handed an area of `size`; the first report wins. */
  def record(token: Int, size: Size): Unit =
    FrameContext.current.localSizes.foreach(areas =>
      if token >= 0 && !areas.contains(token) then areas.update(token, size)
    )

  /** Runs `paint` as a dry paint and returns the sizes the local nodes in it reported. */
  def measure(paint: => Unit): Map[Int, Size] =
    val context  = FrameContext.current
    val previous = context.localSizes
    val areas    = scala.collection.mutable.HashMap.empty[Int, Size]
    context.localSizes = Some(areas)
    try paint
    finally context.localSizes = previous
    areas.toMap

/** A subtree the container-query pre-pass has no reason to paint: [[DryStub.around]] puts one in place of every subtree
  * holding no unresolved local query, so the dry paint lays the stub out exactly as the subtree (constraint, claim and
  * measurement all forward to it) and paints nothing — and no widget in it runs its render, or touches caller-owned
  * state, a second time in the frame. Built and thrown away within one dry paint, on the render thread.
  */
private[dsl] final case class DryStubElement(inner: Element) extends DecoratingElement:
  type Self = DryStubElement
  override def children: Seq[Element]                             = Seq.empty
  def widget: io.worxbend.tui.core.Widget                         = (_, _) => ()
  private[dsl] def withProps(props: ElementProps): DryStubElement = copy(inner = inner.withProps(props))

private[dsl] object DryStub:

  /** `tree` with every subtree that holds no unresolved local query replaced by a [[DryStubElement]]; one walk. */
  def around(tree: Element): Element = visit(tree)._1

  /** The node as the dry paint should see it, and whether it is, or holds, a local query still to be measured. */
  private def visit(node: Element): (Element, Boolean) =
    node match
      case responsive: ResponsiveElement if responsive.local && responsive.resolved.isEmpty => (node, true)
      case _                                                                                =>
        val visited = node.children.map(visit)
        if visited.exists(_._2) then (node.withChildren(visited.map(_._1)), true) else (DryStubElement(node), false)
