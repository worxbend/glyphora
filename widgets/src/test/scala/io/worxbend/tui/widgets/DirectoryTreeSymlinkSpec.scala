package io.worxbend.tui.widgets

import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

/** Pins the symlink contracts [[DirectoryTreeState]]'s own Scaladoc states explicitly: a symbolic link inside the tree
  * is followed even when its target resolves outside `root`, while a link that would recurse back onto the walk's own
  * chain — a loop — renders expanded-but-empty rather than overflowing the stack. Neither is asserted here because it
  * is desirable — see the Scaladoc note on `DirectoryTreeState` — but because a change to either (in either direction)
  * should be a deliberate, documented decision and not a silent behaviour drift this suite fails to notice.
  */
final class DirectoryTreeSymlinkSpec extends AnyFunSuite:

  test("expanding a symlink whose target is outside root reaches paths outside root"):
    val outside = Files.createTempDirectory("glyphora-outside")
    Files.writeString(outside.resolve("secret.txt"), "s")
    val root    = Files.createTempDirectory("glyphora-root")
    Files.writeString(root.resolve("inside.txt"), "i")
    Files.createSymbolicLink(root.resolve("escape"), outside)

    val state = DirectoryTreeState(root)
    state.loadVisible()
    val link  = state.visiblePaths().find(_.getFileName.toString == "escape").get
    state.selected = Some(link)
    state.toggle()
    state.loadVisible()

    val leaked = state.visiblePaths().filter(_.toRealPath().startsWith(outside.toRealPath()))
    assert(
      leaked.nonEmpty,
      "expected the symlink's target to be reachable — if this now fails, containment was added and " +
        "DirectoryTreeState's Scaladoc note above should be updated to match",
    )

  test("expanding every directory of a symlink loop renders without recursing forever"):
    val root = Files.createTempDirectory("glyphora-loop")
    Files.createDirectories(root.resolve("a"))
    Files.createSymbolicLink(root.resolve("a").resolve("loop"), root.resolve("a")) // a/loop -> a
    Files.createSymbolicLink(root.resolve("up"), root)                             // up -> the root itself

    val state  = DirectoryTreeState(root)
    state.loadVisible()
    // expand every directory that becomes visible, until the visible set stops growing. Without loop protection the
    // walk inside visiblePaths recurses (a/loop/a/loop/… or up/up/…) until the stack overflows; the loop terminating
    // is itself the behaviour under test, so it carries no separate timeout.
    var before = -1
    var after  = state.visiblePaths().size
    while after != before do
      state.visiblePaths().filter(Files.isDirectory(_)).foreach(path => state.expanded += path)
      state.loadVisible()
      before = after
      after = state.visiblePaths().size

    val visible = state.visiblePaths()
    assert(visible.count(_.getFileName.toString == "loop") == 1, s"expected one loop entry, got $visible")
    assert(visible.count(_.getFileName.toString == "up") == 1, s"expected one up entry, got $visible")
    // the cut entries render expanded-but-empty: nothing beneath a/loop or up appears (the entries themselves do)
    val loop    = root.resolve("a").resolve("loop")
    val up      = root.resolve("up")
    assert(!visible.exists(path => path != loop && path.startsWith(loop)), s"the loop was followed, got $visible")
    assert(!visible.exists(path => path != up && path.startsWith(up)), s"the root loop was followed, got $visible")
