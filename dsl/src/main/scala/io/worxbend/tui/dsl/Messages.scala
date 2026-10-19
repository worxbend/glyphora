package io.worxbend.tui.dsl

import scala.util.DynamicVariable

/** Every word and marker the framework draws on its own, in one table an application can translate.
  *
  * A dialog's default button, the command palette's title and filter placeholder, the help overlay's heading, the
  * wizard's Back/Next/Finish, the inspector's panel titles, and the prompts' yes/no hints, list hints, pointer and
  * check glyphs — none of these are written by the application, so before this table an app shipped in Spanish still
  * said "OK" and "type to filter…". Each built-in reads its label from the table in force *when it is built or
  * painted*, never from a literal.
  *
  * An application sets the table with `override def messages: Messages = Messages.Default.withDialogOk("Sí")` on its
  * `TuiApp`; the run binds it on the render thread around every frame and every event, so a factory called in `view`
  * and an overlay painted after it read the same table. Code that builds elements outside a run — a [[Snapshot]],
  * `Console.print`, a `Prompt` — reads [[Default]] unless it is wrapped in [[Messages.scoped]].
  *
  * Labels the application passes explicitly (`dialog(title, message, buttons = Seq("Vale"))`) are never touched: the
  * table only supplies the defaults. Strings are plain values with no placeholders except where a field's Scaladoc
  * names one (`{lines}` in [[composerPasted]]). Immutable; safe to share between threads.
  *
  * @param dialogOk
  *   the single button `dialog(...)` draws when given none, and the confirming button of `Screen.confirm`.
  * @param dialogCancel
  *   the second, cancelling button of `Screen.confirm`.
  * @param helpTitle
  *   the title of `helpOverlay(...)` when none is passed.
  * @param paletteTitle
  *   the title of the command palette (Ctrl+P).
  * @param palettePlaceholder
  *   the command palette's filter-input placeholder.
  * @param wizardBack
  *   the wizard's previous-step button.
  * @param wizardNext
  *   the wizard's next-step button.
  * @param wizardFinish
  *   the wizard's next-step button on the last step.
  * @param inspectorTitle
  *   the title of the inspector's node card (F12).
  * @param inspectorTreeTitle
  *   the title of the inspector's element-tree panel.
  * @param inspectorNothing
  *   what the inspector's card says when nothing is focused or under the pointer.
  * @param promptYes
  *   how a confirm prompt's summary line shows a yes.
  * @param promptNo
  *   how a confirm prompt's summary line shows a no.
  * @param promptYesNoDefaultYes
  *   the hint beside a confirm prompt whose Enter means yes.
  * @param promptYesNoDefaultNo
  *   the hint beside a confirm prompt whose Enter means no.
  * @param promptCancelled
  *   what a cancelled prompt step leaves instead of an answer.
  * @param promptRequired
  *   the validation message of a required text prompt left empty.
  * @param promptSelectHint
  *   the hint beside a single-choice prompt.
  * @param promptMultiSelectHint
  *   the hint beside a multi-choice prompt.
  * @param promptPointer
  *   the marker before a choice prompt's highlighted row, including its trailing space.
  * @param promptChecked
  *   the marker of a chosen row in a multi-choice prompt, including its trailing space.
  * @param promptUnchecked
  *   the marker of an unchosen row in a multi-choice prompt, including its trailing space.
  * @param taskSucceeded
  *   the marker before a finished `Prompt.task` line.
  * @param taskFailed
  *   the marker before a failed `Prompt.task` line.
  * @param composerPasted
  *   the placeholder `promptComposer` inserts for a multi-line paste; `{lines}` is replaced by the line count.
  * @param composerReverseSearch
  *   the label of `promptComposer`'s Ctrl+R history search bar.
  */
