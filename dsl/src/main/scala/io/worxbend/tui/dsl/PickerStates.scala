package io.worxbend.tui.dsl

import io.worxbend.tui.runtime.Signal
import io.worxbend.tui.widgets.{DirectoryTreeState, TextInputState}

import java.nio.file.Path

/** App-owned state for an `autocomplete` element: the text being typed plus the highlighted suggestion. */
final class AutocompleteState:
  val input: TextInputState = TextInputState()
  var highlighted: Int      = 0

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
