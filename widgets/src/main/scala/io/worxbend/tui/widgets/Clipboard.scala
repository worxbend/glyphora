package io.worxbend.tui.widgets

/** Where an editor's cut, copy and paste go. The caller supplies one — a widget never reaches for a system clipboard on
  * its own, because it has no terminal: the DSL hands its text nodes one that copies through OSC 52 and pastes from an
  * in-app buffer, and a plain unit test passes [[Clipboard.inMemory]].
  *
  * Called on the render thread, from inside an edit.
  */
trait Clipboard:

  /** Stores `text` as the clipboard's content. */
  def copy(text: String): Unit

  /** The clipboard's content, or `None` when there is nothing to paste. */
  def paste(): Option[String]

object Clipboard:

  /** A clipboard that remembers the last copy in memory and nothing else — for tests and for apps without a terminal.
    */
  def inMemory(): Clipboard = new Clipboard:
    private var content: Option[String] = None
    def copy(text: String): Unit        = content = Some(text)
    def paste(): Option[String]         = content

  /** A clipboard that drops every copy and never pastes. */
  val Discard: Clipboard = new Clipboard:
    def copy(text: String): Unit = ()
    def paste(): Option[String]  = None
