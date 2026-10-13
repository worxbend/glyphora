package io.worxbend.tui.widgets

import scala.collection.mutable

/** Render-confined expansion set. Every effective mutation, including the public mutable-set API, retires cached rows.
  * No-op additions/removals keep the current view; validating it never scans either the set or the source tree.
  */
private[widgets] final class TreeExpansion[A](onChange: () => Unit) extends mutable.AbstractSet[A]:
  private val values = mutable.HashSet.empty[A]

  override def contains(value: A): Boolean = values.contains(value)
  override def iterator: Iterator[A]       = values.iterator
  override def size: Int                   = values.size

  override def addOne(value: A): this.type =
    if values.add(value) then onChange()
    this

  override def subtractOne(value: A): this.type =
    if values.remove(value) then onChange()
    this

  override def clear(): Unit =
    if values.nonEmpty then
      values.clear()
      onChange()
