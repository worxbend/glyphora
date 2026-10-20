package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, BufferExport, Cell, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.{Deadline, DurationInt}

final class PeerPromptSecuritySpec extends AnyFunSuite with PilotFixture:
  private def cells(frame: Buffer): Vector[Cell] =
    (for
      y <- frame.area.y until frame.area.bottom
      x <- frame.area.x until frame.area.right
    yield frame.get(x, y)).toVector

  test("invisible password frames preserve every symbol and style through typing, movement and selection"):
    val backend = HeadlessBackend(Size(40, 4))
    val answer  = AtomicReference[Option[String]](None)
    val pilot   = startPilot(backend) {
      answer.set(Prompt.password("Token")(using PromptSession.on(backend)))
      Right(())
    }
    pilot.waitForIdle()
    val empty   = cells(pilot.lastFrame)
    val ansi    = BufferExport.ansi(pilot.lastFrame)
    val html    = BufferExport.html(pilot.lastFrame)
    pilot.assertNoCursor()
    for input <- Seq("x", " e\u0301密🔒", "a" * 80) do
      pilot.typeText(input).waitForIdle()
      assert(cells(pilot.lastFrame) == empty)
      assert(BufferExport.ansi(pilot.lastFrame) == ansi)
      assert(BufferExport.html(pilot.lastFrame) == html)
      pilot.assertNoCursor()
    for chord <- Seq("home", "shift+end", "ctrl+a", "left", "end") do
      pilot.press(chord).waitForIdle()
      assert(cells(pilot.lastFrame) == empty)
      pilot.assertNoCursor()
    pilot.press("enter")
    assert(pilot.awaitTermination())
    assert(answer.get.contains("x e\u0301密🔒" + "a" * 80))

  test("an explicit bullet password mask may disclose grapheme count but never source text"):
    val backend = HeadlessBackend(Size(40, 4))
    val answer  = AtomicReference[Option[String]](None)
    val pilot   = startPilot(backend) {
      answer.set(Prompt.password("Token", mask = Some("•"))(using PromptSession.on(backend)))
      Right(())
    }
    pilot.waitForIdle().typeText("e\u0301密🔒").waitForIdle()
    assert(pilot.screenText.contains("•••"))
    assert(!pilot.screenText.contains("e\u0301密🔒"))
    pilot.press("home", "ctrl+u", "ctrl+w", "alt+backspace", "ctrl+a", "ctrl+x").waitForIdle()
    assert(backend.clipboardContents.isEmpty)
    assert(!BufferExport.ansi(pilot.lastFrame).contains("e\u0301密🔒"))
    pilot.press("enter")
    assert(pilot.awaitTermination())
    assert(answer.get.contains("e\u0301密🔒"))

  for
    hidden <- Seq(true, false)
    chord  <- Seq("ctrl+u", "ctrl+w", "alt+backspace")
  do
    test(s"secret backward kill $chord at Home is a no-op (hidden=$hidden)"):
      val backend = HeadlessBackend(Size(40, 4))
      val ring    = KillRing()
      val state   = TextInputState("e\u0301密 top secret", ring)
      val app     = new TuiApp:
        def view(using ReactiveScope, Theme): Element =
          val field = input(state)
          (if hidden then field.hidden else field.masked()).autofocus
      val pilot   = startPilot(backend)(app.runWith(backend))
      pilot.waitForIdle().press("home", chord).waitForIdle()
      assert(pilot.readOnRenderThread(state.value) == "e\u0301密 top secret")
      assert(pilot.readOnRenderThread(state.cursor) == 0)
      assert(pilot.readOnRenderThread(state.selection.isEmpty))
      assert(pilot.readOnRenderThread(ring.size) == 0)
      assert(backend.clipboardContents.isEmpty)
      pilot.press("ctrl+a", "ctrl+c", "ctrl+x").waitForIdle()
      assert(pilot.readOnRenderThread(state.value) == "e\u0301密 top secret")
      assert(backend.clipboardContents.isEmpty)
      pilot.press(chord).waitForIdle()
      assert(pilot.readOnRenderThread(state.value.isEmpty))
      assert(pilot.readOnRenderThread(ring.size) == 0)
      pilot.typeText("new secret").press("end", chord).waitForIdle()
      assert(pilot.readOnRenderThread(state.value.isEmpty))
      assert(pilot.readOnRenderThread(ring.size) == 0)
      pilot.interrupt()
      assert(pilot.awaitTermination())

  for consumes <- Seq("caught InterruptedException", "Thread.interrupted") do
    test(s"loader cancellation survives $consumes in the active item"):
      val backend   = HeadlessBackend(Size(40, 4))
      val entered   = CountDownLatch(1)
      val observed  = CountDownLatch(1)
      val release   = CountDownLatch(1)
      val processed = AtomicInteger(0)
      val result    = AtomicReference[Option[Seq[Int]]](Some(Seq(-1)))
      val pilot     = startPilot(backend) {
        result.set(Prompt.loader("Cancel", 1 to 10000) { n =>
          val _ = processed.incrementAndGet()
          if n == 1 then
            entered.countDown()
            if consumes == "caught InterruptedException" then
              try assert(release.await(5, TimeUnit.SECONDS))
              catch { case _: InterruptedException => () }
            else
              while !Thread.currentThread().isInterrupted && release.getCount > 0 do Thread.onSpinWait()
              assert(Thread.interrupted())
            observed.countDown()
            // Keep the current cooperative item alive until the UI retires. Even a teardown interrupt may be caught;
            // cancellation must remain durable after all interrupts have been consumed.
            val deadline = Deadline.now + 5.seconds
            var released = false
            while !released && deadline.hasTimeLeft() do
              try released = release.await(math.max(1L, deadline.timeLeft.toNanos), TimeUnit.NANOSECONDS)
              catch { case _: InterruptedException => () }
            assert(released)
            val _        = Thread.interrupted()
          n
        }(using PromptSession.on(backend)))
        Right(())
      }
      try
        assert(entered.await(5, TimeUnit.SECONDS))
        pilot.waitForIdle().interrupt()
        assert(observed.await(5, TimeUnit.SECONDS))
        pilot.waitUntil("cancelled UI teardown")(!backend.isRawMode)
        val cancelled = cells(pilot.lastFrame)
        val draws     = backend.drawCount
        assert(pilot.screenText.contains(PromptStep.Cancelled))
        assert(!pilot.screenText.contains("100%"))
        release.countDown()
        assert(pilot.awaitTermination())
        assert(result.get.isEmpty)
        assert(processed.get == 1)
        assert(cells(pilot.lastFrame) == cancelled)
        assert(backend.drawCount == draws)
      finally release.countDown()
