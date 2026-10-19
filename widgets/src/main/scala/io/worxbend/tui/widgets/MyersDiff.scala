package io.worxbend.tui.widgets

import scala.collection.mutable

/** Eugene Myers' O(ND) shortest edit script ("An O(ND) Difference Algorithm and Its Variations", 1986), over any two
  * sequences compared with `==` — lines for [[Patch.of]], words for the intra-line highlight of [[DiffView]].
  *
  * Ties are broken the way the paper's greedy forward search breaks them: when a deletion and an insertion reach the
  * same diagonal equally far, the deletion is taken first, so a replaced line reads as `-old` then `+new`, the order
  * `git diff` prints. A common prefix and suffix are matched before the search runs, which keeps the usual case — a few
  * edited lines in a long file — far from the worst-case cost. The search remembers one frontier per edit distance, so
  * memory is O(D²) in the number of differences D rather than in the length of the inputs — which is why
  * [[diffBounded]] can stop at a maximum distance: past it, the unmatched middle is answered as one deletion of the old
  * lines followed by one insertion of the new ones, a valid but not shortest script, instead of a search whose memory
  * grows with the square of the difference.
  *
  * Pure and allocation-local: safe to call from any thread.
  */
private[widgets] object MyersDiff:

  enum Op:
    case Equal, Delete, Insert

  /** One step of an edit script: `Equal` and `Delete` name an index into the old sequence, `Equal` and `Insert` one
    * into the new; the index a step has no use for is `-1`.
    */
  final case class Edit(op: Op, oldIndex: Int, newIndex: Int)

  /** The shortest edit script turning `before` into `after`, in order. */
  def diff[A](before: IndexedSeq[A], after: IndexedSeq[A]): IndexedSeq[Edit] =
    diffBounded(before, after, Int.MaxValue)._1

  /** [[diff]], giving up on a shortest script once it would take more than `maxDistance` edits: the answer is then the
    * common prefix and suffix as `Equal`, and everything between them deleted and inserted, with `false` beside it.
    * `true` means the script is a shortest one.
    */
  def diffBounded[A](before: IndexedSeq[A], after: IndexedSeq[A], maxDistance: Int): (IndexedSeq[Edit], Boolean) =
    var prefix                   = 0
    while prefix < before.size && prefix < after.size && before(prefix) == after(prefix) do prefix += 1
    var suffix                   = 0
    while suffix < before.size - prefix && suffix < after.size - prefix &&
      before(before.size - 1 - suffix) == after(after.size - 1 - suffix)
    do suffix += 1
    def shifted(index: Int): Int = if index < 0 then -1 else index + prefix
    val oldMiddle                = before.slice(prefix, before.size - suffix)
    val newMiddle                = after.slice(prefix, after.size - suffix)
    val found                    = search(oldMiddle, newMiddle, maxDistance)
    val middle                   = found
      .getOrElse(oldMiddle.indices.map(Edit(Op.Delete, _, -1)) ++ newMiddle.indices.map(Edit(Op.Insert, -1, _)))
      .map(edit => edit.copy(oldIndex = shifted(edit.oldIndex), newIndex = shifted(edit.newIndex)))
    val head                     = (0 until prefix).map(index => Edit(Op.Equal, index, index))
    val tail                     = (0 until suffix).map { step =>
      Edit(Op.Equal, before.size - suffix + step, after.size - suffix + step)
    }
    (head ++ middle ++ tail, found.isDefined)

  /** The greedy forward search and its backtrack, with the frontier `v` indexed by diagonal `k = x - y`; `None` once
    * the distance passes `maxDistance`.
    */
  private def search[A](before: IndexedSeq[A], after: IndexedSeq[A], maxDistance: Int): Option[IndexedSeq[Edit]] =
    val n       = before.size
    val m       = after.size
    val max     = n + m
    val offset  = max + 1
    val v       = Array.fill(2 * max + 3)(0)
    // trace(d) is the frontier as it stood when the search for distance d began, kept for diagonals -d-1..d+1 only
    val trace   = mutable.ArrayBuffer.empty[Array[Int]]
    var d       = 0
    var reached = false
    while !reached && d <= maxDistance do
      trace += v.slice(offset - d - 1, offset + d + 2)
      var k = -d
      while !reached && k <= d do
        var x = if k == -d || (k != d && v(offset + k - 1) < v(offset + k + 1)) then v(offset + k + 1)
        else v(offset + k - 1) + 1
        var y = x - k
        while x < n && y < m && before(x) == after(y) do
          x += 1
          y += 1
        v(offset + k) = x
        reached = x >= n && y >= m
        k += 2
      if !reached then d += 1
    Option.when(reached)(backtrack(trace, n, m))

  private def backtrack(trace: mutable.ArrayBuffer[Array[Int]], n: Int, m: Int): IndexedSeq[Edit] =
    val edits = mutable.ArrayBuffer.empty[Edit]
    var x     = n
    var y     = m
    var d     = trace.size - 1
    while d >= 0 do
      val frontier        = trace(d)
      def at(k: Int): Int = frontier(k + d + 1)
      val k               = x - y
      val previousK       = if k == -d || (k != d && at(k - 1) < at(k + 1)) then k + 1 else k - 1
      val previousX       = at(previousK)
      val previousY       = previousX - previousK
      while x > previousX && y > previousY do
        edits += Edit(Op.Equal, x - 1, y - 1)
        x -= 1
        y -= 1
      if d > 0 then
        if x == previousX then edits += Edit(Op.Insert, -1, previousY)
        else edits += Edit(Op.Delete, previousX, -1)
      x = previousX
      y = previousY
      d -= 1
    edits.reverseIterator.toIndexedSeq
