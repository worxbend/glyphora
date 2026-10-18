package io.worxbend.tui.dsl

import io.worxbend.tui.core.KeyEvent
import io.worxbend.tui.runtime.Signal

import scala.concurrent.duration.FiniteDuration

/** The start of a key sequence pressed so far, and the clock reading at which its last key arrived. */
private[dsl] final case class PendingPrefix(keys: Vector[KeyEvent], since: Long):

  /** The prefix as a sequence, spelled the way a spec would name it — what the status bar and the which-key popup show.
    */
  def sequence: KeySequence = KeySequence.of(keys*)

/** One app's pending key-sequence prefix: set when a key starts or extends a sequence, cleared when the sequence fires,
  * is abandoned, or waits longer than the app's `sequenceTimeout`.
  *
  * Kept in a [[Signal]] so a view that shows the prefix — `statusBar(activeBindings)`, the which-key popup — recomputes
  * when it changes. Owned by one `TuiApp`; every method must be called on its render thread, as `Signal.set` requires.
  */
private[dsl] final class PendingKeys(now: () => Long):
  val signal: Signal[Option[PendingPrefix]] = Signal(None)

  def current: Option[PendingPrefix] = signal.peek

  def isPending: Boolean = signal.peek.isDefined

  def start(keys: Vector[KeyEvent]): Unit = signal.set(Some(PendingPrefix(keys, now())))

  def clear(): Unit = if signal.peek.isDefined then signal.set(None)

  /** Drops the prefix once `timeout` has passed since its last key; `true` when it did. */
  def expire(timeout: FiniteDuration): Boolean =
    signal.peek.exists(prefix => now() - prefix.since >= timeout.toNanos) && {
      clear()
      true
    }
