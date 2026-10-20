package io.worxbend.tui.runtime

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.{ConsoleHandler, Logger}

/** Root handlers are process-global even when each runner's lifetime belongs to a different thread. */
final class JulOwnershipSpec extends AnyFunSuite:
  private def isolated(body: (Logger, ConsoleHandler, ByteArrayOutputStream) => Unit): Unit =
    val root     = Logger.getLogger("")
    val handlers = root.getHandlers.toVector
    val out      = System.out
    val err      = System.err
    val raw      = ByteArrayOutputStream()
    val stream   = PrintStream(raw, true, UTF_8)
    System.setOut(stream)
    System.setErr(stream)
    handlers.foreach(root.removeHandler)
    val original = ConsoleHandler()
    root.addHandler(original)
    try body(root, original, raw)
    finally
      root.getHandlers.foreach(root.removeHandler)
      handlers.foreach(root.addHandler)
      System.setOut(out)
      System.setErr(err)

  for earlierFirst <- Seq(true, false) do
    test(s"overlapping runners retiring earlierFirst=$earlierFirst capture once until zero owners"):
      isolated { (root, original, raw) =>
        val rings   = Vector.fill(2)(LogRing(20))
        val started = Vector.fill(2)(CountDownLatch(1))
        val release = Vector.fill(2)(CountDownLatch(1))
        val results = Vector.fill(2)(AtomicReference[Option[Either[RunnerError, Unit]]](None))
        val threads = (0 until 2).map { index =>
          Thread(() => {
            val result = TerminalRunner(
              HeadlessBackend(Size(10, 2)),
              RunnerConfig.Default.withCaptureStdio(CaptureStdio.Console(rings(index))),
            ).run(
              handle =>
                started(index).countDown()
                assert(release(index).await(20, TimeUnit.SECONDS))
                handle.quit()
              ,
              (_, _) => EventOutcome.Ignored,
              _ => (),
            )
            results(index).set(Some(result))
          })
        }
        try
          threads(0).start()
          assert(started(0).await(10, TimeUnit.SECONDS))
          threads(1).start()
          assert(started(1).await(10, TimeUnit.SECONDS))
          root.warning("both-live")
          assert(rings(1).snapshot.count(_.message.contains("both-live")) == 1)
          val first = if earlierFirst then 0 else 1
          val last  = 1 - first
          release(first).countDown()
          threads(first).join(10000)
          assert(!threads(first).isAlive)
          root.warning("survivor-live")
          assert(raw.toString(UTF_8).isEmpty, "no raw handler may return while a capture remains")
          assert(rings(last).snapshot.count(_.message.contains("survivor-live")) == 1)
          release(last).countDown()
          threads(last).join(10000)
          assert(!threads(last).isAlive)
          assert(results.forall(_.get().contains(Right(()))))
          assert(root.getHandlers.toVector == Vector(original), "zero owners restore exact handler identities")
          root.warning("all-retired")
          assert(raw.toString(UTF_8).linesIterator.count(_.contains("all-retired")) == 1)
        finally
          release.foreach(_.countDown())
          threads.foreach(thread => if thread.isAlive then thread.join(10000))
      }

  test("same-thread nested captures return JUL to the enclosing owner and restore once"):
    isolated { (root, original, raw) =>
      val outerLines = scala.collection.mutable.ArrayBuffer.empty[String]
      val innerLines = scala.collection.mutable.ArrayBuffer.empty[String]
      val outer      = StdioCapture(_ => (), line => { outerLines += line; () })
      outer.install()
      val inner      = StdioCapture(_ => (), line => { innerLines += line; () })
      try
        inner.install()
        root.warning("inner")
        inner.restore()
        root.warning("outer")
        assert(innerLines.count(_.contains("inner")) == 1)
        assert(outerLines.count(_.contains("outer")) == 1)
        assert(raw.toString(UTF_8).isEmpty)
        outer.restore()
        inner.restore()
        outer.restore()
        assert(root.getHandlers.toVector == Vector(original))
      finally
        inner.restore()
        outer.restore()
    }

  test("captures constructed before either install still return JUL to the surviving owner"):
    isolated { (root, original, raw) =>
      val outerLines = scala.collection.mutable.ArrayBuffer.empty[String]
      val outer      = StdioCapture(_ => (), line => { outerLines += line; () })
      val inner      = StdioCapture(_ => (), _ => ())
      try
        outer.install()
        inner.install()
        inner.restore()
        root.warning("preconstructed-survivor")
        assert(raw.toString(UTF_8).isEmpty)
        assert(outerLines.count(_.contains("preconstructed-survivor")) == 1)
        outer.restore()
        assert(root.getHandlers.toVector == Vector(original))
      finally
        inner.restore()
        outer.restore()
    }

  test("overlap with a ring bridge never reinstalls raw handlers or duplicates bridge records"):
    isolated { (root, original, raw) =>
      val ring   = LogRing(20)
      val bridge = JulBridge.attach(ring)
      val outer  = StdioCapture(_ => (), _ => fail("bridge record must not be captured twice"))
      outer.install()
      val inner  = StdioCapture(_ => (), _ => fail("bridge record must not be captured twice"))
      try
        inner.install()
        outer.restore()
        root.warning("bridged-survivor")
        assert(ring.snapshot.count(_.message == "bridged-survivor") == 1)
        assert(raw.toString(UTF_8).isEmpty)
        inner.restore()
        bridge.cancel()
        assert(root.getHandlers.toVector == Vector(original))
      finally
        inner.restore()
        outer.restore()
        bridge.cancel()
    }
