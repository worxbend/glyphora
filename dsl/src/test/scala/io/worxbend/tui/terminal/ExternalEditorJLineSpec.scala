package io.worxbend.tui.terminal

import io.worxbend.tui.dsl.*
import org.jline.terminal.{Size as JLineSize, Terminal}
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

final class ExternalEditorJLineSpec extends AnyFunSuite:
  test("missing editors in editExternally and openInEditor do not terminate a real JLine runner"):
    val sink     = ByteArrayOutputStream()
    val terminal = LineDisciplineTerminal("editor-test", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    (terminal: Terminal).setSize(JLineSize.of(30, 5))
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    val started  = CountDownLatch(1)
    val edited   = CountDownLatch(1)
    val opened   = CountDownLatch(1)
    val app      = new TuiApp:
      override protected def editorEnvironment: Map[String, String] = Map("EDITOR" -> "/nonexistent/glyphora-editor")
      override def onStart(): Unit                                  = started.countDown()
      override def bindings: KeyBindings                            = KeyBindings(
        binding("e", "edit") {
          assert(services.editExternally("draft", ".txt").isEmpty)
          edited.countDown()
        },
        binding("o", "open") {
          services.openInEditor(java.nio.file.Path.of("notes"))
          opened.countDown()
        },
        binding("q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element                 = text("host")
    val executor = Executors.newSingleThreadExecutor()
    val result   = executor.submit(() => app.runWith(backend))
    try
      assert(started.await(10, TimeUnit.SECONDS))
      terminal.processInputBytes("e".getBytes(UTF_8))
      assert(edited.await(10, TimeUnit.SECONDS))
      terminal.processInputBytes("o".getBytes(UTF_8))
      assert(opened.await(10, TimeUnit.SECONDS))
      assert(!result.isDone)
      terminal.processInputBytes("q".getBytes(UTF_8))
      assert(result.get(10, TimeUnit.SECONDS).isRight)
    finally
      if !result.isDone then terminal.processInputBytes("q".getBytes(UTF_8))
      val _ = executor.shutdownNow()
      val _ = backend.close()
