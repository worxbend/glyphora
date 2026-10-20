package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

/** A persisted signal is an ordinary `Signal` whose value is loaded from a file at creation and written back, off the
  * render thread, whenever it changes. No runner is registered here, so the test thread plays the render thread and
  * drains the continuation queue by hand, the way `AsyncSpec` does.
  */
final class PersistedSpec extends AnyFunSuite:

  private def encode(value: (String, Int)): String                = s"${value._1}|${value._2}"
  private def decode(text: String): Either[String, (String, Int)] =
    text.split('|') match
      case Array(name, count) => count.toIntOption.map(name -> _).toRight(s"'$count' is not a count")
      case _                  => Left(s"'$text' is not name|count")

  private def pumpUntil(deadlineMillis: Long)(done: => Boolean): Unit =
    val end = System.currentTimeMillis() + deadlineMillis
    while !done && System.currentTimeMillis() < end do
      RenderThread.drainPending()
      Thread.sleep(2)
    RenderThread.drainPending()

  private def withTempDir(body: Path => Unit): Unit =
    val dir = Files.createTempDirectory("glyphora-persisted")
    try body(dir)
    finally
      // deepest paths first, so a nested directory is empty by the time its own turn comes
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => { val _ = Files.deleteIfExists(p) })

  test("a missing file yields the initial value and writes nothing until the signal changes"):
    withTempDir { dir =>
      val path   = dir.resolve("state.txt")
      val signal = Persisted.signal(path, "fresh" -> 0)(encode, decode)
      assert(signal.peek == ("fresh", 0))
      pumpUntil(200)(false)
      assert(!Files.exists(path))
    }

  test("a change is written off-thread and a later instance reloads it"):
    withTempDir { dir =>
      val path  = dir.resolve("nested").resolve("state.txt")
      val first = Persisted.signal(path, "fresh" -> 0)(encode, decode)
      first.set("saved" -> 7)
      pumpUntil(3000)(Files.exists(path) && Files.readString(path) == "saved|7")
      assert(Files.readString(path) == "saved|7")

      val second = Persisted.signal(path, "fresh" -> 0)(encode, decode)
      assert(second.peek == ("saved", 7))
    }

  test("rapid changes end with the newest value on disk"):
    withTempDir { dir =>
      val path   = dir.resolve("state.txt")
      val signal = Persisted.signal(path, "n" -> 0)(encode, decode)
      (1 to 50).foreach(i => signal.set("n" -> i))
      pumpUntil(3000)(Files.exists(path) && Files.readString(path) == "n|50")
      assert(Files.readString(path) == "n|50")
    }

  test("a file that does not decode falls back to the initial value rather than failing"):
    withTempDir { dir =>
      val path   = dir.resolve("state.txt")
      Files.writeString(path, "garbage")
      val signal = Persisted.signal(path, "fresh" -> 0)(encode, decode)
      assert(signal.peek == ("fresh", 0))
    }

  test("RTP-3 a failed write releases the writer and later changes recover"):
    withTempDir { dir =>
      val parent      = dir.resolve("blocked")
      Files.writeString(parent, "not a directory")
      val path        = parent.resolve("state.txt")
      val signal      = Persisted.signal(path, "fresh" -> 0)(encode, decode)
      signal.set("failed"    -> 1)
      var reported    = false
      val deadline    = System.nanoTime() + 3_000_000_000L
      while !reported && System.nanoTime() < deadline do
        try RenderThread.drainPending()
        catch case _: java.nio.file.FileAlreadyExistsException => reported = true
        Thread.sleep(2)
      assert(reported, "the write failure must be reported, not swallowed")
      Files.delete(parent)
      Files.createDirectory(parent)
      signal.set("recovered" -> 2)
      pumpUntil(3000)(Files.isRegularFile(path))
      assert(Files.isRegularFile(path))
      assert(Files.readString(path) == "recovered|2")
    }
