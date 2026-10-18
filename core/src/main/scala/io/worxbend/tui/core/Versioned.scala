package io.worxbend.tui.core

/** A caller-owned mutable state that counts its own mutations.
  *
  * Why this exists. The interactive widgets keep their selection, scroll offset and text in plain mutable objects the
  * caller owns (`ListState`, `TextInputState`, `TreeState`, …), and a plain object cannot tell the reactive layer it
  * changed. A mutation made outside the event path — a background result written into a list on the render thread —
  * used to leave the previous frame on screen until something unrelated repainted. `version` is the smallest thing that
  * lets an observer notice instead: a number that moves on every mutation, which the DSL compares against the value it
  * saw when it last painted the state and turns into a frame when the two differ.
  *
  * The interface lives in `tui-core` because the states live in `tui-widgets`, which cannot see `tui-runtime`; the
  * observer that turns a moved version into a redraw lives above both.
  *
  * '''Contract.''' `version` never decreases, and every public method or setter that changes what the state holds
  * raises it. A setter raises it even when handed the value already there — telling the two apart would cost a
  * comparison per write for nothing — while a method that finds nothing to do (a page move with paging off) may leave
  * it alone. Two reads with nothing in between return the same number. A composite state (a dropdown wrapping a menu)
  * answers the sum of its parts' versions, which is monotone for the same reason each part is.
  *
  * '''Threads.''' The states are render-thread-only, and so is `version`; it is a plain field, not an atomic.
  */
trait Versioned:

  /** The number of mutations seen so far, or any monotone stand-in for it. */
  def version: Long

/** The counter most states mix in: [[version]] is a field, and [[touch]] is what each mutating method calls. */
trait VersionedState extends Versioned:

  private var mutations: Long = 0L

  final def version: Long = mutations

  /** Records one mutation. Call it from every public method or setter that changes what the state holds. */
  protected final def touch(): Unit = mutations += 1
