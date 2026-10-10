package glyphora.consumer

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.{EventOutcome, RenderThread, TerminalRunner}
import io.worxbend.tui.terminal.HeadlessBackend
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicBoolean

final class OwnerDispatcherSpec extends AnyFunSuite:
  test("external callbacks can target a captured owner and retired owners reject them"):
    var captured  = Option.empty[RenderThread.RenderLoop]
    var delivered = false
    val accepted  = AtomicBoolean(false)
    val result    = TerminalRunner(HeadlessBackend(Size(10, 2))).run(
      handle =>
        val owner  = RenderThread.capture()
        captured = Some(owner)
        val worker = Thread(() => { accepted.set(owner.execute { delivered = true }); () })
        worker.start()
        worker.join(5000)
        assert(!worker.isAlive)
        handle.quit()
      ,
      (_, _) => EventOutcome.Ignored,
      _ => (),
    )
    assert(result == Right(()))
    assert(accepted.get())
    assert(delivered)
    assert(!captured.get.execute(fail("retired owner executed work")))
