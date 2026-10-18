package io.worxbend.tui.core

/** A spatial shape for an effect: turns the whole animation's progress into each cell's own, so a fade can bloom from
  * the centre, wipe diagonally or ripple instead of reaching every cell at once. [[Effect.patterned]] applies one.
  *
  * Every pattern picks a '''delay''' in `[0, 1]` for each cell from its position in the effect's area and hands it to
  * [[Progress.staggered]], which owns the time-to-position arithmetic. So for every pattern a cell's progress is in
  * `[0, 1]`, is `0` everywhere at progress 0 and is `1` everywhere at progress 1. `softness` is how much of the run
  * each cell's own transition takes: small values give a hard front, values towards `1` a broad gradient.
  *
  * Patterns are pure, immutable values with no clock; one can be shared between effects and threads.
  */
trait Pattern:

  /** The progress, in `[0, 1]`, of the cell at `(x, y)` inside `area` when the whole animation is at `progress`. */
  def apply(progress: Double, x: Int, y: Int, area: Rect): Double

object Pattern:

  /** The softness every pattern but [[checker]] defaults to: each cell's transition takes about a quarter of the run.
    */
  val DefaultSoftness: Double = 0.3

  /** Terminal cells are roughly twice as tall as they are wide; [[radial]] scales rows by this so its rings look round
    * on screen rather than squashed into ellipses.
    */
  private val CellAspect: Double = 2.0

  /** Grows outwards from `center` (in buffer coordinates, inside the area or not): the centre cell leads, the cell
    * farthest from it trails. Distances are measured with rows counted as two columns, so the rings read as circles.
    */
  def radial(center: Position, softness: Double = DefaultSoftness): Pattern =
    (progress, x, y, area) =>
      val farthest = farthestCorner(center, area)
      val delay    = if farthest <= 0.0 then 0.0 else distance(center, x, y) / farthest
      Progress.staggered(progress, delay, softness)

  /** Wipes from the top-left corner to the bottom-right one. */
  def diagonal(softness: Double = DefaultSoftness): Pattern =
    (progress, x, y, area) =>
      val span  = (area.width - 1) + (area.height - 1)
      val delay = if span <= 0 then 0.0 else ((x - area.x) + (y - area.y)).toDouble / span
      Progress.staggered(progress, delay, softness)

  /** A straight front travelling in `direction`: `Right` starts at the left column, `Up` at the bottom row. */
  def sweep(direction: MotionDirection, softness: Double = DefaultSoftness): Pattern =
    (progress, x, y, area) =>
      val delay = direction match
        case MotionDirection.Right => fraction(x - area.x, area.width)
        case MotionDirection.Left  => fraction(area.right - 1 - x, area.width)
        case MotionDirection.Down  => fraction(y - area.y, area.height)
        case MotionDirection.Up    => fraction(area.bottom - 1 - y, area.height)
      Progress.staggered(progress, delay, softness)

  /** A checkerboard of `size`-cell squares (at least one): one colour plays through the first half of the run, the
    * other through the second, so neighbouring squares alternate.
    */
  def checker(size: Int): Pattern =
    val side = math.max(1, size)
    (progress, x, y, area) =>
      val square = (x - area.x) / side + (y - area.y) / side
      // softness 1 makes each colour take exactly half the run: delay 0 plays 0 to 0.5, delay 1 plays 0.5 to 1
      Progress.staggered(progress, delay = (square % 2).toDouble, softness = 1.0)

  /** A left-to-right front whose edge ripples: `frequency` whole waves down the area's height, each pushing its rows a
    * quarter of the run ahead of or behind the straight sweep.
    */
  def wave(frequency: Double, softness: Double = DefaultSoftness): Pattern =
    (progress, x, y, area) =>
      val across = fraction(x - area.x, area.width)
      val down   = fraction(y - area.y, area.height)
      val ripple = 0.5 + 0.5 * math.sin(2.0 * math.Pi * frequency * down)
      val delay  = across * (1.0 - WaveAmplitude) + WaveAmplitude * ripple
      Progress.staggered(progress, delay, softness)

  private val WaveAmplitude: Double = 0.25

  /** `offset` of the way along an extent of `length` cells, `0` for the first and `1` for the last. */
  private def fraction(offset: Int, length: Int): Double =
    if length <= 1 then 0.0 else offset.toDouble / (length - 1)

  /** How far `center` is from the farthest corner of `area` — the distance every other cell's is a fraction of. */
  private def farthestCorner(center: Position, area: Rect): Double =
    val right  = math.max(area.x, area.right - 1)
    val bottom = math.max(area.y, area.bottom - 1)
    math.max(
      math.max(distance(center, area.x, area.y), distance(center, right, area.y)),
      math.max(distance(center, area.x, bottom), distance(center, right, bottom)),
    )

  private def distance(center: Position, x: Int, y: Int): Double =
    math.hypot((x - center.x).toDouble, (y - center.y) * CellAspect)
