package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, StatefulWidget, Style, Versioned}

/** One drop-down of a [[MenuBar]]: its title on the bar, its entries, and the letter that opens it with `Alt`. The
  * letter defaults to the title's first character; `key` is it lower-cased, which is how it is matched.
  */
final case class MenuBarMenu(title: String, items: Seq[MenuEntry], mnemonic: Option[Char] = None):

  /** The lower-cased letter that opens this menu, if the title has one to offer. */
  def key: Option[Char] = mnemonic.orElse(title.headOption.filter(_.isLetterOrDigit)).map(_.toLower)

/** Caller-owned [[MenuBar]] state: which menu is dropped down, if any, and the highlight inside it (a [[MenuState]]).
  *
  * Render-thread-only and `Versioned`: [[version]] is its own mutation count plus the menu state's.
  */
final class MenuBarState private (initialOpen: Option[Int]) extends Versioned:

  private var openValue: Option[Int] = initialOpen
  private var mutations: Long        = 0L

  /** The highlight inside the open menu. */
  val menu: MenuState = MenuState()

  def version: Long = mutations + menu.version

  /** The menu dropped down, or `None` while the bar is closed. */
  def open: Option[Int] = openValue

  /** Drops menu `index` down with its highlight reset, so the menu settles it onto its first selectable entry. */
  def openMenu(index: Int): Unit =
    openValue = Some(math.max(0, index))
    menu.selected = None
    menu.offset = 0
    mutations += 1

  /** Folds the open menu away. */
  def close(): Unit =
    openValue = None
    mutations += 1

  /** Opens the menu `delta` titles over from the open one, wrapping among `count`; a no-op while closed. */
  def move(delta: Int, count: Int): Unit =
    openValue.filter(_ => count > 0).foreach(current => openMenu(Math.floorMod(current + delta, count)))

object MenuBarState:

  /** A closed menu bar, or one with menu `open` dropped down. */
  def apply(open: Option[Int] = None): MenuBarState = new MenuBarState(open)

/** A one-row menu bar: each title padded by a space either side, ` File  Edit  View `, its mnemonic letter in
  * `mnemonicStyle` and the open menu's title in `activeStyle`. The drop-down itself is a [[Menu]] over the open menu's
  * entries; [[dropdown]] builds it with this bar's styles, and [[titleSpan]] says where it hangs from.
  *
  * Stateless apart from the caller-owned [[MenuBarState]]; render-thread use only.
  */
final case class MenuBar(
    menus: Seq[MenuBarMenu],
    style: Style = Style.Default,
    activeStyle: Style = Style.Default.reverse,
    mnemonicStyle: Style = Style.Default.underline,
    highlightStyle: Style = Style.Default.reverse,
) extends StatefulWidget[MenuBarState]
    with Measured:

  override def widthAt(height: Int): Option[Int] =
    val _ = height
    Some(menus.map(menu => labelWidth(menu.title)).sum)

  override def heightAt(width: Int): Option[Int] =
    val _ = width
    Some(1)

  /** The first column (relative to the bar) and the width of title `index`. */
  def titleSpan(index: Int): (Int, Int) =
    (menus.take(index).map(menu => labelWidth(menu.title)).sum, menus.lift(index).fold(0)(m => labelWidth(m.title)))

  /** The menu whose title covers column `x` (relative to the bar). */
  def titleAt(x: Int): Option[Int] =
    menus.indices.find { index =>
      val (start, width) = titleSpan(index)
      x >= start && x < start + width
    }

  /** The menu `letter` opens, ignoring case. */
  def menuFor(letter: Char): Option[Int] =
    Some(menus.indexWhere(_.key.contains(letter.toLower))).filter(_ >= 0)

  /** The drop-down of menu `index`, drawn with this bar's styles. */
  def dropdown(index: Int): Menu = Menu(menus.lift(index).fold(Seq.empty[MenuEntry])(_.items), style, highlightStyle)

  def render(area: Rect, buffer: Buffer, state: MenuBarState): Unit =
    if !area.isEmpty then
      buffer.setString(area.x, area.y, " ".repeat(area.width), style)
      menus.zipWithIndex.foreach { case (menu, index) =>
        val (start, _) = titleSpan(index)
        val x          = area.x + start
        if x < area.right then
          val own = if state.open.contains(index) then style.patch(activeStyle) else style
          buffer.setString(x, area.y, s" ${menu.title} ", own, area.right - x)
          mnemonicColumn(menu).foreach { (offset, letter) =>
            val at = x + 1 + offset
            if at < area.right then buffer.setString(at, area.y, letter, own.patch(mnemonicStyle), area.right - at)
          }
      }

  private def labelWidth(title: String): Int = CharWidth.of(title) + 2

  /** Where the mnemonic letter sits in the title (display columns from its start), and the cluster drawn there. */
  private def mnemonicColumn(menu: MenuBarMenu): Option[(Int, String)] =
    menu.key.flatMap { key =>
      val clusters = CharWidth.graphemeClusters(menu.title).toVector
      val index    = clusters.indexWhere(cluster => cluster.length == 1 && cluster.head.toLower == key)
      Option.when(index >= 0)((clusters.take(index).map(CharWidth.of).sum, clusters(index)))
    }
