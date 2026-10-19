package io.worxbend.tui.examples.gallery

import io.worxbend.tui.dsl.*

/** One editable value a gallery page draws from: the page reads the signal in its view, the editor writes it.
  *
  * Only the kinds of value a factory takes as a plain argument — a whole number in a range, an on/off flag, one of a
  * few named choices — so one editor serves every page that declares them, with no per-factory code. A `Double`
  * argument is a `Number` in hundredths (`0..100` for a ratio), read back by the page as `value.get / 100.0`.
  */
enum Prop:
  case Number(name: String, value: Signal[Int], range: SliderRange)
  case Toggle(name: String, value: Signal[Boolean])
  case Choice(name: String, options: Seq[String], selected: Signal[Int])

  def name: String

/** The prop editor the gallery shows beside a page with [[Prop]]s: one labelled control per prop — a slider for a
  * number (with its value), a checkbox for a flag, a one-row cycler for a choice. Tab reaches the controls after the
  * page's own.
  */
object PropEditor:

  def apply(props: Seq[Prop]): View =
    column(props.map(row)*)

  private def row(prop: Prop)(using ReactiveScope): Element =
    prop match
      case Prop.Number(name, value, range)      =>
        column(text(s"$name: ${value.get}").length(1), slider(value, range).length(1)).length(2)
      case Prop.Toggle(name, value)             => checkbox(name, value).length(1)
      case Prop.Choice(name, options, selected) =>
        column(text(name).length(1), select(options, selected).length(1)).length(2)
