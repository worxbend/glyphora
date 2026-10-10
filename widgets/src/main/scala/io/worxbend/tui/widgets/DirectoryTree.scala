package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, StatefulWidget, Style}

import java.nio.file.Path
import scala.collection.mutable

/** Caller-owned, render-thread-confined directory snapshots and interaction state.
  *
  * Construction, navigation and painting perform no filesystem IO. Call [[beginLoad]] on the owner thread, acquire
  * `DirectoryListing.load(request.directory)` on a worker, then [[install]] on the owner thread and request a redraw.
  * Never mutate this state from the worker. [[directoriesToLoad]] reports visible unloaded branches; loading and failed
  * branches are excluded until explicitly retried. [[loadVisible]] is a blocking convenience for small local trees, to
  * call explicitly outside painting, not a background pre-warm of mutable state.
  *
  * Links outside root are followed; this is not a sandbox. Cycle checks use only snapshot identities along the current
  * ancestor chain, so sibling aliases remain independently browsable. Selection, expansion and scroll are caller-owned.
  */
final class DirectoryTreeState(val root: Path):
  var selected: Option[Path]      = None
  var offset: Int                 = 0
  val expanded: mutable.Set[Path] = mutable.Set.empty
  private val listings            = mutable.Map.empty[Path, DirectoryListing]
  private val requests            = mutable.Map.empty[Path, DirectoryLoadRequest]
  private val failures            = mutable.Map.empty[Path, Throwable]

  /** Pure lookup; an unloaded, loading or failed branch has no children. */
  def childrenOf(directory: Path): Vector[Path] =
    listings.get(directory).fold(Vector.empty[Path])(_.entries.map(_.path))

  def loadState(directory: Path): DirectoryLoadState =
    if requests.contains(directory) then DirectoryLoadState.Loading
    else
      failures.get(directory) match
        case Some(error) => DirectoryLoadState.Failed(error)
        case None => if listings.contains(directory) then DirectoryLoadState.Loaded else DirectoryLoadState.Unloaded

  /** Starts or supersedes a generation; call on the owning render thread, not the worker. */
  def beginLoad(directory: Path): DirectoryLoadRequest =
    invalidate(Some(directory))
    val request = new DirectoryLoadRequest(directory)
    requests(directory) = request
    request

  /** Installs only the latest outstanding generation from this state. False means stale, foreign or mismatched data. A
    * completion is single-use. Errors remain observable through [[loadState]] and render as an empty branch.
    */
  def install(request: DirectoryLoadRequest, result: Either[Throwable, DirectoryListing]): Boolean =
    val matchesDirectory = result.forall(_.directory == request.directory)
    if !requests.get(request.directory).contains(request) || !matchesDirectory then false
    else
      requests.remove(request.directory)
      result match
        case Right(listing) => listings(request.directory) = listing
        case Left(error)    => failures(request.directory) = error
      true

  /** Drops snapshots and retires in-flight generations. Loading resumes only on an explicit request. */
  def invalidate(directory: Option[Path] = None): Unit =
    directory match
      case Some(path) =>
        listings.remove(path)
        requests.remove(path)
        failures.remove(path)
      case None       =>
        listings.clear()
        requests.clear()
        failures.clear()

  /** Visible unloaded directories, with cycles suppressed using acquired identities. Does not start work. */
  def directoriesToLoad: Vector[Path] =
    val (_, missing) = snapshotWalk()
    missing.filter(path => loadState(path) == DirectoryLoadState.Unloaded)

  /** Explicit blocking migration helper. Call only on the owner thread, before painting; never on a worker. For slow
    * filesystems prefer beginLoad / DirectoryListing.load / install. Failed branches are not retried here.
    */
  def loadVisible(): Unit =
    var pending = directoriesToLoad
    while pending.nonEmpty do
      pending.foreach { directory =>
        val request = beginLoad(directory)
        val _       = install(request, DirectoryListing.load(directory))
      }
      pending = directoriesToLoad

  def selectNext(): Unit     = moveSelection(+1)
  def selectPrevious(): Unit = moveSelection(-1)

  /** Expands/collapses a known directory. Schedule acquisition of newly visible branches separately. */
  def toggle(): Unit =
    selected
      .filter(path =>
        path == root || listings.valuesIterator.exists(_.entries.exists(e => e.path == path && e.isDirectory))
      )
      .foreach { path =>
        if expanded.contains(path) then expanded -= path else expanded += path
      }

  def visiblePaths(): Vector[Path] = visibleEntries().map(_._1)

  private[widgets] def visibleEntries(): Vector[(Path, Boolean)] = snapshotWalk()._1

  private def snapshotWalk(): (Vector[(Path, Boolean)], Vector[Path]) =
    val visible                                           = Vector.newBuilder[(Path, Boolean)]
    val missing                                           = Vector.newBuilder[Path]
    def walk(directory: Path, ancestors: Set[Path]): Unit =
      listings.get(directory) match
        case None          => missing += directory
        case Some(listing) =>
          val chain = ancestors + listing.identity
          listing.entries.foreach { entry =>
            visible += ((entry.path, entry.isDirectory))
            if entry.isDirectory && expanded.contains(entry.path) && !chain.contains(entry.identity) then
              walk(entry.path, chain)
          }
    walk(root, Set.empty)
    (visible.result(), missing.result())

  private def moveSelection(delta: Int): Unit =
    val visible = visiblePaths()
    if visible.nonEmpty then selected = Selection.moveWithin(visible, selected, delta)

/** A filesystem browser — [[Tree]] with the filesystem as its node source: lazy-loaded directory listings with
  * expand/collapse markers, `/`-suffixed directory names, selection highlight, and scroll-to-selection.
  */
final case class DirectoryTree(
    style: Style = Style.Default,
    highlightStyle: Style = Style.Default.reverse,
) extends StatefulWidget[DirectoryTreeState]:

  def render(area: Rect, buffer: Buffer, state: DirectoryTreeState): Unit =
    if !area.isEmpty then
      val visible       = state.visibleEntries()
      val selectedIndex = state.selected.map(path => visible.indexWhere(_._1 == path)).filter(_ >= 0)
      state.offset = TreeRows.render(
        area,
        buffer,
        visible,
        state.offset,
        selectedIndex,
        (path, _) => state.selected.contains(path),
        (path, isDir) => rowText(path, isDir, state),
        style,
        highlightStyle,
      )

  private def rowText(path: Path, isDirectory: Boolean, state: DirectoryTreeState): String =
    val depth  = path.getNameCount - state.root.getNameCount - 1
    val indent = "  ".repeat(math.max(0, depth))
    val name   = path.getFileName.toString
    if isDirectory then
      val marker = if state.expanded.contains(path) then "▾ " else "▸ "
      s"$indent$marker$name/"
    else s"$indent  $name"
