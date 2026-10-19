package io.worxbend.tui.dsl

import io.worxbend.tui.core.Versioned
import io.worxbend.tui.macros.deriveForm

import org.scalatest.funsuite.AnyFunSuite

/** A top-level case class so `deriveForm` can summon its `Mirror`. */
final case class VersionedBooking(name: String, guests: Int)

/** The DSL's own caller-owned states are `Versioned` over their own fields, not only the inner widget states they wrap:
  * a mutation of any field the node paints raises the version, so a write made between frames schedules one.
  *
  * Table-driven like `widgets`' `VersionedStatesSpec`: each row is one mutation applied to a fresh state.
  */
final class DslVersionedStatesSpec extends AnyFunSuite:

  private final case class Row[S <: Versioned](state: String, fresh: () => S, mutations: Seq[(String, S => Unit)])

  private def row[S <: Versioned](state: String, fresh: => S)(mutations: (String, S => Unit)*): Row[S] =
    Row(state, () => fresh, mutations)

  private val rows: Seq[Row[? <: Versioned]] = Seq(
    row("AutocompleteState", AutocompleteState())(
      "highlighted ="   -> (_.highlighted = 2),
      "accept"          -> (_.accept("zebra")),
      "input.insert"    -> (_.input.insert("a")),
    ),
    row("FormState", FormState.of(deriveForm[VersionedBooking]))(
      "submit"          -> (_.submit()),
      "text field"      -> (state =>
        state.bindings.collectFirst { case FieldBinding.TextLike(_, input, _) => input }.foreach(_.insert("x"))
      ),
    ),
    row("PromptComposerState", PromptComposerState(initialHistory = Seq("one", "two")))(
      "historyPrevious" -> (_.historyPrevious()),
      "reverseSearch"   -> (_.reverseSearch()),
      "paste (large)"   -> (_.paste("a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk")),
      "submit"          -> (state => { state.editor.insert("x"); val _ = state.submit() }),
      "editor.insert"   -> (_.editor.insert("y")),
    ),
  )

  for r <- rows; (name, mutate) <- r.mutations do
    test(s"${r.state}.$name raises the version"):
      val state  = r.fresh()
      val before = state.version
      mutate(state)
      assert(state.version > before, s"${r.state}.$name left version at $before")

  test("a PromptComposerState historyNext after historyPrevious keeps raising the version"):
    val state  = PromptComposerState(initialHistory = Seq("one"))
    state.historyPrevious()
    val before = state.version
    state.historyNext()
    assert(state.version > before)

  test("the autocomplete and promptComposer nodes watch the whole state, not only the inner editor"):
    val auto     = AutocompleteState()
    val composer = PromptComposerState()
    assert(AutocompleteElement(auto, Seq("a")).watchedStates == Seq(auto))
    assert(PromptComposerElement(composer, _ => ()).watchedStates == Seq(composer))
