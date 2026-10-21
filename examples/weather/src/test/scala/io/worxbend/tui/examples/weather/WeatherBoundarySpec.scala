package io.worxbend.tui.examples.weather

import org.scalatest.funsuite.AnyFunSuite

import com.sun.net.httpserver.HttpServer
import java.net.{Authenticator, CookieHandler, InetSocketAddress, ProxySelector, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.time.Duration as JDuration
import java.util.Optional
import java.util.concurrent.{CompletableFuture, Executor}
import javax.net.ssl.{SSLContext, SSLParameters}

final class WeatherBoundarySpec extends AnyFunSuite:
  private val location   = """{"results":[{"name":"Lisbon","latitude":38.7,"longitude":-9.1}]}"""
  private val conditions =
    """{"current":{"temperature_2m":22.5,"relative_humidity_2m":61,"wind_speed_10m":14,"weather_code":1}}"""

  private final class RoutedClient(route: HttpRequest => URI) extends HttpClient:
    val requests         = scala.collection.mutable.ArrayBuffer.empty[HttpRequest]
    private val delegate = HttpClient.newHttpClient()
    override def send[T](request: HttpRequest, handler: HttpResponse.BodyHandler[T]): HttpResponse[T] =
      requests += request
      delegate.send(HttpRequest.newBuilder(route(request)).GET().build(), handler)
    override def sendAsync[T](
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler[T],
    ): CompletableFuture[HttpResponse[T]]                                                             = unsupported
    override def sendAsync[T](
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler[T],
        pushPromiseHandler: HttpResponse.PushPromiseHandler[T],
    ): CompletableFuture[HttpResponse[T]]                                                             = unsupported
    override def newWebSocketBuilder(): WebSocket.Builder                                             = unsupported
    override def cookieHandler(): Optional[CookieHandler]                                             = unsupported
    override def connectTimeout(): Optional[JDuration]                                                = unsupported
    override def followRedirects(): HttpClient.Redirect                                               = unsupported
    override def proxy(): Optional[ProxySelector]                                                     = unsupported
    override def sslContext(): SSLContext                                                             = unsupported
    override def sslParameters(): SSLParameters                                                       = unsupported
    override def authenticator(): Optional[Authenticator]                                             = unsupported
    override def version(): HttpClient.Version                                                        = unsupported
    override def executor(): Optional[Executor]                                                       = unsupported

  private def unsupported: Nothing = throw UnsupportedOperationException("unused test stub")

  private def withResponse[A](responses: Vector[(Int, String)])(body: RoutedClient => A): A =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val cursor = java.util.concurrent.atomic.AtomicInteger(0)
    server.createContext(
      "/",
      exchange =>
        val (status, payload) = responses(cursor.getAndIncrement())
        val bytes             = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        try exchange.getResponseBody.write(bytes)
        finally exchange.close(),
    )
    server.start()
    try body(RoutedClient(_ => URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/")))
    finally server.stop(0)

  test("HTTP requests encode city names, preserve timeout, and decode both responses with optional defaults"):
    withResponse(Vector(200 -> location, 200 -> conditions)) { http =>
      assert(
        OpenMeteoClient(http).fetch("São Paulo & x") ==
          Right(WeatherReport("Lisbon", "", 22.5, 61, 14, true, 1))
      )
      assert(http.requests.size == 2)
      assert(http.requests.head.uri().getRawQuery.endsWith("name=S%C3%A3o+Paulo+%26+x"))
      assert(http.requests(1).uri().getQuery.contains("latitude=38.7&longitude=-9.1"))
      assert(http.requests.forall(_.method() == "GET"))
      assert(http.requests.forall(_.timeout().get() == JDuration.ofSeconds(8)))
    }

  test("HTTP errors and missing or malformed payloads retain their domain error classifications"):
    withResponse(Vector(503 -> "unavailable")) { http =>
      assert(OpenMeteoClient(http).fetch("x") == Left(WeatherError.NetworkFailure("HTTP 503")))
      assert(http.requests.size == 1)
    }
    for body <- Vector("{}", """{"results":[]}""", """{"results":false}""") do
      withResponse(Vector(200 -> body)) { http =>
        assert(OpenMeteoClient(http).fetch("x") == Left(WeatherError.CityNotFound("x")))
      }
    withResponse(Vector(200 -> "{")) { http =>
      assert(OpenMeteoClient(http).fetch("x").left.toOption.exists {
        case WeatherError.UnexpectedResponse(_) => true
        case _                                  => false
      })
    }
    withResponse(Vector(200 -> location, 200 -> """{"current":{}}""")) { http =>
      assert(OpenMeteoClient(http).fetch("x") == Left(WeatherError.UnexpectedResponse("missing temperature")))
    }

  test("I/O and interruption map to network failure and restore only the interrupt flag"):
    val io          = RoutedClient(_ => throw java.io.IOException("offline"))
    assert(OpenMeteoClient(io).fetch("x") == Left(WeatherError.NetworkFailure("offline")))
    val interrupted = RoutedClient(_ => throw InterruptedException("stop"))
    try
      assert(OpenMeteoClient(interrupted).fetch("x") == Left(WeatherError.NetworkFailure("stop")))
      assert(Thread.currentThread().isInterrupted)
    finally { val _ = Thread.interrupted() }

  test("JSON model covers escapes, nested values, numeric coercion and existing permissive syntax"):
    val parsed = Json.parse("""{"s":"a\n\u65e5","a":[true,false,null,-1.25e2],"n":2.9}""").toOption.get
    assert(parsed.field("s").flatMap(_.asString).contains("a\n日"))
    assert(
      parsed
        .field("a")
        .flatMap(_.asArray)
        .contains(Vector(Json.JBool(true), Json.JBool(false), Json.JNull, Json.JNumber(-125)))
    )
    assert(parsed.field("n").flatMap(_.asInt).contains(2))
    assert(Json.parse("[1,]") == Right(Json.JArray(Vector(Json.JNumber(1)))))
    assert(Json.parse("01") == Right(Json.JNumber(1)))
    for body <- Vector("", "true false", "[1", "{x:1}", "1e", "\"\\q\"") do assert(Json.parse(body).isLeft, body)
