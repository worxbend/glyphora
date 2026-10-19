package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.runtime.{JfrEvents, ReactiveScope}

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** `ElementHost` commits a Flight Recorder `FocusChanged` each frame focus lands somewhere new. */
final class FocusChangedEventSpec extends AnyFunSuite:

  private given ReactiveScope = ReactiveScope.untracked

  test("moving focus between two keyed buttons records one FocusChanged naming both"):
    val view: View = row(button("A")(()).key("a"), button("B")(()).key("b"))
    val host       = ElementHost()
    val area       = Rect(0, 0, 20, 1)
    val recording  = Recording()
    recording.enable(JfrEvents.FocusChangedName)
    recording.start()
    host.render(area, Buffer(area), Theme.Dark, view)
    val _          = host.focusNext()
    host.render(area, Buffer(area), Theme.Dark, view)
    host.render(area, Buffer(area), Theme.Dark, view)
    recording.stop()
    val file       = Files.createTempFile("glyphora-focus", ".jfr")
    recording.dump(file)
    recording.close()
    val moves      = RecordingFile
      .readAllEvents(file)
      .asScala
      .toVector
      .filter(_.getEventType.getName == JfrEvents.FocusChangedName)
      .map(e => (e.getInt("fromIndex"), e.getString("fromKey"), e.getInt("toIndex"), e.getString("toKey")))
    Files.deleteIfExists(file)
    assert(moves.last == (0, "a", 1, "b"))
    assert(moves.count(_._4 == "b") == 1)
