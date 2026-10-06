package io.worxbend.tui.widgets

import io.worxbend.tui.core.CharWidth

/** Shared editing of one line. Re-segment the whole affected line: regional-indicator pairing can propagate beyond the
  * immediate splice. The cursor has right affinity: the first boundary at or after the UTF-16 splice offset, including
  * the far side of a cluster formed across that offset. Offsets are only used to map boundaries, never to split
  * strings. State and undo ownership remain with the editor.
  */
private[widgets] object GraphemeEdit:

  def clustersOf(text: String): Vector[String] =
    CharWidth.graphemeClusters(CharWidth.withoutControls(text)).toVector

  def splice(clusters: Vector[String], from: Int, removed: Int, inserted: String): (Vector[String], Int) =
    val before = clusters.take(from).mkString
    val after  = clusters.drop(from + removed).mkString
    joined(before, CharWidth.withoutControls(inserted), after)

  /** The cursor follows `inserted`, snapping right when either neighbouring boundary disappears. */
  def joined(before: String, inserted: String, after: String): (Vector[String], Int) =
    val result = clustersOf(before + inserted + after)
    val offset = before.length + inserted.length
    var index  = 0
    var end    = 0
    while index < result.size && end < offset do
      end += result(index).length
      index += 1
    (result, index)
