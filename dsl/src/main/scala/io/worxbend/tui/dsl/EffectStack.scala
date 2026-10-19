package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Effect, Rect, Widget}
import io.worxbend.tui.runtime.Frame

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** The post-render [[Effect]]s a [[TuiApp]] has started and not yet finished, behind [[TuiApp.runEffect]].
  *
  * An effect is a pure function of how long it has been running, so all this has to keep is the start time of each one.
  * `now` is the clock, in nanoseconds, as a parameter rather than a direct `System.nanoTime()` call, so a test can step
  * an effect through its phases without waiting for wall-clock time to pass.
  *
  * An effect can be started under a key: starting another under the same key replaces the running one, timed from the
  * new start, so a binding pressed twenty times in a row plays one flash rather than stacking twenty.
  *
  * A keyed effect can also be *scoped* to an element: `start(key, effect, scope)` confines it to the rect the node
  * declared with `.key(scope)` was painted into, resolved afresh on every frame from the host's [[NodeBounds]] — so a
  * flash follows its panel when the layout moves, and a frame on which the node is not on screen paints nothing of it.
  *
  * One of these belongs to one [[TuiApp]] instance and every method runs on that app's render thread — `start` from an
  * event handler, `applyTo` from the render pass, `prune` from the tick stage — so its field is unsynchronised.
  */
private[dsl] final class EffectStack(now: () => Long):

  private var running: List[EffectStack.Running] = Nil

  /** Starts `effect` over the whole frame, timed from this moment. */
  def start(effect: Effect): Unit =
    running = EffectStack.Running(effect, now(), None) :: running

  /** Starts `effect` over the whole frame under `key`, replacing whatever effect is already running under it. */
  def start(key: String, effect: Effect): Unit =
    running = EffectStack.Running(effect, now(), Some(key)) :: running.filterNot(_.key.contains(key))

  /** Starts `effect` under `key`, replacing whatever is running under it, confined to the node keyed `scope`. */
  def start(key: String, effect: Effect, scope: String): Unit =
    running = EffectStack.Running(effect, now(), Some(key), Some(scope)) :: running.filterNot(_.key.contains(key))

  /** Whether a running effect is scoped to an element — the host must record bounds for it to resolve. */
  def hasScoped: Boolean = running.exists(_.scope.isDefined)

  def isEmpty: Boolean = running.isEmpty

  /** How many effects are running — keyed and unkeyed alike. */
  def activeCount: Int = running.length

  /** Applies every running effect over the frame that was just composed: a whole-frame one over the frame's area, a
    * scoped one over the rect `boundsOf(scope)` answers, clipped to the frame — or not at all when it answers `None`.
    */
  def applyTo(frame: Frame, boundsOf: String => Option[Rect] = _ => None): Unit =
    if running.nonEmpty then
      val at = now()
      running.foreach { entry =>
        val elapsed = (at - entry.started).nanos
        entry.scope match
          case None        => frame.applyEffect(entry.effect, elapsed)
          case Some(scope) =>
            boundsOf(scope).map(_.intersection(frame.area)).filterNot(_.isEmpty).foreach { rect =>
              frame.renderWidget((area, buffer) => entry.effect.process(elapsed, buffer, area), rect)
            }
      }

  /** Drops finished effects; `true` when any were dropped, which is the caller's cue to schedule one more redraw so the
    * un-effected frame is what stays on screen.
    */
  def prune(): Boolean =
    if running.isEmpty then false
    else
      val at              = now()
      val (done, ongoing) = running.partition(entry => entry.effect.isDone((at - entry.started).nanos))
      running = ongoing
      done.nonEmpty

private[dsl] object EffectStack:

  /** One started effect: what it is, when it started on the stack's clock, the key it replaces under, if any, and the
    * element key it is confined to, if any.
    */
  final case class Running(effect: Effect, started: Long, key: Option[String], scope: Option[String] = None)

/** `inner` with `effect` applied, at `elapsed`, over exactly the cells `inner` was painted into — what
  * `element.withEffect(...)` builds. The layout extensions the wrapped node carried are lifted onto this node, as every
  * decorator does, and everything else (focus, handlers, measurement) reads through to `inner`.
  *
  * The effect runs inside the paint, straight after `inner` painted, so it sees the element's own cells and nothing a
  * later sibling or a portal draws on top. Immutable; render-thread-only like every widget paint.
  */
final case class EffectedElement(
    inner: Element,
    effect: Effect,
    elapsed: FiniteDuration,
    override val props: ElementProps = ElementProps(),
) extends DecoratingElement:
  type Self = EffectedElement

  override def debugName: String = "effect"

  def widget: Widget =
    (area, buffer) =>
      inner.widget.render(area, buffer)
      val visible = area.intersection(buffer.area)
      if !visible.isEmpty then effect.process(elapsed, buffer, visible)

  private[dsl] def withProps(props: ElementProps): EffectedElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): EffectedElement =
    copy(inner = children.headOption.getOrElse(inner))

private[dsl] object EffectedElement:

  /** `element` under `effect`, with the layout extensions it carried moved onto the wrapper. */
  def around(element: Element, effect: Effect, elapsed: FiniteDuration): EffectedElement =
    val props = element.props
    EffectedElement(
      element.withProps(props.copy(constraint = None, alignSelf = None, gridSpot = GridSpot.Flowed)),
      effect,
      elapsed,
      ElementProps(constraint = props.constraint, alignSelf = props.alignSelf, gridSpot = props.gridSpot),
    )

/** Element-scoped effects — an [[Effect]] confined to one element's painted cells. */
extension [E <: Element](element: E)

  /** `effect` played over this element's cells only, at `elapsed` into it: flash this panel, dissolve this toast, sweep
    * in this dialog. The caller owns the clock — pass the time since the effect should have started — which is what
    * keeps a frame of it reproducible in a test. For an effect timed from the element's first appearance, use the
    * overload without `elapsed`.
    */
  def withEffect(effect: Effect, elapsed: FiniteDuration): EffectedElement =
    EffectedElement.around(element, effect, elapsed)

  /** `effect` played over this element's cells only, timed from the first frame that reaches this call: the element
    * flashes when it appears and stays as painted once the effect is done. A hook — it follows the ordering rule on
    * [[ViewState]] and may only be called while a view is evaluated. The animation clock is read with a subscription,
    * and so asks for ticks, only while the effect is still running.
    */
  def withEffect(effect: Effect)(using scope: ReactiveScope): EffectedElement =
    val started = useMemo()(AnimationClock.peek)
    val sofar   = AnimationClock.peek - started
    val elapsed = if effect.isDone(sofar) then sofar else AnimationClock.elapsed(using scope) - started
    EffectedElement.around(element, effect, elapsed)
