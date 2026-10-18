package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, StatefulWidget, Style, VersionedState}

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
final class DirectoryTreeState(val root: Path) extends VersionedState:
  private final case class View(
      entries: Vector[(Path, Boolean)],
      paths: Vector[Path],
      indices: Map[Path, Int],
      missing: Vector[Path],
  )
  private var cached: Option[View] = None
  def selected: Option[Path]       = selectedValue

  def selected_=(value: Option[Path]): Unit =
    selectedValue = value
    touch()

  private var selectedValue: Option[Path] = None
  def offset: Int                         = offsetValue

  def offset_=(value: Int): Unit =
    offsetValue = value
    touch()

  private var offsetValue: Int    = 0
  val expanded: mutable.Set[Path] = new TreeExpansion(() => { cached = None; touch() })
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
    touch()
    request

  /** Installs only the latest outstanding generation from this state. False means stale, foreign or mismatched data. A
    * completion is single-use. Errors remain observable through [[loadState]] and render as an empty branch.
    */
  def install(request: DirectoryLoadRequest, result: Either[Throwable, DirectoryListing]): Boolean =
    val matchesDirectory = result.forall(_.directory == request.directory)
    if !requests.get(request.directory).contains(request) || !matchesDirectory then false
    else
      cached = None
      requests.remove(request.directory)
      result match
        case Right(listing) => listings(request.directory) = listing
        case Left(error)    => failures(request.directory) = error
      touch()
      true

  /** Drops snapshots and retires in-flight generations. Loading resumes only on an explicit request. */
  def invalidate(directory: Option[Path] = None): Unit =
    cached = None
    directory match
      case Some(path) =>
        listings.remove(path)
        requests.remove(path)
        failures.remove(path)
      case None       =>
        listings.clear()
        requests.clear()
        failures.clear()
    touch()

  /** Visible unloaded directories, with cycles suppressed using acquired identities. Does not start work. */
  def directoriesToLoad: Vector[Path] =
    view.missing

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
    val _ = view
    selected
      .filter(path =>
        path == root || listings.valuesIterator.exists(_.entries.exists(e => e.path == path && e.isDirectory))
      )
      .foreach { path =>
        if expanded.contains(path) then expanded -= path else expanded += path
      }

  def visiblePaths(): Vector[Path] = view.paths

  private[widgets] def visibleEntries(): Vector[(Path, Boolean)] = view.entries

  private[widgets] def selectedIndex: Option[Int] = selected.flatMap(view.indices.get)

  /** Retains only the current snapshot/expansion generation; acquisition changes and direct set mutations retire it. */
  private def view: View =
    val visible = cachedView
    if visible.paths.isEmpty then
      selected = None
      offset = 0
    visible

  private def cachedView: View = cached match
    case Some(visible) => visible
    case None          =>
      val visible = snapshotWalk()
      cached = Some(visible)
      visible

  private def snapshotWalk(): View =
    val visible                                           = Vector.newBuilder[(Path, Boolean)]
    val missing                                           = Vector.newBuilder[Path]
    def walk(directory: Path, ancestors: Set[Path]): Unit =
      listings.get(directory) match
        case None          => if loadState(directory) == DirectoryLoadState.Unloaded then missing += directory
        case Some(listing) =>
          val chain = ancestors + listing.identity
          listing.entries.foreach { entry =>
            visible += ((entry.path, entry.isDirectory))
            if entry.isDirectory && expanded.contains(entry.path) && !chain.contains(entry.identity) then
              walk(entry.path, chain)
          }
    walk(root, Set.empty)
    val entries                                           = visible.result()
    val paths                                             = entries.map(_._1)
    View(entries, paths, paths.iterator.zipWithIndex.toMap, missing.result())

  private def moveSelection(delta: Int): Unit =
    val visible = view
    if visible.paths.nonEmpty then
      selected = Selection.by(selectedIndex, visible.paths.size, delta).map(visible.paths(_))

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
      val selectedIndex = state.selectedIndex
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
