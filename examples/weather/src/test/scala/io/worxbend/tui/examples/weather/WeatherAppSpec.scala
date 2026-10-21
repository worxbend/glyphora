package io.worxbend.tui.examples.weather

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import io.worxbend.tui.runtime.RenderThread

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.DurationInt

private final class FakeWeatherClient(
    response: String => Either[WeatherError, WeatherReport]
) extends WeatherClient:
  @volatile var lastRequestedCity: Option[String] = None

  def fetch(city: String): Either[WeatherError, WeatherReport] =
    lastRequestedCity = Some(city)
    response(city)

final class WeatherAppSpec extends AnyFunSuite:

  private val sampleReport = WeatherReport(
    city = "Lisbon",
    country = "Portugal",
    temperatureC = 22.5,
    humidityPercent = 61.0,
    windKph = 14.0,
    isDay = true,
    conditionCode = 1,
  )

  test("typing a city and pressing Enter shows the fetched conditions"):
    val client  = FakeWeatherClient(_ => Right(sampleReport))
    val backend = HeadlessBackend(Size(60, 16))
    val app     = WeatherApp(client)
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      assert(pilot.a11yReport(app).isEmpty, pilot.a11yReport(app).mkString("\n"))
      pilot.typeText("Lisbon").pressKey(KeyCode.Enter)
      pilot.waitUntil("the fetched conditions to render")(pilot.screenText.contains("Lisbon, Portugal"))

      assert(client.lastRequestedCity.contains("Lisbon"))
      assert(pilot.screenText.contains("Mainly clear"))
      assert(pilot.screenText.contains("22.5"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination(2.seconds))
    }

  test("a failed lookup shows an error instead of crashing"):
    val client  = FakeWeatherClient(_ => Left(WeatherError.CityNotFound("Nowhereville")))
    val backend = HeadlessBackend(Size(60, 16))
    val app     = WeatherApp(client)
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      pilot.typeText("Nowhereville").pressKey(KeyCode.Enter)
      pilot.waitUntil("the failure message to render")(pilot.screenText.contains("Couldn't fetch Nowhereville"))

      assert(pilot.screenText.contains("Couldn't fetch Nowhereville"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination(2.seconds))
    }

  for outcome <- Vector("success", "failure", "throw") do
    test(s"a delivered stale $outcome cannot overwrite a newer search"):
      val started   = CountDownLatch(1)
      val release   = CountDownLatch(1)
      val owner     = AtomicReference[RenderThread.RenderLoop]()
      val delivered = AtomicBoolean(false)
      val client    = FakeWeatherClient { city =>
        if city != "Kyiv" then Right(sampleReport)
        else
          started.countDown()
          assert(release.await(5, TimeUnit.SECONDS), "stale request was not released")
          outcome match
            case "success" => Right(sampleReport.copy(city = "Kyiv", country = "", temperatureC = -3.0))
            case "failure" => Left(WeatherError.NetworkFailure("stale failure"))
            case _         => throw IllegalStateException("stale exception")
      }
      val backend   = HeadlessBackend(Size(60, 16))
      val app       = WeatherApp(client)
      Pilot.using(backend) {
        RenderThread.observingRegistration(owner.set)(app.runWith(backend))
      } { pilot =>
        try
          pilot.waitForIdle()
          pilot.typeText("Kyiv").pressKey(KeyCode.Enter)
          assert(started.await(5, TimeUnit.SECONDS), "old request never started")
          pilot.typeText("Lisbon").pressKey(KeyCode.Enter)
          pilot.waitUntil("new result")(pilot.screenText.contains("Lisbon, Portugal"))
          release.countDown()
          // isIdle includes Async's reserved delivery slot. A FIFO probe can observe it true only
          // after the old continuation has run, not merely after fetch returned or the frame settled.
          pilot.waitUntil("old Async continuation to be processed") {
            val loop = owner.get()
            val _    = loop.execute { if loop.isIdle then delivered.set(true) }
            delivered.get()
          }
          pilot.waitForIdle()
          assert(pilot.screenText.contains("Lisbon, Portugal"))
          assert(!pilot.screenText.contains("-3.0"))
          assert(!pilot.screenText.contains("stale failure"))
          assert(!pilot.screenText.contains("stale exception"))
          pilot.pressKey(KeyCode.Escape)
          assert(pilot.awaitTermination(2.seconds))
        finally release.countDown()
      }
