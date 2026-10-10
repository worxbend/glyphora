package io.worxbend.tui.widgets

import java.nio.file.{Files, Path}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import org.scalatest.funsuite.AnyFunSuite

final class DirectorySnapshotSpec extends AnyFunSuite:
  test("painting and navigation never acquire an unloaded directory"):
    val root = Files.createTempDirectory("glyphora-snapshot")
    try
      Files.writeString(root.resolve("file"), "")
      val state = DirectoryTreeState(root)
      assert(trimmedLines(rendered(DirectoryTree(), state, 20, 4)).forall(_.isEmpty))
      state.selectNext()
      assert(state.selected.isEmpty)
      assert(state.childrenOf(root).isEmpty)
    finally
      Files.deleteIfExists(root.resolve("file"))
      Files.deleteIfExists(root)

  private val root    = Path.of("/virtual-glyphora-tree")
  private val child   = root.resolve("branch")
  private val listing = DirectoryListing(root, root, Vector(DirectoryEntry(child, true, root)))

  test("injected immutable identities cut cycles without consulting the filesystem"):
    val state   = DirectoryTreeState(root)
    assert(state.directoriesToLoad == Vector(root))
    val request = state.beginLoad(root)
    assert(state.loadState(root) == DirectoryLoadState.Loading)
    assert(state.directoriesToLoad.isEmpty)
    assert(state.install(request, Right(listing)))
    state.selected = Some(child)
    state.toggle()
    assert(state.visiblePaths() == Vector(child))
    assert(state.directoriesToLoad.isEmpty)
    assert(trimmedLines(rendered(DirectoryTree(), state, 30, 3)).head == "▾ branch/")
    assert(state.loadState(root) == DirectoryLoadState.Loaded)

  test("new requests retire prior results including failures and single-use completions"):
    val state = DirectoryTreeState(root)
    val old   = state.beginLoad(root)
    val fresh = state.beginLoad(root)
    assert(!state.install(old, Right(listing)))
    assert(!state.install(old, Left(new IllegalStateException("old"))))
    assert(state.install(fresh, Right(listing)))
    assert(!state.install(fresh, Right(listing)))
    assert(state.childrenOf(root) == Vector(child))

  test("invalidation retires outstanding work and a foreign state cannot install it"):
    val state   = DirectoryTreeState(root)
    val other   = DirectoryTreeState(root)
    val request = state.beginLoad(root)
    val _       = other.beginLoad(root)
    assert(!other.install(request, Right(listing)))
    state.invalidate(Some(root))
    assert(!state.install(request, Right(listing)))
    assert(state.loadState(root) == DirectoryLoadState.Unloaded)
    val pending = state.beginLoad(root)
    state.invalidate()
    assert(!state.install(pending, Right(listing)))

  test("failures remain distinguishable from empty success and can be explicitly retried"):
    val state = DirectoryTreeState(root)
    val error = new IllegalStateException("unavailable")
    assert(state.install(state.beginLoad(root), Left(error)))
    assert(state.loadState(root) == DirectoryLoadState.Failed(error))
    assert(state.directoriesToLoad.isEmpty)
    assert(state.visiblePaths().isEmpty)
    assert(state.install(state.beginLoad(root), Right(listing.copy(entries = Vector.empty))))
    assert(state.loadState(root) == DirectoryLoadState.Loaded)
    assert(state.visiblePaths().isEmpty)

  test("a result for another directory cannot consume the outstanding request"):
    val state   = DirectoryTreeState(root)
    val request = state.beginLoad(root)
    assert(!state.install(request, Right(listing.copy(directory = child))))
    assert(state.loadState(root) == DirectoryLoadState.Loading)
    assert(state.install(request, Right(listing)))

  test("sibling aliases with one identity both expand"):
    val state   = DirectoryTreeState(root)
    val left    = root.resolve("left")
    val right   = root.resolve("right")
    val target  = Path.of("/virtual-target")
    val entries = Vector(DirectoryEntry(left, true, target), DirectoryEntry(right, true, target))
    assert(state.install(state.beginLoad(root), Right(listing.copy(entries = entries))))
    Vector(left, right).foreach { alias =>
      val data =
        DirectoryListing(alias, target, Vector(DirectoryEntry(alias.resolve("leaf"), false, target.resolve("leaf"))))
      assert(state.install(state.beginLoad(alias), Right(data)))
      state.expanded += alias
    }
    assert(state.visiblePaths() == Vector(left, left.resolve("leaf"), right, right.resolve("leaf")))

  test("acquisition reports actual filesystem failure"):
    val directory = Files.createTempDirectory("glyphora-missing")
    Files.delete(directory)
    assert(DirectoryListing.load(directory).isLeft)

  test("acquired snapshots survive removal of the underlying filesystem"):
    val directory     = Files.createTempDirectory("glyphora-immutable")
    val branch        = Files.createDirectory(directory.resolve("branch"))
    val file          = Files.writeString(branch.resolve("leaf"), "")
    val state         = DirectoryTreeState(directory)
    val rootRequest   = state.beginLoad(directory)
    val branchRequest = state.beginLoad(branch)
    val rootResult    = DirectoryListing.load(directory)
    val branchResult  = DirectoryListing.load(branch)
    Files.delete(file)
    Files.delete(branch)
    Files.delete(directory)
    assert(state.install(rootRequest, rootResult))
    assert(state.install(branchRequest, branchResult))
    state.expanded += branch
    val expected      = Seq("▾ branch/", "    leaf")
    assert(trimmedLines(rendered(DirectoryTree(), state, 30, 3)).take(2) == expected)
    assert(state.directoriesToLoad.isEmpty)
