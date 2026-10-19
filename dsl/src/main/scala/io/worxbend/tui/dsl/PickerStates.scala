package io.worxbend.tui.dsl

import io.worxbend.tui.core.Versioned
import io.worxbend.tui.runtime.Signal
import io.worxbend.tui.widgets.{DirectoryTreeState, TextInputState}

import java.nio.file.Path

/** App-owned state for an `autocomplete` element: the text being typed plus the highlighted suggestion.
  *
  * [[Versioned]] over both: [[version]] is the input's version plus this state's own count of highlight writes, so a
  * highlight moved between frames schedules a frame just like typed text does. Render-thread-only.
  */
final class AutocompleteState extends Versioned:
  val input: TextInputState = TextInputState()
  private var highlight     = 0
  private var writes        = 0L

  def version: Long = writes + input.version

  /** The highlighted suggestion's index among the visible matches; the node clamps it to the list it shows. */
  def highlighted: Int = highlight

  def highlighted_=(index: Int): Unit =
    highlight = index
    writes += 1

  /** Replaces the typed text with `choice` (what accepting a suggestion does). */
  def accept(choice: String): Unit =
    input.clear()
    input.insert(choice)
    highlighted = 0

/** App-owned state for a local `filePicker`: an initially unloaded tree plus the accepted file. Call
  * `tree.loadVisible()` explicitly before showing it, outside `view`/painting. The picker loads new branches
  * synchronously from its Enter handler; use `directoryTree` with asynchronous snapshots for slow filesystems.
  */
final class FilePickerState(root: Path):
  val tree: DirectoryTreeState     = DirectoryTreeState(root)
  val chosen: Signal[Option[Path]] = Signal(None)
