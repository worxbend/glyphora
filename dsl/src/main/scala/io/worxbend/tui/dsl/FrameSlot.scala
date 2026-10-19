package io.worxbend.tui.dsl

/** A slot of this thread's [[FrameContext]] that a host arms for one render pass and the nodes painted during it write
  * into — the shape the frame's portal queue ([[PortalQueue]]) and caret register ([[CaretRegister]]) share.
  *
  * Empty until a host arms it on this thread, and again once it disarms, so a node painted outside any host — a
  * construction test calling `element.widget.render(...)` — finds nothing armed and falls back to its own behaviour.
  * Per thread because every runner owns its render thread and several can share a JVM: one runner must never read
  * another's frame state. One writer per slot, since the whole pass happens on that thread.
  *
  * @param fresh
  *   builds the empty state a pass starts with
  * @param read
  *   the field of the frame context this slot lives in
  * @param write
  *   stores a new value into that field
  */
private[dsl] final class FrameSlot[A](
    fresh: () => A,
    read: FrameContext => Option[A],
    write: (FrameContext, Option[A]) => Unit,
):

  /** The state of the pass armed on this thread, if any. */
  def armed: Option[A] = read(FrameContext.current)

  /** Arms a fresh pass on this thread, discarding whatever an earlier one left behind. */
  def arm(): Unit = write(FrameContext.current, Some(fresh()))

  /** Disarms this thread; "disarmed" and "never armed" are the same state. */
  def disarm(): Unit = write(FrameContext.current, None)

  /** Arms a fresh pass around `body` and hands it the pass's state, restoring whatever was armed before — a nested
    * host's pass included — even when `body` throws.
    */
  def around[B](body: A => B): B =
    val context  = FrameContext.current
    val previous = read(context)
    val state    = fresh()
    write(context, Some(state))
    try body(state)
    finally write(context, previous)
