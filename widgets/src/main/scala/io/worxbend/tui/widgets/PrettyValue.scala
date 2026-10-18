package io.worxbend.tui.widgets

import scala.collection.immutable.Seq as ScalaSeq

/** A value described for display — the input of [[Inspect]].
  *
  * Four shapes cover any Scala value: an [[PrettyValue.Atom]] is already text (a number, a quoted string, `None`); a
  * [[PrettyValue.Seq]] is a named collection of items (`List`, `Vector`, `Some`); a [[PrettyValue.Map]] pairs keys with
  * values; a [[PrettyValue.Product]] is a named record of labelled fields (a case class, an enum case — with no fields,
  * a case object). `tui-macros`' `Pretty[A]` derives this description for case classes, enums and sealed hierarchies
  * without reflection, and the DSL's `inspect(value)` glues the two; build one by hand for anything else.
  *
  * Plain immutable data, safe to share between threads.
  */
enum PrettyValue:

  /** A leaf already in display form: `42`, `"text"`, `true`, `None`. */
  case Atom(text: String)

  /** A collection: `name` is how it introduces itself (`List`, `Set`, `Some`), `items` its elements in order. */
  case Seq(name: String, items: ScalaSeq[PrettyValue])

  /** A key-value collection; `name` is usually `Map`. */
  case Map(name: String, entries: ScalaSeq[(PrettyValue, PrettyValue)])

  /** A record: its type or case `name` and its fields, labelled, in declaration order. */
  case Product(name: String, fields: ScalaSeq[(String, PrettyValue)])
