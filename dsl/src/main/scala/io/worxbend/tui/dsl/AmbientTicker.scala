package io.worxbend.tui.dsl

import io.worxbend.tui.runtime.{Async, Cancelable}

import scala.concurrent.duration.FiniteDuration

/** The repeating tick a run started for itself and the interval it is running at, kept as one value because the two are
  * only ever read and written together: retargeting compares the wanted interval against the running one and swaps the
  * cancelable in the same step, so neither is ever observed without the other.
  */
private[dsl] final case class AmbientTick(interval: FiniteDuration, cancelable: Cancelable)

/** The negotiation between what a frame renders and the repeating tick a run with no `config.tickRate` arms for itself
  * — retargeted after every frame, cancelled on the way out. [[retarget]] explains why the negotiation exists at all.
  */
private[dsl] final class AmbientTicker(
    effects: EffectStack,
    toasts: ToastStack,
    redraw: () => Unit,
    pendingKeys: PendingKeys = PendingKeys(() => System.nanoTime()),
    sequenceTimeout: () => FiniteDuration = () => FiniteDuration(1, "s"),
):

  /** Turns this run's own repeating tick on, off, or onto a different interval, according to what the frame just
    * composed actually needs.
    *
    * Why this exists: the ambient [[AnimationClock]] is what makes `spinner`, `marquee`, `indeterminateBar` and a timed
    * `Effect` animate with no counter to thread through — but the ticks that advance it used to come only from a
    * globally configured `config.tickRate`. An app that rendered a spinner and set no tick rate got a frozen spinner
    * and no diagnostic at all. Meanwhile an app that *did* set one paid for a tick every interval forever, whether or
    * not anything on screen was moving.
    *
    * Both are the same missing negotiation, and the render pass already knows the answer: every ambient animation reads
    * the clock, and each read says how often it needs one. So after each frame this asks for the shortest interval
    * anything on it wanted — plus a tick for the live toasts and post-render effects, which age in clock time and are
    * not part of the tree — and retargets a single `Async.every`. Nothing animated on the frame means no ticker at all.
    *
    * An app that configured a `tickRate` keeps being driven by the runner, exactly as before: this does nothing at all
    * for such a run, so `onTick` and the frame cadence of every existing app are untouched. `onTick` is deliberately
    * *not* called from the ambient tick either — it is documented as requiring a `config.tickRate`, and an app that
    * never asked for ticks should not suddenly start receiving them.
    *
    * Runs on the render thread, at the end of the render pass, which is where `Async.every` must be armed for its body
    * to come back to this loop.
    *
    * One limit worth knowing. A tick arrives as a task queued back to the render loop, and the loop notices it when its
    * current `readEvent` returns. With `JLine3Backend` — and with `HeadlessBackend`, since 0.19.0 — the queued task
    * `wake()`s the backend and the read returns at once, so an ambient animation advances as soon as its interval
    * fires. Only a custom `Backend` that keeps the trait's no-op `wake` is bound by its poll: such a loop with no
    * configured `tickRate` blocks on input for up to 100ms between draining that queue, and an ambient animation
    * advances at that granularity however short an interval it asked for. That is the difference between "the spinner
    * spins" and "the spinner is perfectly smooth"; an app on such a backend that wants the second still sets
    * `config.tickRate`.
    */
  def retarget(run: RunState): Unit =
    if !run.runnerTicks then
      // the toasts and the post-render effects age in clock time and are not part of the tree, so they ask for ticks
      // separately; the ticker runs at whichever of the two demands is the shorter
      // ...and so does a signal in flight under `animateTo`, which is sampled on the same tick
      val ageing =
        // a pending key-sequence prefix times out on the tick, so it borrows ticks for as long as it waits
        if !effects.isEmpty || toasts.isLive || SignalMotion.isActive || pendingKeys.isPending then
          Some(AnimationClock.DefaultInterval)
        else None
      // the intro is not in the tree either, and it is the one thing here that an app with no `tickRate` of its own
      // cannot animate without ticks; the demand goes away with the frame that ends it
      val intro  = Option.when(run.splash.isActive)(SplashPlayer.TickRate)
      val wanted = (AnimationClock.frameDemand.toList ++ ageing.toList ++ intro.toList).minOption
      if wanted != run.ambient.map(_.interval) then
        run.ambient.foreach(_.cancelable.cancel())
        run.ambient = wanted.map(interval => AmbientTick(interval, Async.every(interval)(tick(run))))

  /** Cancels the run's ambient ticker when one is armed. This run started it, so this run stops it: an uncancelled
    * `Async.every` is a process-lifetime daemon.
    */
  def cancel(run: RunState): Unit =
    run.ambient.foreach(_.cancelable.cancel())
    run.ambient = None

  /** One ambient tick: advance the clock, age the toasts and the post-render effects, and ask for the frame that shows
    * the result.
    *
    * The redraw is unconditional rather than conditional on something having changed, because the whole reason this
    * ticker is running is that the last frame contained an animation whose next position is a function of the clock
    * alone — and a `Signal` set to an equal value notifies nobody, so an "only if something changed" test would leave
    * that animation frozen between the two frames where its glyph happens to repeat.
    */
  private def tick(run: RunState): Unit =
    AnimationClock.advanceUnlessPinned()
    SignalMotion.step()
    toasts.age()
    val _ = effects.prune()
    // the intro is driven from here for a run that configured no `tickRate`; `advance` is what ends it, and the redraw
    // below is the frame that shows the real view
    val _ = run.splash.advance()
    val _ = pendingKeys.expire(sequenceTimeout())
    redraw()
