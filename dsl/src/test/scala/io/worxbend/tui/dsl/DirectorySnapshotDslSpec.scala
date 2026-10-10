package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

final class DirectorySnapshotDslSpec extends AnyFunSuite:
  test("the one-import surface exposes directory acquisition and task ownership"):
    assertCompiles("""
      import io.worxbend.tui.dsl.*
      import java.nio.file.Path
      def acquire(state: DirectoryTreeState, tasks: TaskScope): Cancelable = {
        val request: DirectoryLoadRequest = state.beginLoad(state.root)
        val entry: DirectoryEntry = DirectoryEntry(state.root, true, state.root)
        val listing: DirectoryListing = DirectoryListing(state.root, state.root, Vector(entry))
        val status: DirectoryLoadState = state.loadState(state.root)
        tasks.runCatching(DirectoryListing.load(request.directory)) { result =>
          val _ = state.install(request, result.flatMap(identity))
        }
      }
    """)

  test("constructing and painting a directory element never acquires the root"):
    val root = Files.createTempDirectory("glyphora-dsl-snapshot")
    val file = Files.writeString(root.resolve("not-loaded.txt"), "")
    try
      val state   = DirectoryTreeState(root)
      val element = directoryTree(state)
      element.widget.render(Rect(0, 0, 40, 4), Buffer(Rect(0, 0, 40, 4)))
      assert(state.childrenOf(root).isEmpty)
      assert(state.directoriesToLoad == Vector(root))
    finally
      Files.delete(file)
      Files.delete(root)

  test("picker painting does not implicitly acquire the root"):
    val root = Files.createTempDirectory("glyphora-picker-snapshot")
    try
      val state   = FilePickerState(root)
      val element = FilePickerElement(state, None)
      element.widget.render(Rect(0, 0, 40, 4), Buffer(Rect(0, 0, 40, 4)))
      assert(state.tree.directoriesToLoad == Vector(root))
    finally Files.delete(root)

  test("picker Enter uses installed directory metadata and exposes acquisition failure"):
    val root      = Files.createTempDirectory("glyphora-picker-stale")
    val directory = Files.createDirectory(root.resolve("gone"))
    try
      val state   = FilePickerState(root)
      state.tree.loadVisible()
      state.tree.selected = Some(directory)
      Files.delete(directory)
      val element = FilePickerElement(state, None)
      assert(element.builtinKeyHandler.exists(_(KeyEvent(KeyCode.Enter, KeyModifiers.None))))
      assert(state.tree.expanded.contains(directory))
      assert(state.chosen.peek.isEmpty, "a disappeared directory must not be accepted as a file")
      assert(state.tree.directoriesToLoad.isEmpty, "failure must not trigger an automatic retry loop")
    finally Files.delete(root)
