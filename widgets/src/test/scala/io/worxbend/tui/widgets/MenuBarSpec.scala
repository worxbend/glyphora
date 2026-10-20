package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Modifiers, Position, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.{line, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class MenuBarSpec extends AnyFunSuite:

  private val menus = Seq(
    MenuBarMenu("File", Seq(MenuEntry.Item("Open", Some("^O")), MenuEntry.Separator, MenuEntry.Item("Quit"))),
    MenuBarMenu("Edit", Seq(MenuEntry.Item("Undo"), MenuEntry.Item("Redo"))),
    MenuBarMenu("View", Seq(MenuEntry.Item("Zoom")), mnemonic = Some('w')),
  )

  test("titles sit side by side, each padded by a space, with the mnemonic letter underlined"):
    val buffer = rendered(MenuBar(menus), MenuBarState(), 24, 1)
    assert(line(buffer, 0) == " File  Edit  View       ")
    assert(buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Underline))
    assert(!buffer.get(2, 0).style.modifiers.hasAny(Modifiers.Underline))
    assert(buffer.get(16, 0).style.modifiers.hasAny(Modifiers.Underline)) // the w of View

  test("the open menu's title draws in the active style"):
    val state  = MenuBarState()
    state.openMenu(1)
    val buffer = rendered(MenuBar(menus), state, 24, 1)
    assert(buffer.get(7, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Reverse))

  test("mnemonics default to a title's first letter, lower-cased, and resolve to their menu"):
    assert(menus.map(_.key) == Seq(Some('f'), Some('e'), Some('w')))
    assert(MenuBar(menus).menuFor('E').contains(1))
    assert(MenuBar(menus).menuFor('x').isEmpty)

  test("titleAt and titleSpan agree with the drawn titles"):
    val bar = MenuBar(menus)
    assert(bar.titleSpan(1) == MenuBar.TitleSpan(6, 6))
    assert(bar.titleAt(7).contains(1) && bar.titleAt(0).contains(0) && bar.titleAt(19).isEmpty)
    assert(bar.widthAt(1).contains(18))

  test("state: opening resets the highlight onto the first item, moving wraps and keeps the menu open"):
    val state = MenuBarState()
    state.openMenu(0)
    assert(state.open.contains(0))
    state.menu.selectNext(menus(0).items)
    state.move(1, menus.size)
    assert(state.open.contains(1) && state.menu.selected.isEmpty)
    state.move(-2, menus.size)
    assert(state.open.contains(2))
    state.close()
    assert(state.open.isEmpty)
    state.move(1, menus.size)
    assert(state.open.isEmpty)

  test("context menu placement opens at the pointer and flips away from the edges it would cross"):
    val frame = Rect(0, 0, 40, 12)
    assert(ContextMenu.placement(Position(5, 3), 10, 4, frame) == Rect(5, 3, 10, 4))
    assert(ContextMenu.placement(Position(35, 3), 10, 4, frame) == Rect(26, 3, 10, 4))
    assert(ContextMenu.placement(Position(5, 10), 10, 4, frame) == Rect(5, 7, 10, 4))
    assert(ContextMenu.placement(Position(2, 1), 50, 20, frame) == Rect(0, 0, 40, 12))

  test("context menu state opens at a position and closes"):
    val state = ContextMenuState()
    assert(!state.isOpen)
    state.openAt(Position(3, 4))
    assert(state.at.contains(Position(3, 4)) && state.isOpen)
    state.close()
    assert(state.at.isEmpty)
