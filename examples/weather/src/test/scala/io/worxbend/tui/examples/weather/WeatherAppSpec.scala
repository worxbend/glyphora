package io.worxbend.tui.examples.weather

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

private final class FakeWeatherClient(
    response: String => Either[WeatherError, WeatherReport],
    delay: String => FiniteDuration = _ => Duration.Zero,
) extends WeatherClient:
  @volatile var lastRequestedCity: Option[String] = None

  def fetch(city: String): Either[WeatherError, WeatherReport] =
    lastRequestedCity = Some(city)
    val pause = delay(city)
    if pause > Duration.Zero then Thread.sleep(pause.toMillis)
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

  test("a slower earlier search cannot overwrite the result of a newer one"):
    // Kyiv answers after 500ms, Lisbon immediately: Kyiv's fetch is still in flight when Lisbon's result lands, and
    // its late completion is the stale write the request-generation guard exists to drop. Without the guard the
    // screen would flip back to Kyiv's figures once the slow fetch finished.
    val slow    = sampleReport.copy(city = "Kyiv", country = "", temperatureC = -3.0)
    val client  = FakeWeatherClient(
      response = city => Right(if city == "Kyiv" then slow else sampleReport),
      delay = city => if city == "Kyiv" then 500.millis else Duration.Zero,
    )
    val backend = HeadlessBackend(Size(60, 16))
    val app     = WeatherApp(client)
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      pilot.typeText("Kyiv").pressKey(KeyCode.Enter)
      pilot.typeText("Lisbon").pressKey(KeyCode.Enter)
      pilot.waitUntil("the fetched conditions to render")(pilot.screenText.contains("Lisbon, Portugal"))

      // Wait out Kyiv's in-flight delay (plus its delivery to the render thread), then Lisbon must still own the
      // screen and Kyiv's temperature must never have appeared.
      Thread.sleep(750)
      assert(pilot.screenText.contains("Lisbon, Portugal"))
      assert(!pilot.screenText.contains("-3.0"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination(2.seconds))
    }
