package io.worxbend.tui.dsl

import io.worxbend.tui.macros.Codec
import io.worxbend.tui.runtime.{Persisted, Signal, SignalEquality}

import java.nio.file.Path

/** A [[Signal]] that survives a restart, with its file format derived from the value's type.
  *
  * {{{
  * final case class Prefs(theme: String, sidebar: Boolean, recent: List[String])
  *
  * private val prefs = persisted(configDir.resolve("prefs.txt"), Prefs("dark", sidebar = true, Nil))
  * }}}
  *
  * This is `Persisted.signal` from `tui-runtime` with the two halves of the file format supplied by the [[Codec]] in
  * scope — derived for any case class, enum or sealed hierarchy over the primitives, `Option` and the immutable
  * collections, or hand-written next to the type through `Codec.atom`. It is the glue between a `tui-macros` typeclass
  * and a `tui-runtime` value, which is why it lives here rather than in either of them.
  *
  * The contract is `Persisted.signal`'s: loaded synchronously when created (a missing or undecodable file yields
  * `initial`), written back on an `Async` worker on every change, newest value wins, atomic replace. Create it as a
  * field or in `onStart`, never per frame; the render-thread rules of `Signal` apply unchanged.
  */
def persisted[A](path: Path, initial: A)(using codec: Codec[A], equality: SignalEquality[A]): Signal[A] =
  Persisted.signal(path, initial)(codec.encode, codec.decode)
