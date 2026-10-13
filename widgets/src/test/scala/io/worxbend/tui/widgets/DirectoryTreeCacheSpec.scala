package io.worxbend.tui.widgets

import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import org.scalatest.funsuite.AnyFunSuite

import java.io.File
import java.net.URI
import java.nio.file.{FileSystem, LinkOption, Path, WatchEvent, WatchKey, WatchService}

final class DirectoryTreeCacheSpec extends AnyFunSuite:
  test("rejected stale foreign and mismatched completions leave a warm snapshot cache intact"):
    val root       = Path.of("/virtual-glyphora-rejected-cache")
    val child      = root.resolve("child")
    val state      = DirectoryTreeState(root)
    val stale      = state.beginLoad(root)
    val current    = state.beginLoad(root)
    val listing    = DirectoryListing(root, root, Vector(DirectoryEntry(child, false, child)))
    val loading    = state.visiblePaths()
    val mismatched = DirectoryListing(child, child, Vector.empty)
    assert(!state.install(current, Right(mismatched)))
    assert(state.visiblePaths() eq loading)
    assert(state.loadState(root) == DirectoryLoadState.Loading)
    assert(state.install(current, Right(listing)))
    val warm       = state.visiblePaths()
    val missing    = state.directoriesToLoad
    val foreign    = DirectoryTreeState(root).beginLoad(root)
    assert(!state.install(stale, Right(DirectoryListing(root, root, Vector.empty))))
    assert(!state.install(foreign, Left(IllegalStateException("foreign failure"))))
    assert(!state.install(current, Left(IllegalStateException("duplicate completion"))))
    assert(state.visiblePaths() eq warm)
    assert(state.directoriesToLoad eq missing)
    assert(state.loadState(root) == DirectoryLoadState.Loaded)
    assert(trimmedLines(rendered(DirectoryTree(), state, 20, 1)) == Seq("  child"))
    state.selectNext()
    assert(state.selected.contains(child))

  /** Only acquired target identities are instrumented, not paths used for painting or selection. Each expanded
    * sibling's ancestor-cycle check compares its identity, so the counter observes snapshot traversal without any
    * filesystem IO.
    */
  private final class CountedIdentity(val underlying: Path) extends Path:
    var comparisons: Int                                = 0
    override def equals(other: Any): Boolean            =
      comparisons += 1
      other match
        case identity: CountedIdentity => underlying == identity.underlying
        case _                         => underlying == other
    override def hashCode(): Int                        = underlying.hashCode()
    override def getFileSystem: FileSystem              = underlying.getFileSystem
    override def isAbsolute: Boolean                    = underlying.isAbsolute
    override def getRoot: Path                          = underlying.getRoot
    override def getFileName: Path                      = underlying.getFileName
    override def getParent: Path                        = underlying.getParent
    override def getNameCount: Int                      = underlying.getNameCount
    override def getName(index: Int): Path              = underlying.getName(index)
    override def subpath(begin: Int, end: Int): Path    = underlying.subpath(begin, end)
    override def startsWith(other: Path): Boolean       = underlying.startsWith(other)
    override def startsWith(other: String): Boolean     = underlying.startsWith(other)
    override def endsWith(other: Path): Boolean         = underlying.endsWith(other)
    override def endsWith(other: String): Boolean       = underlying.endsWith(other)
    override def normalize(): Path                      = underlying.normalize()
    override def resolve(other: Path): Path             = underlying.resolve(other)
    override def resolve(other: String): Path           = underlying.resolve(other)
    override def resolveSibling(other: Path): Path      = underlying.resolveSibling(other)
    override def resolveSibling(other: String): Path    = underlying.resolveSibling(other)
    override def relativize(other: Path): Path          = underlying.relativize(other)
    override def toUri: URI                             = underlying.toUri
    override def toAbsolutePath: Path                   = underlying.toAbsolutePath
    override def toRealPath(options: LinkOption*): Path = underlying.toRealPath(options*)
    override def toFile: File                           = underlying.toFile
    override def register(
        watcher: WatchService,
        events: Array[WatchEvent.Kind[?]],
        modifiers: WatchEvent.Modifier*
    ): WatchKey                                         = underlying.register(watcher, events, modifiers*)
    override def register(watcher: WatchService, events: WatchEvent.Kind[?]*): WatchKey =
      underlying.register(watcher, events*)
    override def iterator(): java.util.Iterator[Path]                                   = underlying.iterator()
    override def compareTo(other: Path): Int                                            = underlying.compareTo(other)
    override def toString: String                                                       = underlying.toString

  test("unchanged paints navigation and load discovery reuse one snapshot traversal"):
    val root     = Path.of("/virtual-glyphora-cache")
    val identity = new CountedIdentity(Path.of("/virtual-glyphora-target"))
    val entries  = Vector.tabulate(2000) { i =>
      val path = root.resolve(s"branch$i")
      DirectoryEntry(path, true, identity)
    }
    val state    = DirectoryTreeState(root)
    assert(state.install(state.beginLoad(root), Right(DirectoryListing(root, root, entries))))
    state.expanded ++= entries.map(_.path)
    assert(trimmedLines(rendered(DirectoryTree(), state, 20, 2)) == Seq("▾ branch0/", "▾ branch1/"))
    assert(identity.comparisons >= 2000)
    identity.comparisons = 0
    (0 until 10).foreach { _ =>
      state.selectNext()
      val _ = rendered(DirectoryTree(), state, 20, 2)
      assert(state.directoriesToLoad.size == 2000)
      assert(state.visiblePaths().size == 2000)
    }
    state.selectPrevious()
    assert(state.selected.contains(entries(8).path))
    assert(identity.comparisons == 0, s"unchanged tree repeated ${identity.comparisons} ancestor checks")

  test("same-size installed snapshot replacement retires paths and navigation indices"):
    val root                                    = Path.of("/virtual-glyphora-replacement")
    val state                                   = DirectoryTreeState(root)
    def listing(name: String): DirectoryListing =
      val path = root.resolve(name)
      DirectoryListing(root, root, Vector(DirectoryEntry(path, false, path)))
    assert(state.install(state.beginLoad(root), Right(listing("old"))))
    state.selectNext()
    assert(trimmedLines(rendered(DirectoryTree(), state, 20, 1)) == Seq("  old"))
    assert(state.install(state.beginLoad(root), Right(listing("new"))))
    assert(trimmedLines(rendered(DirectoryTree(), state, 20, 1)) == Seq("  new"))
    state.selectNext()
    assert(state.selected.contains(root.resolve("new")))

  test("every direct expansion set mutation invalidates warm snapshots while no-ops retain them"):
    val root    = Path.of("/virtual-glyphora-mutations")
    val left    = root.resolve("left")
    val right   = root.resolve("right")
    val state   = DirectoryTreeState(root)
    val entries = Vector(left, right).map(path => DirectoryEntry(path, true, path))
    assert(state.install(state.beginLoad(root), Right(DirectoryListing(root, root, entries))))
    Vector(left, right).foreach { path =>
      val leaf = path.resolve("leaf")
      assert(
        state.install(
          state.beginLoad(path),
          Right(DirectoryListing(path, path, Vector(DirectoryEntry(leaf, false, leaf)))),
        )
      )
    }
    def check(mutate: => Unit, changed: Boolean): Unit =
      val before   = state.visiblePaths()
      mutate
      val after    = state.visiblePaths()
      assert((after eq before) == !changed)
      val expected = Vector(left, right).flatMap { path =>
        if state.expanded.contains(path) then Vector(path, path.resolve("leaf")) else Vector(path)
      }
      assert(after == expected)
      state.selected = expected.lastOption
      state.selectPrevious()
      assert(state.selected == expected.lift(math.max(0, expected.size - 2)))
      val _        = rendered(DirectoryTree(), state, 20, 4)
      assert(state.directoriesToLoad.isEmpty)
    check(state.expanded += left, true)
    check(state.expanded += left, false)
    check(state.expanded -= left, true)
    check(state.expanded -= left, false)
    check(state.expanded ++= Seq(left, right), true)
    check(state.expanded ++= Seq(left, right), false)
    check(state.expanded --= Seq(left, right), true)
    check(state.expanded --= Seq(left, right), false)
    check(state.expanded ++= Seq(left, right), true)
    check(state.expanded.filterInPlace(_ == right), true)
    check(state.expanded.filterInPlace(_ == right), false)
    check(state.expanded.clear(), true)
    check(state.expanded.clear(), false)
