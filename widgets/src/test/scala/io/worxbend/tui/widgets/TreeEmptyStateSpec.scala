package io.worxbend.tui.widgets

import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Path

final class TreeEmptyStateSpec extends AnyFunSuite:
  private val nodes = Vector.tabulate(8)(index => TreeNode(s"node$index"))

  private def populatedTree(): TreeState =
    val state = TreeState()
    state.selected = Some(Seq(7))
    val _     = rendered(Tree(nodes), state, 20, 2)
    assert(state.offset == 6)
    state

  test("empty source painting repairs populated tree selection and scroll"):
    val state = populatedTree()
    assert(trimmedLines(rendered(Tree(Vector.empty), state, 20, 2)).forall(_.isEmpty))
    assert(state.selected.isEmpty)
    assert(state.offset == 0)

  test("empty source navigation repairs populated tree selection and scroll"):
    Seq[TreeState => Unit](_.selectNext(Vector.empty), _.selectPrevious(Vector.empty), _.toggle(Vector.empty)).foreach {
      action =>
        val state = populatedTree()
        action(state)
        assert(state.selected.isEmpty)
        assert(state.offset == 0)
        state.selectNext(nodes)
        assert(state.selected.contains(Seq(0)))
    }

  private val root    = Path.of("/virtual-glyphora-empty")
  private val entries = Vector.tabulate(8) { index =>
    val path = root.resolve(s"file$index")
    DirectoryEntry(path, false, path)
  }

  private def populatedDirectory(): DirectoryTreeState =
    val state = DirectoryTreeState(root)
    assert(state.install(state.beginLoad(root), Right(DirectoryListing(root, root, entries))))
    state.selected = Some(entries.last.path)
    val _     = rendered(DirectoryTree(), state, 20, 2)
    assert(state.offset == 6)
    state

  test("empty and failed directory loads repair populated selection and scroll during painting"):
    val results: Seq[Either[Throwable, DirectoryListing]] = Seq(
      Right(DirectoryListing(root, root, Vector.empty)),
      Left(new IllegalStateException("unavailable")),
    )
    results.foreach { result =>
      val state = populatedDirectory()
      assert(state.install(state.beginLoad(root), result))
      assert(trimmedLines(rendered(DirectoryTree(), state, 20, 2)).forall(_.isEmpty))
      assert(state.selected.isEmpty)
      assert(state.offset == 0)
      assert(state.directoriesToLoad.isEmpty)
    }

  test("invalidated directory navigation repairs populated selection and scroll without acquisition"):
    Seq[DirectoryTreeState => Unit](_.selectNext(), _.selectPrevious(), _.toggle()).foreach { action =>
      val state = populatedDirectory()
      state.invalidate()
      action(state)
      assert(state.selected.isEmpty)
      assert(state.offset == 0)
      assert(state.directoriesToLoad == Vector(root))
      assert(state.loadState(root) == DirectoryLoadState.Unloaded)
    }
