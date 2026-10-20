package io.worxbend.tui.widgets

import scala.collection.mutable

/** The Emacs/readline kill ring: text removed by kill commands (`Ctrl+K`, `Ctrl+U`, `Ctrl+W`, `Alt+D`), newest first,
  * for `Ctrl+Y` to yank back and `Alt+Y` to cycle through.
  *
  * Separate from the [[Clipboard]] on purpose — killing a line does not overwrite what the user copied. Consecutive
  * kills accumulate into one entry, the way readline's do, so `Ctrl+K Ctrl+K` yanks back as one piece. Holds at most
  * `capacity` entries, dropping the oldest. One ring may be shared between several editors so a kill in one field yanks
  * in another; pass the same instance to each state.
  *
  * Render-thread-only, like the editor states that write it.
  */
final class KillRing(capacity: Int = 32):
  require(capacity > 0, "a kill ring holds at least one entry")

  private val entries               = mutable.ArrayDeque.empty[String]
  private var owner: Option[AnyRef] = None

  private[widgets] def breakChain(): Unit = owner = None

  private[widgets] def continues(buffer: AnyRef): Boolean = owner.contains(buffer)

  private[widgets] def record(buffer: AnyRef, text: String, backward: Boolean, continue: Boolean): Unit =
    if continue then accumulate(text, backward) else push(text)
    owner = Some(buffer)

  /** How many entries the ring holds. */
  def size: Int = entries.size

  /** The most recent kill, the one `Ctrl+Y` yanks. */
  def newest: Option[String] = entries.headOption

  /** The entry `steps` back from the newest, wrapping round the ring. */
  def entry(steps: Int): Option[String] =
    Option.when(entries.nonEmpty)(entries(Math.floorMod(steps, entries.size)))

  /** Adds `text` as the newest entry. */
  def push(text: String): Unit =
    breakChain()
    if text.nonEmpty then
      entries.prepend(text)
      while entries.size > capacity do
        val _ = entries.removeLast()

  /** Grows the newest entry — at its end for a forward kill, at its front for a backward one — or starts one. */
  private[widgets] def accumulate(text: String, backward: Boolean): Unit =
    entries.headOption match
      case Some(newest) if text.nonEmpty => entries(0) = if backward then text + newest else newest + text
      case _                             => push(text)
