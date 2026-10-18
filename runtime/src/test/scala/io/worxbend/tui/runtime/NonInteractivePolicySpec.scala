package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** Covers the [[NonInteractivePolicy]] field of [[RunnerConfig]]: the default is the pre-0.20 behaviour, the builder
  * round-trips, and the field takes part in equality, hashing and `toString` like every other one. Kept apart from
  * `RunnerConfigSpec` so the suite that enumerates every field is edited in one place when a field is added.
  */
final class NonInteractivePolicySpec extends AnyFunSuite:

  test("the default policy is Fail — a TTY-less process still fails to start"):
    assert(RunnerConfig.Default.nonInteractive == NonInteractivePolicy.Fail)
    assert(RunnerConfig().nonInteractive == NonInteractivePolicy.Fail)

  test("withNonInteractive round-trips and leaves every other field alone"):
    val config = RunnerConfig.Default.withTickRate(100.millis).withNonInteractive(NonInteractivePolicy.PrintFinalFrame)
    assert(config.nonInteractive == NonInteractivePolicy.PrintFinalFrame)
    assert(config.tickRate.contains(100.millis))
    assert(config == RunnerConfig(tickRate = Some(100.millis), nonInteractive = NonInteractivePolicy.PrintFinalFrame))

  test("the policy takes part in equality, hashing and toString"):
    val plain = RunnerConfig.Default.withNonInteractive(NonInteractivePolicy.Plain)
    assert(plain != RunnerConfig.Default)
    assert(plain.hashCode != RunnerConfig.Default.hashCode)
    assert(plain.toString == "RunnerConfig(nonInteractive=Plain)")
    assert(RunnerConfig.Default.toString == "RunnerConfig.Default")
