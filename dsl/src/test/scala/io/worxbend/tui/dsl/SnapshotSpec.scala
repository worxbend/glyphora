package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions

import org.scalatest.funsuite.AnyFunSuite

/** Covers [[Snapshot]]: composing a view into a buffer with no runner and no terminal. */
final class SnapshotSpec extends AnyFunSuite with PilotFixture:

  test("a view renders into a buffer of the size it was given"):
    val buffer = Snapshot.render(Size(10, 1))(text("hi"))
    assert(buffer.area == io.worxbend.tui.core.Rect(0, 0, 10, 1))
    assert(BufferAssertions.line(buffer, 0) == "hi        ")

  test("a responsive view branches on the snapshot size, not on a terminal"):
    // This is the property the whole thing is for: a snapshot has to run the same responsive pass a live frame does,
    // or it renders a layout the app would never actually paint.
    val view: View = responsive(size => if size.width < 40 then text("narrow") else text("wide"))
    assert(BufferAssertions.text(Snapshot.render(Size(20, 1))(view)) == "narrow")
    assert(BufferAssertions.text(Snapshot.render(Size(80, 1))(view)) == "wide")

  test("the theme is the one that was asked for"):
    val view: View  = text("themed").styled(_ => summon[Theme].primary)
    val dark        = Snapshot.render(Size(10, 1), Theme.Dark)(view)
    val highContast = Snapshot.render(Size(10, 1), Theme.HighContrast)(view)
    assert(dark.get(0, 0).style != highContast.get(0, 0).style)

  test("wide graphemes are truncated by display width, not by character count"):
    // Three CJK ideographs are six columns; four of them do not fit in a seven-column area, and half a glyph must not
    // be drawn either.
    val buffer = Snapshot.render(Size(7, 1))(text("世界地図"))
    assert(BufferAssertions.trimmedLines(buffer).head.length <= 4)
    assert(BufferAssertions.trimmedLines(buffer).head.startsWith("世界地"))

  test("an empty area renders nothing rather than failing"):
    val buffer = Snapshot.render(Size(0, 0))(text("invisible"))
    assert(buffer.area.isEmpty)

  test("focusedKey decides which element is drawn as focused"):
    val first      = io.worxbend.tui.widgets.TextInputState()
    val second     = io.worxbend.tui.widgets.TextInputState()
    val form: View = column(input(first).key("first"), input(second).key("second"))
    val onFirst    = Snapshot.render(Size(20, 2), focusedKey = Some("first"))(form)
    val onSecond   = Snapshot.render(Size(20, 2), focusedKey = Some("second"))(form)
    // The focus cue is a style, not a glyph, so the rows have to be compared by style rather than by text.
    assert(onFirst.get(0, 0).style != onSecond.get(0, 0).style)
    assert(onFirst.get(0, 1).style != onSecond.get(0, 1).style)

  test("a key that matches nothing falls back to the first focusable rather than failing"):
    val field      = io.worxbend.tui.widgets.TextInputState()
    val form: View = column(input(field).key("only"))
    assert(
      Snapshot.render(Size(20, 1), focusedKey = Some("missing"))(form).get(0, 0).style ==
        Snapshot.render(Size(20, 1), focusedKey = Some("only"))(form).get(0, 0).style
    )

  test("a snapshot matches what a live app paints at the same size"):
    // The regression test that keeps the offline composer honest: if TuiApp.renderFrame ever grows a pass this does
    // not run, these two stop agreeing.
    val size            = Size(24, 4)
    val panelView: View = panel("Title")(column(text("one"), text("two")))
    val snapshot        = BufferAssertions.trimmedLines(Snapshot.render(size)(panelView))
    final class SnapshotApp extends TuiApp:
      def view(using ReactiveScope, Theme): Element = panelView
      def stop(): Unit                              = quit()
    val app = SnapshotApp()
    val backend = HeadlessBackend(size)
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val live    = pilot.screenLines
    app.stop()
    assert(pilot.awaitTermination())
    assert(snapshot == live)
