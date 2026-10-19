package io.worxbend.tui.dsl

/** A per-thread slot a host arms for one render pass and the nodes painted during it write into — the shape the frame's
  * portal queue ([[PortalQueue]]) and caret register ([[CaretRegister]]) share.
  *
  * Empty until a host arms it on this thread, and again once it disarms, so a node painted outside any host — a
  * construction test calling `element.widget.render(...)` — finds nothing armed and falls back to its own behaviour.
  * Per thread because every runner owns its render thread and several can share a JVM: one runner must never read
  * another's frame state. One writer per slot, since the whole pass happens on that thread.
  *
  * @param fresh
  *   builds the empty state a pass starts with
  */
private[dsl] final class FrameSlot[A](fresh: () => A):

  private val slot: ThreadLocal[Option[A]] = ThreadLocal.withInitial(() => Option.empty[A])

  /** The state of the pass armed on this thread, if any. */
  def armed: Option[A] = slot.get()

  /** Arms a fresh pass on this thread, discarding whatever an earlier one left behind. */
  def arm(): Unit = slot.set(Some(fresh()))

  /** Disarms this thread; "disarmed" and "never armed" are the same state. */
  def disarm(): Unit = slot.remove()

  /** Arms a fresh pass around `body` and hands it the pass's state, restoring whatever was armed before — a nested
    * host's pass included — even when `body` throws.
    */
  def around[B](body: A => B): B =
    val previous = slot.get()
    val state    = fresh()
    slot.set(Some(state))
    try body(state)
    finally slot.set(previous)
