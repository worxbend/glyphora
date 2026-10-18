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

/** Resolves [[ResponsiveElement]] nodes against the frame size, before the focus pass runs.
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

  /** A copy of `element` with every responsive node holding the branch it builds at `size`, top-down. */
  def resolve(element: Element, size: Size): Element = resolveAt(element, size, NestingLimit)

  /** `fuel` bounds *consecutive* responsive nesting — a builder that returns another responsive node — and resets at
    * every ordinary node, so it never constrains how deep a tree may be. Exhausting it leaves the node unresolved,
    * degrading to its own render-time fallback; a builder that returns itself is a programmer error that should show up
    * as odd focus behavior, not as a hung render thread.
    */
  private def resolveAt(element: Element, size: Size, fuel: Int): Element =
    element match
      case responsive: ResponsiveElement =>
        if fuel <= 0 then responsive
        else
          val branch = resolveAt(responsive.build(size), size, fuel - 1)
          // suppression runs in TuiApp.effectiveView, before this pass, so a branch that did not exist yet when the
          // modal was composed has to inherit its node's suppression here or it rejoins the tab order and the router
          responsive.resolvedAt(if responsive.props.inert then FocusPass.suppressFocus(branch) else branch)
      case lazyList: LazyListElement     =>
        val built = lazyList.builtFor(size.height)
        built.withChildren(built.children.map(resolveAt(_, size, NestingLimit)))
      case other                         =>
        other.withChildren(other.children.map(resolveAt(_, size, NestingLimit)))

  private val NestingLimit: Int = 16
