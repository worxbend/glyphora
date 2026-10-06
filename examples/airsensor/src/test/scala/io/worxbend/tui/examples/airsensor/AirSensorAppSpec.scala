package io.worxbend.tui.examples.airsensor

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import java.net.{Authenticator, CookieHandler, ProxySelector}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.time.Duration as JDuration
import java.util.{Locale, Optional}
import java.util.concurrent.{CompletableFuture, Executor}
import javax.net.ssl.{SSLContext, SSLParameters}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Every condition that waits on an `Async` continuation landing — a sensor read, a poll-timer delivery — waits through
  * [[Pilot.waitUntil]] rather than a hand-rolled poll: `waitForIdle` proves the posted event queue drained, not that a
  * later render-thread drain delivered the read. Polling a condition rather than sleeping a fixed time also survives a
  * parallel test run starving the render thread for a while.
  */
final class AirSensorAppSpec extends AnyFunSuite:

  private val clean = Reading(co2Ppm = 640, pm25 = 4.1, tvocIndex = 72, temperatureC = 21.2)
  private val foul  = Reading(co2Ppm = 1900, pm25 = 90.0, tvocIndex = 380, temperatureC = 31.0)

  /** The poll interval used by every test that drives the app by hand: long enough that no timer fires behind the
    * assertions, so only the `r` key produces a second reading.
    */
  private val Manual = 10.seconds

  private def withApp[A](
      script: Vector[Either[String, Reading]],
      interval: FiniteDuration = Manual,
  )(body: (AirSensorApp, Pilot, HeadlessBackend) => A): A =
    val backend = HeadlessBackend(Size(96, 30))
    val app     = AirSensorApp(FakeSensor(script), interval)
    // `runWith` takes the headless backend; `run()` would open the real TTY. The `val _` discards its Either so the
    // block types as Unit, which `-Wunused:all -Werror` insists on.
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      body(app, pilot, backend)
    }

  test("the first reading fills the hero panel and every metric card"):
    withApp(Vector(Right(clean))) { (app, pilot, _) =>
      pilot.waitUntil("the first reading to render")(pilot.screenText.contains("640 ppm"))

      val screen = pilot.screenText
      assert(screen.contains("AQI 23")) // 4.1 ug/m3 interpolated onto the EPA's first breakpoint
      assert(screen.contains("640 ppm"))
      assert(screen.contains("4.1 ug/m3"))
      assert(screen.contains("72 index"))
      assert(screen.contains("21.2 C"))
      assert(screen.contains("Good"))   // the band as a word, not only as a colour
      assert(app.status.peek == Status.Ready)
      assert(app.history.peek == Vector(clean))
      assert(pilot.readOnRenderThread(app.worstBand.peek) == Band.Good)

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("a failed poll explains itself and keeps the last good reading on screen"):
    withApp(Vector(Right(clean), Left("sensor offline"))) { (app, pilot, _) =>
      pilot.waitUntil("the first reading to render")(pilot.screenText.contains("640 ppm"))

      pilot.press("r")
      pilot.waitUntil("the failure message to render")(pilot.screenText.contains("sensor offline"))

      val screen = pilot.screenText
      assert(screen.contains("showing the last good reading"))
      assert(screen.contains("640 ppm")) // the cards are still there — a failure never blanks the pane
      assert(app.status.peek == Status.Failed("sensor offline"))
      assert(app.history.peek == Vector(clean))

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("readings arrive on the poll timer with no key presses"):
    withApp(Vector(Right(clean), Right(foul)), interval = 150.millis) { (app, pilot, backend) =>
      val drawsBefore = backend.drawCount
      // poll rather than sleeping a fixed time: under parallel test load the timer thread may be starved for a while
      pilot.waitUntil("the poll timer to deliver a second reading")(app.history.peek.sizeIs >= 2)

      assert(app.history.peek.take(2) == Vector(clean, foul))
      assert(backend.drawCount > drawsBefore) // the timer alone drove repaints
      assert(pilot.screenText.contains("History · last"))

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("the band word and the worst-band summary follow the reading"):
    withApp(Vector(Right(clean), Right(foul))) { (app, pilot, _) =>
      pilot.waitUntil("the first reading to render")(pilot.screenText.contains("640 ppm"))
      assert(pilot.readOnRenderThread(app.worstBand.peek) == Band.Good)
      assert(pilot.screenText.contains("air quality: Good"))

      pilot.press("r")
      pilot.waitUntil("the second reading to render")(pilot.screenText.contains("1900 ppm"))

      val screen = pilot.screenText
      assert(screen.contains("Unhealthy"))
      assert(screen.contains("Elevated")) // temperature bands on a range, so 31 C is uncomfortable, not unhealthy
      assert(pilot.readOnRenderThread(app.worstBand.peek) == Band.Unhealthy)

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("h collapses the history pane and ? opens the help overlay"):
    withApp(Vector(Right(clean))) { (_, pilot, _) =>
      pilot.waitUntil("the history pane to render")(pilot.screenText.contains("History · last"))

      pilot.press("h").waitForIdle()
      assert(!pilot.screenText.contains("History · last"))

      pilot.press("?").waitForIdle()
      assert(pilot.screenText.contains("airsensor keys"))

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  /** An `HttpClient` whose `send` always throws `InterruptedException`, so the interrupt contract of
    * `AirGradientClient.fetch` is testable without a socket. Every other method is a stub; the client under test never
    * calls them.
    */
  private val interruptingHttpClient: HttpClient = new HttpClient:
    override def send[T](request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler[T]): HttpResponse[T] =
      throw InterruptedException("stop requested")
    override def sendAsync[T](
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler[T],
    ): CompletableFuture[HttpResponse[T]]                 = unsupported
    override def sendAsync[T](
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler[T],
        pushPromiseHandler: HttpResponse.PushPromiseHandler[T],
    ): CompletableFuture[HttpResponse[T]]                 = unsupported
    override def newWebSocketBuilder(): WebSocket.Builder = unsupported
    override def cookieHandler(): Optional[CookieHandler] = unsupported
    override def connectTimeout(): Optional[JDuration]    = unsupported
    override def followRedirects(): HttpClient.Redirect   = unsupported
    override def proxy(): Optional[ProxySelector]         = unsupported
    override def sslContext(): SSLContext                 = unsupported
    override def sslParameters(): SSLParameters           = unsupported
    override def authenticator(): Optional[Authenticator] = unsupported
    override def version(): HttpClient.Version            = unsupported
    override def executor(): Optional[Executor]           = unsupported

  private def unsupported: Nothing = throw UnsupportedOperationException("test stub")

  test("an interrupted read reports the failure and restores the thread's interrupt status"):
    val client = AirGradientClient(httpClient = interruptingHttpClient)
    assert(client.read() == Left("stop requested"))
    // `Thread.interrupted()` also clears the flag, so the restored interrupt cannot leak into the rest of the suite —
    // `waitUntil` sleeps, and a lingering interrupt would turn every later sleep into a thrown exception
    assert(Thread.currentThread().isInterrupted)
    assert(Thread.interrupted())

  test("an AirGradient /measures/current payload parses into a reading"):
    val body =
      """{"wifi":-52,"serialno":"ecda3b1eaaaa","rco2":812,"pm01":3.1,"pm02":6.8,"pm10":7.4,
        |"tvocIndex":143,"noxIndex":1,"atmp":21.4,"rhum":47.2,"boot":9}""".stripMargin

    assert(AirGradientClient.readingFrom(body) == Right(Reading(812.0, 6.8, 143.0, 21.4)))
    assert(AirGradientClient.readingFrom("""{"rco2":812}""") == Left("missing field 'pm02'"))

  test("AQI interpolates between the EPA's PM2.5 breakpoints"):
    assert(math.round(AirQuality.aqiFromPm25(0.0)) == 0L)
    assert(math.round(AirQuality.aqiFromPm25(9.0)) == 50L)
    assert(math.round(AirQuality.aqiFromPm25(35.4)) == 100L)
    assert(math.round(AirQuality.aqiFromPm25(1000.0)) == 500L)
    // banding is upper-inclusive, so 9.0 ug/m3 is still the top of Good
    assert(Metric.Pm25.classify(9.0) == Band.Good)
    assert(Metric.Pm25.classify(9.1) == Band.Moderate)

  // Every other assertion in this file that names a decimal — "4.1 ug/m3", "21.2 C" — passes in CI only because CI
  // runs under en_US. `Metric.format` used to be `s"%.${decimals}f".format(value)`, which formats through the
  // *default* FORMAT locale: on a European developer's machine the same reading drew "4,1 ug/m3" and this suite
  // failed with no code change. It now pins Locale.ROOT, the way procmon's `decimal` always did.
  //
  // Setting the process-wide default is safe here: `TuiTests` forks one JVM per test class, and the `finally`
  // restores it for the rest of this one.
  test("rendered readings keep ASCII decimal points under a comma-decimal default locale"):
    val previous = Locale.getDefault
    try
      Locale.setDefault(Locale.GERMANY)
      withApp(Vector(Right(clean))) { (_, pilot, _) =>
        pilot.waitUntil("the first reading to render")(pilot.screenText.contains("640 ppm"))

        val screen = pilot.screenText
        assert(screen.contains("4.1 ug/m3"))
        assert(screen.contains("21.2 C"))
        // the negatives are the half that fails against the old implementation: it still *contained* a number, just
        // not one any of this file's other assertions would recognise
        assert(!screen.contains("4,1"))
        assert(!screen.contains("21,2"))

        pilot.press("q")
        assert(pilot.awaitTermination())
      }
    finally Locale.setDefault(previous)
