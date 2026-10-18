package io.worxbend.tui.dslimport

import io.worxbend.tui.dsl.*

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

/** The 0.20.0 state additions under the one-import rule: `Store`, `persisted` with a derived `Codec`, and a
  * hand-written `Codec.atom` all have to compile from `io.worxbend.tui.dsl.*` alone. Like `OneImportSpec`, this lives
  * outside the `dsl` package so an unexported name is a compile error here rather than silently visible.
  */
final class StateSurfaceSpec extends AnyFunSuite:

  private enum Side:
    case Left, Right

  private final case class Prefs(side: Side, width: Int, recent: List[String], nick: Option[Nick])

  /** A type the derivation cannot see through, taught with `Codec.atom` as the docs show. */
  private final class Nick(val value: String):
    override def equals(other: Any): Boolean = other match
      case that: Nick => that.value == value
      case _          => false
    override def hashCode: Int               = value.hashCode

  private given Codec[Nick] = Codec.atom[Nick](_.value, raw => Right(Nick(raw)))

  test("a persisted signal round-trips a derived case class through a file and reloads it in a new signal"):
    val dir  = Files.createTempDirectory("glyphora-persisted-dsl")
    val path = dir.resolve("prefs.txt")
    try
      val initial = Prefs(Side.Left, 40, Nil, None)
      val first   = persisted(path, initial)
      assert(first.peek == initial)
      first.set(Prefs(Side.Right, 72, List("a", "b"), Some(Nick("w"))))
      val end     = System.currentTimeMillis() + 3000
      while !Files.exists(path) && System.currentTimeMillis() < end do
        RenderThread.drainPending()
        Thread.sleep(2)
      RenderThread.drainPending()
      val second  = persisted(path, initial)
      assert(second.peek == Prefs(Side.Right, 72, List("a", "b"), Some(Nick("w"))))
    finally
      val _ = Files.deleteIfExists(path)
      val _ = Files.deleteIfExists(dir)

  test("Store is reachable and its select is a Computed"):
    val store                  = Store(1, history = 2)
    val doubled: Computed[Int] = store.select(_ * 2)
    store.dispatch(_ + 1)
    assert(doubled.peek == 4)
    assert(store.undo())
    assert(doubled.peek == 2)
