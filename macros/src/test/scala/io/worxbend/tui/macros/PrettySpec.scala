package io.worxbend.tui.macros

import org.scalatest.funsuite.AnyFunSuite

/** Shapes for the derivation to walk, top level so the compiler synthesises their mirrors. */
enum Speed:
  case Slow, Fast

final case class Endpoint(host: String, port: Int)

final case class Service(
    name: String,
    speed: Speed,
    endpoints: List[Endpoint],
    retries: Option[Int],
    limits: Map[String, Double],
)

sealed trait Shape
final case class Circle(radius: Double) extends Shape
case object Dot                         extends Shape

/** A user type that teaches the derivation about itself through an explicit given. */
final case class Secret(value: String)
object Secret:
  given Pretty[Secret] = Pretty.atom(_ => "<redacted>")

final case class Credentials(user: String, secret: Secret)

/** `Pretty` describes values to a [[PrettyShape]] without reflection. This shape spells the description out as a
  * compact string, so the expected values below are written by hand from the type definitions rather than computed.
  */
final class PrettySpec extends AnyFunSuite:

  private object Spelled extends PrettyShape[String]:
    def atom(text: String): String                                   = text
    def sequence(name: String, items: Seq[String]): String           = items.mkString(s"$name[", ",", "]")
    def map(name: String, entries: Seq[(String, String)]): String    =
      entries.map((k, v) => s"$k=>$v").mkString(s"$name{", ",", "}")
    def product(name: String, fields: Seq[(String, String)]): String =
      fields.map((k, v) => s"$k:$v").mkString(s"$name(", ",", ")")

  private def spell[A](value: A)(using pretty: Pretty[A]): String = pretty.describe(value, Spelled)

  test("a nested case class with an enum, a list, an option and a map is described field by field"):
    val service = Service("api", Speed.Fast, List(Endpoint("a", 1), Endpoint("b\"q", 2)), Some(3), Map("cpu" -> 0.5))
    assert(
      spell(service) ==
        """Service(name:"api",speed:Fast(),endpoints:List[Endpoint(host:"a",port:1),Endpoint(host:"b\"q",port:2)],retries:Some[3],limits:Map{"cpu"=>0.5})"""
    )

  test("derivePretty gives the same description as the derived given"):
    val explicit = derivePretty[Endpoint]
    assert(explicit.describe(Endpoint("h", 8), Spelled) == """Endpoint(host:"h",port:8)""")

  test("a sealed hierarchy names the case, and a case object has no fields"):
    assert(spell[Shape](Circle(1.5)) == "Circle(radius:1.5)")
    assert(spell[Shape](Dot) == "Dot()")

  test("primitives, None, empty collections and escapes"):
    assert(spell(Option.empty[Int]) == "None")
    assert(spell(Vector.empty[String]) == "Vector[]")
    assert(spell('x') == "'x'")
    assert(spell("tab\there\nline") == "\"tab\\there\\nline\"")
    assert(spell(true) == "true")
    assert(spell(42L) == "42")
    assert(spell(Set(1)) == "Set[1]")
    assert(spell(Seq(1, 2)) == "Seq[1,2]")

  test("a given declared beside a type wins over the derivation"):
    assert(spell(Credentials("ann", Secret("hunter2"))) == "Credentials(user:\"ann\",secret:<redacted>)")
