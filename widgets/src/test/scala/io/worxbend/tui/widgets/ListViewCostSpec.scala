package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Line, Text}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.AbstractSeq
import scala.collection.mutable

/** A [[ListView]] must cost what the viewport costs once it has seen its items once.
  *
  * Work is asserted, not time: a sequence that counts how many of its elements were pulled is stable on a loaded CI
  * box, where a wall-clock threshold is not. The first frame over a fresh item sequence is allowed to walk it — the
  * widget has to learn whether every row is one line high — and every frame after that over the *same* sequence must
  * not.
  */
final class ListViewCostSpec extends AnyFunSuite:

  /** An indexed sequence that records how many elements were read, by index or through an iterator, and whose contents
    * can be changed under the same reference — the shape of a caller-defined sequence over a buffer it owns.
    */
  private final class CountingItems(initial: Seq[String | Line | Text])
      extends AbstractSeq[String | Line | Text]
      with IndexedSeq[String | Line | Text]:
    private val underlying                                   = mutable.ArrayBuffer.from(initial)
    var touched: Int                                         = 0
    def length: Int                                          = underlying.length
    def apply(index: Int): String | Line | Text              =
      touched += 1
      underlying(index)
    override def iterator: Iterator[String | Line | Text]    = underlying.iterator.map { item =>
      touched += 1
      item
    }
    def update(index: Int, item: String | Line | Text): Unit = underlying(index) = item
    def append(item: String | Line | Text): Unit             = underlying += item

  private val viewport = 10

  private def hundredThousand: CountingItems = CountingItems(Vector.tabulate(100_000)(index => s"item $index"))

  test("after the first frame a 100 000-item list is rendered touching only the viewport"):
    val items = hundredThousand
    val state = ListState(selected = Some(99_999))
    val first = rendered(ListView(items), state, 20, viewport)
    assert(trimmedLines(first).last == "> item 99999")
    items.touched = 0
    // the DSL builds a fresh ListView value every frame; the item sequence is what stays the same
    val again = rendered(ListView(items), state, 20, viewport)
    assert(trimmedLines(again).last == "> item 99999")
    assert(items.touched <= viewport + 2, s"an unchanged list read ${items.touched} items to draw $viewport rows")

  test("scrolling an unchanged list stays proportional to the viewport"):
    val items = hundredThousand
    val state = ListState(selected = Some(50_000))
    val _     = rendered(ListView(items), state, 20, viewport)
    items.touched = 0
    (1 to 20).foreach { _ =>
      state.selectNext(items.size)
      val _ = rendered(ListView(items), state, 20, viewport)
    }
    assert(items.touched <= 20 * (viewport + 2), s"twenty one-row moves read ${items.touched} items")
    assert(state.selected.contains(50_020))

  test("heightAt on the same list is answered without a second walk"):
    val items = hundredThousand
    val view  = ListView(items)
    assert(view.heightAt(20).contains(100_000))
    items.touched = 0
    assert(view.heightAt(20).contains(100_000))
    assert(items.touched == 0, s"the second heightAt read ${items.touched} items")

  test("a new item sequence is measured afresh, so a multi-row item moves the scroll arithmetic to rows"):
    val state = ListState(selected = Some(2))
    val plain = Vector[String | Line | Text]("one", "two", "three")
    assert(trimmedLines(rendered(ListView(plain), state, 12, 3)) == Seq("  one", "  two", "> three"))
    val tall  = Vector[String | Line | Text]("one", Text(Seq(Line.raw("two"), Line.raw("two-sub"))), "three")
    // same state, different sequence: the cached "every row is one line" answer must not survive the swap
    assert(trimmedLines(rendered(ListView(tall), state, 12, 3)) == Seq("  two", "  two-sub", "> three"))
    assert(state.offset == 1)

  test("an item that changed height under the same sequence is noticed when it is drawn"):
    val state = ListState(selected = Some(2))
    val items = CountingItems(Seq("one", "two", "three"))
    assert(trimmedLines(rendered(ListView(items), state, 12, 3)) == Seq("  one", "  two", "> three"))
    items(1) = Text(Seq(Line.raw("two"), Line.raw("two-sub")))
    assert(trimmedLines(rendered(ListView(items), state, 12, 3)) == Seq("  two", "  two-sub", "> three"))
    assert(state.offset == 1)

  test("an item appended to the same sequence is counted"):
    val state = ListState(selected = Some(2))
    val items = CountingItems(Seq("one", "two", "three"))
    val _     = rendered(ListView(items), state, 12, 3)
    items.append("four")
    state.selectLast(items.size)
    assert(trimmedLines(rendered(ListView(items), state, 12, 3)) == Seq("  two", "  three", "> four"))
