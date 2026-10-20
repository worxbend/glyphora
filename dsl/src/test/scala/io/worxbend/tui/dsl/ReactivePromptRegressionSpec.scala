package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import org.scalatest.funsuite.AnyFunSuite

final class ReactivePromptRegressionSpec extends AnyFunSuite with PilotFixture:
  test("RTP-2 loader coalesces ten thousand cheap reports"):
    val backend = HeadlessBackend(Size(40, 4))
    val result  = new java.util.concurrent.atomic.AtomicReference[Option[Seq[Int]]](None)
    val pilot   = startPilot(backend) {
      result.set(Prompt.loader("Fast", 1 to 10000)(identity)(using PromptSession.on(backend)))
      Right(())
    }
    assert(pilot.awaitTermination())
    assert(result.get.contains(1 to 10000))

  test("RTP-5 rejected scripted secrets are absent even from validator diagnostics"):
    given PromptSession = Prompt.answers(Seq("hunter2"), _ => ())
    val error           = intercept[IllegalArgumentException] {
      Prompt.password("Token", validate = raw => Left("bad " + raw))
    }
    assert(!error.getMessage.contains("hunter2"))

  test("RTP-8 snapshot hooks remain loading and never start effects"):
    var started = false
    val frame   = Snapshot.render(Size(30, 2)) {
      useEffect() { started = true; () => () }
      val task = useTask() { started = true; 42 }
      text(task.toString)
    }
    assert((0 until 7).map(x => frame.get(x, 0).symbol).mkString == "Loading")
    assert(!started)

  test("RTP-9 adaptive and empty-name themes round trip"):
    Seq(Theme.auto, Theme.auto.withAccent(Style.Default.bold), Theme.Dark.withName("")).foreach { theme =>
      assert(Theme.parse(Theme.render(theme)) == Right(theme))
    }

  test("RTP-13 empty scripted choices take terminal defaults"):
    given PromptSession = Prompt.answers(Seq("", "", ""), _ => ())
    assert(Prompt.select("One", Seq("a", "b")) == Some("a"))
    assert(Prompt.multiSelect("Many", Seq("a", "b"), preselected = Seq("b")) == Some(Seq("b")))
    assert(Prompt.multiSelect("Equal labels", Seq(1, 2), render = _ => "same", preselected = Seq(2)) == Some(Seq(2)))

  test("RTP-14 link whitespace and escapes round trip in styles and themes"):
    val style = Style.Default.bold.withLink("file:///a b/\"q\"\\x\tend")
    assert(Style.parse(Style.render(style)) == Right(style))
    val theme = Theme.Dark.withAccent(style)
    assert(Theme.parse(Theme.render(theme)) == Right(theme))

  test("RTP-4 host reset attempts all cancellations and remains reusable after failures"):
    val host            = ElementHost()
    val area            = io.worxbend.tui.core.Rect(0, 0, 20, 3)
    given ReactiveScope = ReactiveScope.untracked
    var cancelled       = Vector.empty[Int]
    val first           = IllegalStateException("first")
    val second          = IllegalStateException("second")
    host.render(
      area,
      io.worxbend.tui.core.Buffer(area),
      Theme.Dark, {
        useEffect() { () => { cancelled :+= 1; throw first } }
        useEffect() { () => { cancelled :+= 2; throw second } }
        text("mounted")
      },
    )
    assert(intercept[IllegalStateException](host.reset()) eq first)
    assert(cancelled == Vector(1, 2))
    assert(first.getSuppressed.toVector == Vector(second))
    host.render(area, io.worxbend.tui.core.Buffer(area), Theme.Dark, text("fresh"))
    host.reset()
    assert(cancelled == Vector(1, 2))

  test("RTP-1 lazy hooks survive a scroll jump and a grown viewport"):
    val state     = LazyListState()
    val scrolling = ScrollViewState()
    val cells     = scala.collection.mutable.Map.empty[Int, TextInputState]
    val app       = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        scrollView(
          lazyList(1000, 1, state) { i =>
            val cell = useState(TextInputState(s"item-$i"))
            cells(i) = cell
            text(cell.value).length(1)
          },
          scrolling,
        )
    val backend   = HeadlessBackend(Size(30, 3))
    val pilot     = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.readOnRenderThread { scrolling.offset = 300 }
    pilot.waitUntil("jumped hook rows")(pilot.screenText.contains("item-300"))
    val retained  = pilot.readOnRenderThread {
      val cell = cells(300)
      cell.setValue("edited-300")
      cell
    }
    pilot.resize(30, 12).waitForIdle()
    assert(pilot.screenText.contains("item-311"))
    assert(pilot.screenText.contains("edited-300"))
    assert(pilot.readOnRenderThread(cells(300) eq retained))
    pilot.interrupt()
    assert(pilot.awaitTermination())

  test("RTP-12 inline prompts do not open the inspector or palette"):
    val backend = HeadlessBackend(Size(60, 8))
    val pilot   = startPilot(backend) {
      val _ = Prompt.text("Name")(using PromptSession.on(backend))
      Right(())
    }
    pilot.waitForIdle().press("ctrl+p", "f12").waitForIdle()
    assert(!pilot.screenText.contains("Commands"))
    assert(!pilot.screenText.contains("inspector"))
    pilot.typeText("alpha").press("enter")
    assert(pilot.awaitTermination())

  test("RTP-6 loader cancellation stops before applying the next item"):
    val backend   = HeadlessBackend(Size(40, 4))
    val started   = new java.util.concurrent.CountDownLatch(1)
    val processed = new java.util.concurrent.atomic.AtomicInteger(0)
    val result    = new java.util.concurrent.atomic.AtomicReference[Option[Seq[Int]]](None)
    val pilot     = startPilot(backend) {
      result.set(Prompt.loader("Cancel", 1 to 10000) { n =>
        val _ = processed.incrementAndGet()
        started.countDown()
        while !Thread.currentThread().isInterrupted do Thread.onSpinWait()
        n
      }(using PromptSession.on(backend)))
      Right(())
    }
    assert(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
    pilot.waitForIdle().interrupt()
    assert(pilot.awaitTermination())
    assert(result.get.isEmpty)
    assert(processed.get == 1)

  test("RTP-5 password frames hide text and length while masked steps reject safely"):
    val backend = HeadlessBackend(Size(40, 4))
    val result  = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)
    val pilot   = startPilot(backend) {
      result.set(Prompt.password("Token")(using PromptSession.on(backend)))
      Right(())
    }
    pilot.waitForIdle()
    val before  = pilot.screenText
    val caret   = backend.cursorPosition
    pilot.typeText("hunter2").waitForIdle()
    assert(pilot.screenText == before)
    assert(backend.cursorPosition == caret)
    pilot.press("enter")
    assert(pilot.awaitTermination())
    assert(result.get.contains("hunter2"))
    assert(!pilot.screenText.contains("hunter2"))

  test("RTP-10 full screens retire effects while retaining ordinary component state"):
    var starts   = 0
    var stops    = 0
    var seen     = Option.empty[TextInputState]
    val page     = new Screen:
      override def presentation: Presentation       = Presentation.Full
      def view(using ReactiveScope, Theme): Element = text("covered")
    val app      = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("p", "cover")(pushScreen(page)),
        binding("b", "uncover")(popScreen()),
      )
      def view(using ReactiveScope, Theme): Element =
        val state = useState(TextInputState("retained"))
        seen = Some(state)
        useEffect() { starts += 1; () => stops += 1 }
        text(state.value)
    val backend  = HeadlessBackend(Size(30, 3))
    val pilot    = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val original = pilot.readOnRenderThread(seen.get)
    pilot.press("p").waitForIdle()
    assert(pilot.readOnRenderThread((starts, stops)) == (1, 1))
    pilot.press("b").waitForIdle()
    assert(pilot.readOnRenderThread(seen.get eq original))
    assert(pilot.readOnRenderThread((starts, stops)) == (2, 1))
    pilot.interrupt()
    assert(pilot.awaitTermination())
    assert(stops == 2)

  test("RTP-15 choice errors and plain hints use Messages"):
    val translated = Messages.Default
      .withPromptNothingToChoose("empty-local")
      .withPromptYesNoDefaultYes("yes-local")
      .withPromptCommaSeparated("many-local")
    Messages.scoped(translated) {
      val written         = StringBuilder()
      val input           = Iterator("", "")
      given PromptSession = PromptSession.plain(() => input.nextOption(), written ++= _)
      assert(Prompt.confirm("Go", default = true).contains(true))
      assert(Prompt.multiSelect("Many", Seq("a", "b")).contains(Seq.empty))
      assert(written.toString.contains("yes-local"))
      assert(written.toString.contains("many-local"))
      val error           = intercept[IllegalArgumentException] {
        Prompt.select("Empty", Seq.empty[String])(using Prompt.answers(Seq(""), _ => ()))
      }
      assert(error.getMessage.contains("empty-local"))
    }

  test("RTP-1 paint-time responsive hooks retain identity and release effects"):
    var starts  = 0
    var stops   = 0
    val seen    = scala.collection.mutable.ArrayBuffer.empty[Object]
    val app     = new TuiApp:
      val redraw                                    = Signal(0)
      override def bindings: KeyBindings            = KeyBindings(binding("r", "redraw")(redraw.update(_ + 1)))
      def view(using ReactiveScope, Theme): Element =
        val _                           = redraw.get
        def nested(depth: Int): Element =
          if depth > 0 then responsive(_ => nested(depth - 1))
          else
            seen += useState(new Object())
            useEffect() { starts += 1; () => stops += 1 }
            text("fallback")
        nested(18)
    val backend = HeadlessBackend(Size(30, 3))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle().press("r").waitForIdle()
    assert(pilot.screenText.contains("fallback"))
    assert(pilot.readOnRenderThread(seen.nonEmpty && seen.forall(_ eq seen.head)))
    assert(pilot.readOnRenderThread((starts, stops)) == (1, 0))
    pilot.interrupt()
    assert(pilot.awaitTermination())
    assert(stops == 1)

  test("RTP-5 plain password uses its secure reader and never echoes rejected input"):
    val written                         = StringBuilder()
    val answers                         = Iterator("private", "accepted")
    val plain: PromptSession.Mode.Plain = PromptSession.Mode.Plain(
      () => throw AssertionError("the ordinary line reader must not read a terminal password"),
      written ++= _,
      echoInput = true,
      readSecret = Some(() => answers.nextOption()),
    )
    val session                         = PromptSession.terminalOver(
      () => Left(io.worxbend.tui.terminal.BackendError.Io(java.io.IOException("offline"))),
      plain,
      Theme.Dark,
    )
    assert(
      Prompt
        .password("Token", raw => if raw == "private" then Left(raw) else Right(()))(using session)
        .contains("accepted")
    )
    assert(!written.toString.contains("private"))
    assert(!written.toString.contains("accepted"))

  test("RTP-4 a sweep attempts every cancellation before reporting failures"):
    val host            = ElementHost()
    val area            = io.worxbend.tui.core.Rect(0, 0, 20, 3)
    given ReactiveScope = ReactiveScope.untracked
    var cancelled       = Vector.empty[Int]
    host.render(
      area,
      io.worxbend.tui.core.Buffer(area),
      Theme.Dark, {
        useEffect() { () => { cancelled :+= 1; throw IllegalStateException("sweep") } }
        useEffect() { () => cancelled :+= 2 }
        text("mounted")
      },
    )
    intercept[IllegalStateException] {
      host.render(area, io.worxbend.tui.core.Buffer(area), Theme.Dark, text("gone"))
    }
    assert(cancelled == Vector(1, 2))
    host.reset()
    assert(cancelled == Vector(1, 2))

  test("RTP-12 inline task strips do not open framework overlays"):
    val backend = HeadlessBackend(Size(60, 8))
    val release = new java.util.concurrent.CountDownLatch(1)
    val pilot   = startPilot(backend) {
      val _ = Prompt.task("Work")(_ => release.await())(using PromptSession.on(backend))
      Right(())
    }
    pilot.waitForIdle().press("ctrl+p", "f12").waitForIdle()
    assert(!pilot.screenText.contains("Commands"))
    assert(!pilot.screenText.contains("inspector"))
    release.countDown()
    assert(pilot.awaitTermination())