final case class Messages(
    dialogOk: String = "OK",
    dialogCancel: String = "Cancel",
    helpTitle: String = "Help",
    paletteTitle: String = "Commands",
    palettePlaceholder: String = "type to filter…",
    wizardBack: String = "Back",
    wizardNext: String = "Next",
    wizardFinish: String = "Finish",
    inspectorTitle: String = "inspector",
    inspectorTreeTitle: String = "tree",
    inspectorNothing: String = "nothing focused or under the pointer",
    promptYes: String = "yes",
    promptNo: String = "no",
    promptYesNoDefaultYes: String = "(Y/n)",
    promptYesNoDefaultNo: String = "(y/N)",
    promptCancelled: String = "(cancelled)",
    promptRequired: String = "an answer is required",
    promptSelectHint: String = "(arrows to move, enter to select)",
    promptMultiSelectHint: String = "(space to select, enter to confirm)",
    promptPointer: String = "❯ ",
    promptChecked: String = "◉ ",
    promptUnchecked: String = "◯ ",
    taskSucceeded: String = "✓",
    taskFailed: String = "✗",
    composerPasted: String = "[Pasted {lines} lines]",
    composerReverseSearch: String = "(reverse-i-search)",
):

  def withDialogOk(label: String): Messages              = copy(dialogOk = label)
  def withDialogCancel(label: String): Messages          = copy(dialogCancel = label)
  def withHelpTitle(label: String): Messages             = copy(helpTitle = label)
  def withPaletteTitle(label: String): Messages          = copy(paletteTitle = label)
  def withPalettePlaceholder(label: String): Messages    = copy(palettePlaceholder = label)
  def withWizardBack(label: String): Messages            = copy(wizardBack = label)
  def withWizardNext(label: String): Messages            = copy(wizardNext = label)
  def withWizardFinish(label: String): Messages          = copy(wizardFinish = label)
  def withInspectorTitle(label: String): Messages        = copy(inspectorTitle = label)
  def withInspectorTreeTitle(label: String): Messages    = copy(inspectorTreeTitle = label)
  def withInspectorNothing(label: String): Messages      = copy(inspectorNothing = label)
  def withPromptYes(label: String): Messages             = copy(promptYes = label)
  def withPromptNo(label: String): Messages              = copy(promptNo = label)
  def withPromptYesNoDefaultYes(label: String): Messages = copy(promptYesNoDefaultYes = label)
  def withPromptYesNoDefaultNo(label: String): Messages  = copy(promptYesNoDefaultNo = label)
  def withPromptCancelled(label: String): Messages       = copy(promptCancelled = label)
  def withPromptRequired(label: String): Messages        = copy(promptRequired = label)
  def withPromptSelectHint(label: String): Messages      = copy(promptSelectHint = label)
  def withPromptMultiSelectHint(label: String): Messages = copy(promptMultiSelectHint = label)
  def withPromptPointer(marker: String): Messages        = copy(promptPointer = marker)
  def withPromptChecked(marker: String): Messages        = copy(promptChecked = marker)
  def withPromptUnchecked(marker: String): Messages      = copy(promptUnchecked = marker)
  def withTaskSucceeded(marker: String): Messages        = copy(taskSucceeded = marker)
  def withTaskFailed(marker: String): Messages           = copy(taskFailed = marker)
  def withComposerPasted(template: String): Messages     = copy(composerPasted = template)
  def withComposerReverseSearch(label: String): Messages = copy(composerReverseSearch = label)

  /** [[composerPasted]] with `{lines}` filled in. */
  def pastedLines(lines: Int): String = composerPasted.replace("{lines}", lines.toString)

object Messages:

  /** The English labels every release before 0.20.0 drew. */
  val Default: Messages = Messages()

  /** The table the built-ins on this thread read. A `TuiApp` run binds it around each frame and each event to the app's
    * own [[TuiApp.messages]]; per thread, so two runners in one JVM keep their own.
    */
  private[dsl] val current: DynamicVariable[Messages] = DynamicVariable(Default)

  /** The table in force on the calling thread: the running app's inside a frame or a handler, the one [[scoped]]
    * installed inside its block, [[Default]] otherwise.
    */
  def active: Messages = current.value

  /** Runs `body` with `messages` as the table every built-in on this thread reads — for a [[Snapshot]], a
    * `Console.print` or a `Prompt` run outside any `TuiApp`. Restores the previous table afterwards, even on a throw.
    */
  def scoped[A](messages: Messages)(body: => A): A = current.withValue(messages)(body)
