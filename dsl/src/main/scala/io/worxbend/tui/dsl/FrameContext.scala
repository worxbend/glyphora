package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size

import scala.collection.mutable

/** Everything a host publishes to the nodes beneath it while it paints a frame or dispatches an event on one thread:
  * the one `ThreadLocal` behind the paint-time registers.
  *
  * Each field belongs to one channel, which keeps its own API and its own install-and-restore discipline:
  *
  *   - `transforms` — [[FrameCoordinates]], the viewport stack, innermost first;
  *   - `portals` and `caret` — [[PortalQueue]] and [[CaretRegister]], each a [[FrameSlot]] armed for one pass;
  *   - `reveals` — [[RevealProbe]], the visibility collectors of the scroll views painting, innermost first;
  *   - `localSizes` — [[LocalProbe]], the sizes a container-query dry paint records;
  *   - `viewState` — the [[ViewState]] store hooks attach to while a view is evaluated;
  *   - `clipboard` — [[AppClipboard]], the running app's clipboard while it dispatches an event;
  *   - `pictures` — [[PictureEncodings]], the host's off-thread picture encodings while it paints;
  *   - `textEntryFocused` — whether the focused node [[Element.editsText]], set by [[EventRouter.dispatchCapture]] for
  *     the capture phase of one key, so a capture handler can yield a text field's own chords.
  *
  * They used to be seven `ThreadLocal`s, one per channel; they are fields of one record so that "what does a node see
  * while it paints" has one answer and one place to look. Empty — `Nil`, `None` — means "no host armed this here",
  * which every channel already treats as "fall back to plain behaviour".
  *
  * Ownership and threading: one record per thread, created on first use and only ever read or written by that thread,
  * so the fields need no synchronisation. Each runner paints on its own render thread, which is what keeps two runners
  * in one JVM from seeing each other's frame. `Messages.current` and `FrameworkKeys.current` stay `DynamicVariable`s: a
  * thread started inside their scope inherits them, so moving them here would change which table a worker thread
  * started inside the public `Messages.scoped` — a `Prompt.task`'s, say — reads.
  */
private[dsl] final class FrameContext:
  var transforms: List[ViewportTransform]            = Nil
  var portals: Option[PortalQueue.Pending]           = None
  var caret: Option[CaretRegister.Pending]           = None
  var reveals: List[RevealRequests]                  = Nil
  var localSizes: Option[mutable.HashMap[Int, Size]] = None
  var viewState: Option[ViewState]                   = None
  var clipboard: Option[Clipboard]                   = None
  var textEntryFocused: Boolean                      = false
  var pictures: Option[PictureEncodings]             = None
  lazy val threadClipboard: Clipboard                = Clipboard.inMemory()

private[dsl] object FrameContext:

  private val local: ThreadLocal[FrameContext] = ThreadLocal.withInitial(() => new FrameContext)

  /** This thread's record. */
  def current: FrameContext = local.get()
