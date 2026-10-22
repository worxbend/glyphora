package io.worxbend.tui.core

import Spring.State

/** A damped-spring integrator (à la Charm's Harmonica) for physical, non-linear motion — scrolling, progress fills,
  * layout transitions.
  *
  * Unlike an [[Easing]], a spring has no fixed duration: call [[step]] each tick with the current
  * [[io.worxbend.tui.core.Spring.State State]] and it eases toward `target`, overshooting or settling per `frequency`
  * (stiffness) and `damping` (`< 1` bouncy, `1` critically damped, `> 1` sluggish). Integrated semi-implicitly, stable
  * for the usual TUI tick rates.
  *
  * `deltaTime` must be positive: a spring with a non-positive step can never advance, so the documented
  * `while !settled(...) do step(...)` loop would hang. A defect in the caller, hence a construction-time throw.
  */
final case class Spring(frequency: Double = 6.0, damping: Double = 0.7, deltaTime: Double = 1.0 / 60):

  require(deltaTime > 0, s"a spring needs a positive time step, got $deltaTime")

  /** The next [[io.worxbend.tui.core.Spring.State State]] as the value eases one `deltaTime` step toward `target` — the
    * integration loop is `while !settled(...) do state = step(state, ...)`, with the position and velocity travelling
    * together rather than as two bare doubles a caller has to keep in step by hand.
    */
  def step(current: State, target: Double): State =
    val accel       = -frequency * frequency * (current.position - target) - 2 * damping * frequency * current.velocity
    val newVelocity = current.velocity + accel * deltaTime
    State(current.position + newVelocity * deltaTime, newVelocity)

  /** Whether the value has effectively settled on `target` (within `epsilon` of it and nearly at rest). */
  def settled(position: Double, velocity: Double, target: Double, epsilon: Double = 1e-3): Boolean =
    math.abs(position - target) < epsilon && math.abs(velocity) < epsilon

object Spring:

  /** Position and velocity at one tick of a [[Spring]] — the value [[Spring.step]] takes and returns, named so the
    * integration loop reads as what it is instead of as a `(Double, Double)` whose field order the caller has to
    * remember.
    */
  final case class State(position: Double, velocity: Double)
