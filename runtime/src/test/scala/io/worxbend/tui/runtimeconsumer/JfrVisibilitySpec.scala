package io.worxbend.tui.runtimeconsumer

import org.scalatest.funsuite.AnyFunSuite
import scala.compiletime.testing.typeCheckErrors

/** JFR records stay externally readable by event name without exposing writable event instances to Scala callers. */
final class JfrVisibilitySpec extends AnyFunSuite:
  test("JFR's mutable event carriers are runtime implementation details"):
    assert(typeCheckErrors("new io.worxbend.tui.runtime.FrameDrawnEvent").nonEmpty)
    assert(typeCheckErrors("new io.worxbend.tui.runtime.EventRoutedEvent").nonEmpty)
    assert(typeCheckErrors("new io.worxbend.tui.runtime.FocusChangedEvent").nonEmpty)
    assert(typeCheckErrors("new io.worxbend.tui.runtime.AsyncAdmittedEvent").nonEmpty)
    assert(typeCheckErrors("new io.worxbend.tui.runtime.AsyncRejectedEvent").nonEmpty)
