package io.worxbend.tui.core

import scala.concurrent.duration.{Duration, DurationInt, DurationLong, FiniteDuration}

/** A post-render frame transform (the tachyonfx model, original implementation): widgets render normally, then active
  * effects mutate the rendered cells based on elapsed time.
  *
  * An effect holds no clock of its own — it is a pure function of the `elapsed` it is handed, which is what keeps the
  * combinators ([[Effect.sequence]], [[Effect.repeat]], …) composable and replayable. The flip side is that **the
  * caller owns the clock**: whoever calls [[process]], directly or through `Frame.applyEffect` in `tui-runtime`, must
  * remember when this particular effect started and pass the time since then. `TuiApp.runEffect` in `tui-dsl` keeps
  * that timestamp per active effect, so a DSL app gets it for free; an app driving a bare `Runner` has to keep it
  * itself. Passing time since the frame, or since the application started, compiles and type-checks but leaves the
  * effect pinned at progress 1.0 or oscillating out of phase.
  */
trait Effect:

  /** Transforms the rendered frame in place. `elapsed` is measured from this effect's own start — see the class doc. */
  def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit

  /** Total running time; `Duration.Inf` for effects that never finish (pulse, marquee). */
  def duration: Duration

  final def isDone(elapsed: FiniteDuration): Boolean =
    duration match
      case finite: FiniteDuration => elapsed >= finite
      case _                      => false

  /** This effect confined to the cells `filter` accepts — see [[Effect.filtered]]. */
  final def filtered(filter: CellFilter): Effect = Effect.filtered(filter)(this)

object Effect:

  /** `effect` confined to the cells `filter` accepts: every cell the filter rejects is put back exactly as it was
    * rendered once `effect` has run. The filter is asked about the cell as rendered, before `effect` touched it, so
    * `filtered(CellFilter.fg(Color.Red))(fadeOut(…))` keeps fading the red text however dark it has become.
    *
    * Costs one snapshot of the buffer per frame. The duration is `effect`'s.
    */
  def filtered(filter: CellFilter)(effect: Effect): Effect =
    new Effect:
      def duration: Duration                                                 = effect.duration
      def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
        val rendered = buffer.snapshot
        effect.process(elapsed, buffer, area)
        area.foreachPosition: (x, y) =>
          val cell = rendered.get(x, y)
          if !filter.matches(cell, x, y, area) then buffer.set(x, y, cell)

  /** `effect` played with a spatial shape: each cell runs through `effect` at its own progress, which `pattern` derives
    * from the whole run's progress and the cell's position — a fade that blooms from the centre is
    * `patterned(Pattern.radial(center))(fadeIn(…))`.
    *
    * Each cell's progress is rounded to one of [[PatternLevels]] steps, `effect` is evaluated over a scratch copy of
    * the rendered frame once per step a cell needs, and each cell takes its result from the evaluation at its own step;
    * so the cost of a frame is bounded by that step count, not by the area. Effects that move content between cells
    * (`slideIn`) compose too, though a cell then shows whatever its own step put there.
    *
    * '''Evaluations are cached across frames.''' An evaluation depends only on its step, the rendered frame and the
    * area — `effect` is a pure function of the `elapsed` it is handed, as every effect is — so while the rendered frame
    * underneath stays the same, a step evaluated on an earlier frame is reused rather than evaluated again. A whole run
    * over unchanging content then evaluates `effect` at most once per step, `PatternLevels + 1` times in all, however
    * many frames it spans; a frame whose content differs from the last one (compared cell by cell) starts the cache
    * afresh. The cache holds one area's worth of cells per step reached, is dropped when the run ends, and makes the
    * returned effect render-thread-only: share the *description* across views, not one running value.
    *
    * The duration is `effect`'s; an endless `effect` has no run to spread over the area, so it plays unpatterned.
    */
  def patterned(pattern: Pattern)(effect: Effect): Effect =
    effect.duration match
      case total: FiniteDuration => PatternedEffect(pattern, effect, total)
      case _                     => effect

  /** How finely [[patterned]] resolves each cell's progress: 64 steps is smoother than a terminal's colour ramps show.
    */
  val PatternLevels: Int = 64

  /** Content fades up from the background: cell colors interpolate from black to their rendered values. */
  def fadeIn(duration: FiniteDuration, easing: Easing = Easing.QuadOut): Effect =
    fade(duration, easing, level = identity)

  /** Content fades away to black. */
  def fadeOut(duration: FiniteDuration, easing: Easing = Easing.QuadIn): Effect =
    fade(duration, easing, level = 1 - _)

  /** Cells materialize in a seeded pseudo-random order (tachyonfx's `coalesce`). */
  def coalesce(duration: FiniteDuration, easing: Easing = Easing.QuadOut, seed: Int = 42): Effect =
    scatter(duration, easing, seed, threshold = identity)

  /** Cells dissolve away in a seeded pseudo-random order. */
  def dissolve(duration: FiniteDuration, easing: Easing = Easing.QuadIn, seed: Int = 42): Effect =
    scatter(duration, easing, seed, threshold = 1 - _)

  /** Content reveals column by column, left to right. */
  def sweepIn(duration: FiniteDuration, easing: Easing = Easing.QuadOut): Effect =
    new TimedEffect(duration, easing):
      def transform(progress: Double, buffer: Buffer, area: Rect): Unit =
        val visibleColumns = math.round(progress * area.width).toInt
        eraseWhere(buffer, area)((x, _) => x - area.x >= visibleColumns)

  /** Content slides in from the right edge — [[slideIn]] travelling [[MotionDirection.Left]]. */
  def slideInFromRight(duration: FiniteDuration, easing: Easing = Easing.QuadOut): Effect =
    slideIn(MotionDirection.Left, duration, easing)

  /** Content slides into place travelling in `direction`: `Up` enters from the bottom edge, `Left` from the right one.
    * Cells the content has not reached yet are blank.
    */
  def slideIn(direction: MotionDirection, duration: FiniteDuration, easing: Easing = Easing.QuadOut): Effect =
    new TimedEffect(duration, easing):
      def transform(progress: Double, buffer: Buffer, area: Rect): Unit =
        val remaining        = 1 - progress
        // where the content currently sits relative to its resting place: a cell shows the source cell this far back
        val (shiftX, shiftY) = direction match
          case MotionDirection.Left  => (math.round(remaining * area.width).toInt, 0)
          case MotionDirection.Right => (-math.round(remaining * area.width).toInt, 0)
          case MotionDirection.Up    => (0, math.round(remaining * area.height).toInt)
          case MotionDirection.Down  => (0, -math.round(remaining * area.height).toInt)
        if shiftX != 0 || shiftY != 0 then
          val snapshot = buffer.snapshot
          var y        = area.y
          while y < area.bottom do
            // right to left, so a wide grapheme is written after the continuation column it then reclaims
            var x = area.right - 1
            while x >= area.x do
              val sourceX = x - shiftX
              val sourceY = y - shiftY
              val cell    = if area.contains(sourceX, sourceY) then snapshot.get(sourceX, sourceY) else Cell.Empty
              buffer.set(x, y, cell)
              x -= 1
            y += 1

  /** Cells reveal in reading order (row-major), like typing. */
  def typewriter(duration: FiniteDuration, easing: Easing = Easing.Linear): Effect =
    new TimedEffect(duration, easing):
      def transform(progress: Double, buffer: Buffer, area: Rect): Unit =
        val total   = area.area
        val visible = math.round(progress * total).toInt
        eraseWhere(buffer, area)((x, y) => (y - area.y) * area.width + (x - area.x) >= visible)

  /** Endless brightness oscillation with the given period, which is clamped to at least one millisecond. */
  def pulse(period: FiniteDuration = 1.second): Effect =
    new Effect:
      // the phase is computed at millisecond resolution, so a sub-millisecond period would divide by zero and unwind
      // the render loop; one millisecond is the shortest cycle that resolution can express (as in `Async.every`)
      private val periodMillis                                               = math.max(1L, period.toMillis)
      def duration: Duration                                                 = Duration.Inf
      def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
        val phase      = (elapsed.toMillis % periodMillis).toDouble / periodMillis
        val brightness = PulseMidBrightness + PulseBrightnessSwing * math.sin(phase * 2 * math.Pi)
        mapCells(buffer, area)(style => withFgScaled(style, brightness))

  /** Shifts every text cell's foreground around the HSL wheel: by the end of the run its hue has turned `hue` degrees
    * and its saturation and lightness have moved by `saturation` and `lightness` (fractions, clamped to `[0, 1]` as
    * [[Color.hsl]] does). A cell with no foreground of its own is shifted from white. `target` picks the layer — see
    * [[ColorTarget]]; with `Background` every cell's background is shifted instead, a cell with none from black.
    */
  def hslShift(
      hue: Double,
      saturation: Double,
      lightness: Double,
      duration: FiniteDuration,
      easing: Easing = Easing.Linear,
      target: ColorTarget = ColorTarget.Foreground,
  ): Effect =
    def shifted(color: Color, progress: Double): Color =
      val (h, s, l) = Color.toHsl(color)
      Color.hsl(h + hue * progress, s + saturation * progress, l + lightness * progress)
    new TimedEffect(duration, easing):
      def transform(progress: Double, buffer: Buffer, area: Rect): Unit =
        mapLayers(buffer, area, target)(
          fg = style => style.withFg(shifted(style.fg.getOrElse(Color.White), progress)),
          bg = style => style.withBg(shifted(style.bg.getOrElse(Color.Black), progress)),
        )

  /** Paints every text cell's foreground towards `color`, reaching it at the end of the run (an RGB mix). `target`
    * picks the layer — see [[ColorTarget]]; a background is mixed from black where a cell has none of its own.
    */
  def paint(
      color: Color,
      duration: FiniteDuration,
      easing: Easing = Easing.Linear,
      target: ColorTarget = ColorTarget.Foreground,
  ): Effect =
    new TimedEffect(duration, easing):
      def transform(progress: Double, buffer: Buffer, area: Rect): Unit =
        if progress > 0 then
          mapLayers(buffer, area, target)(
            fg = style => style.withFg(Color.mix(style.fg.getOrElse(Color.White), color, progress)),
            bg = style => style.withBg(Color.mix(style.bg.getOrElse(Color.Black), color, progress)),
          )

  /** Scrambles a share of the text cells — `intensity` in `[0, 1]` of them — into [[GlitchGlyphs]] for the length of
    * the run, picking new cells and glyphs [[GlitchRate]] times a second. Seeded and a pure function of `elapsed`, so a
    * given moment always shows the same frame; the frame the run ends on is clean.
    */
  def glitch(intensity: Double, duration: FiniteDuration, seed: Int = 42): Effect =
    val total = duration
    new Effect:
      def duration: Duration                                                 = total
      def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
        if elapsed < total && intensity > 0 then
          // which reshuffle this moment falls in, wrapped well inside Int so it can be folded into the noise seed
          val shuffle = Progress.steppedAtRate(elapsed, GlitchRate, GlitchShuffles)
          val frame   = seed + shuffle * NoiseMixSeed
          area.foreachPosition: (x, y) =>
            val cell = buffer.get(x, y)
            if !cell.isBlank && cellNoise(x, y, frame) < intensity then
              val glyph = (cellNoise(y, x, frame + 1) * GlitchGlyphs.length).toInt.min(GlitchGlyphs.length - 1)
              buffer.set(x, y, cell.copy(symbol = GlitchGlyphs(glyph)))

  /** The glyphs [[glitch]] scrambles cells into: block shades and line noise, all one column wide. */
  val GlitchGlyphs: IndexedSeq[String] =
    IndexedSeq("░", "▒", "▓", "█", "▚", "▞", "#", "%", "&", "@", "$", "*", "/", "\\")

  /** How many times a second [[glitch]] picks new cells and glyphs. */
  val GlitchRate: Double = 15.0

  private val GlitchShuffles: Int = 1 << 20

  /** Runs every text cell through `symbols` in order, then settles it on its real glyph: the run is cut into one more
    * step than there are symbols, and the last step is the content. The cell keeps its style throughout. An empty
    * `symbols` changes nothing. [[ShadeSymbols]] is the light-to-solid block ramp.
    */
  def evolve(symbols: Seq[String], duration: FiniteDuration, easing: Easing = Easing.Linear): Effect =
    val steps = symbols.toIndexedSeq
    new TimedEffect(duration, easing):
      def transform(progress: Double, buffer: Buffer, area: Rect): Unit =
        val step = math.min(steps.length, math.floor(progress * (steps.length + 1)).toInt)
        if step < steps.length then
          val symbol = steps(step)
          area.foreachPosition: (x, y) =>
            val cell = buffer.get(x, y)
            if !cell.isBlank then buffer.set(x, y, cell.copy(symbol = symbol))

  /** Light shade to full block — the default ramp for [[evolve]]. */
  val ShadeSymbols: Seq[String] = Seq("░", "▒", "▓", "█")

  /** `effect` played backwards: its end first, its start last. An endless effect has no end to start from and plays
    * unchanged.
    */
  def reversed(effect: Effect): Effect =
    effect.duration match
      case total: FiniteDuration =>
        new Effect:
          def duration: Duration                                                 = total
          def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
            val back = total - elapsed
            effect.process(if back > Duration.Zero then back else Duration.Zero, buffer, area)
      case _                     => effect

  /** `effect` forwards then backwards, in twice its duration, so it ends on the frame it began with — a flash that
    * comes back. An endless effect plays unchanged.
    */
  def pingPong(effect: Effect): Effect =
    effect.duration match
      case total: FiniteDuration =>
        new Effect:
          def duration: Duration                                                 = total * 2
          def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
            val back = total * 2 - elapsed
            val at   =
              if elapsed <= total then elapsed else if back > Duration.Zero then back else Duration.Zero
            effect.process(at, buffer, area)
      case _                     => effect

  /** Runs `effects` one after another. */
  def sequence(effects: Effect*): Effect =
    new Effect:
      def duration: Duration = effects.foldLeft(Duration.Zero: Duration)((acc, e) => acc + e.duration)
      def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
        var remaining = elapsed
        var index     = 0
        // walk past every effect `elapsed` has already outlived, subtracting each whole duration as we go; the walk
        // stops at the first effect whose duration is not fully consumed, which is the one still playing
        var seeking   = true
        while seeking && index < effects.length do
          effects(index).duration match
            case finite: FiniteDuration if remaining >= finite =>
              remaining -= finite
              index += 1
            case _                                             => seeking = false
        if index < effects.length then effects(index).process(remaining, buffer, area)

  /** Runs `effects` simultaneously; done when the longest finishes. */
  def parallel(effects: Effect*): Effect =
    new Effect:
      def duration: Duration = effects.map(_.duration).maxOption.getOrElse(Duration.Zero)
      def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
        effects.foreach(_.process(elapsed, buffer, area))

  /** Waits `pause` before `effect` starts (the effect is held at progress zero during the wait). */
  def delay(pause: FiniteDuration, effect: Effect): Effect =
    new Effect:
      def duration: Duration                                                 = effect.duration + pause
      def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
        if elapsed >= pause then effect.process(elapsed - pause, buffer, area)
        else effect.process(Duration.Zero, buffer, area)

  /** Repeats `effect` `times` times; `times <= 0` plays it not at all. */
  def repeat(effect: Effect, times: Int): Effect =
    new Effect:
      // "not at all" is a zero-length effect, not an undefined one: `Duration.Inf * 0` is `Duration.Undefined`, which
      // `isDone` can never satisfy, so the runtime would keep the effect (and its per-tick redraws) alive forever
      def duration: Duration                                                 =
        if times <= 0 then Duration.Zero else effect.duration * times.toDouble
      def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
        if times > 0 then
          effect.duration match
            case finite: FiniteDuration if finite.toNanos > 0 =>
              val within = (elapsed.toNanos % finite.toNanos).nanos
              val cycle  = elapsed.toNanos / finite.toNanos
              if cycle < times then effect.process(within, buffer, area)
            case _                                            => effect.process(elapsed, buffer, area)

  // ---- shared machinery ----

  // `pulse` oscillates each cell's brightness between 10% and 100% of its colour: the midpoint is where the sine sits
  // at phase zero, the swing is how far either side of it the sine reaches. Kept as the midpoint/swing pair the sine
  // consumes rather than as a min/max pair, so the numbers the curve uses are the numbers written here.
  private val PulseMidBrightness   = 0.55
  private val PulseBrightnessSwing = 0.45

  private abstract class TimedEffect(val totalDuration: FiniteDuration, easing: Easing = Easing.Linear) extends Effect:
    def duration: Duration                                                       = totalDuration
    def transform(progress: Double, buffer: Buffer, area: Rect): Unit
    final def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
      transform(easing(Progress.normalized(elapsed, totalDuration)), buffer, area)

  /** Brightness ramp shared by [[fadeIn]] and [[fadeOut]]. `level` turns eased progress into the fraction of each
    * cell's colour that survives — `identity` fades content up, `1 - _` fades it down to black.
    */
  private def fade(totalDuration: FiniteDuration, easing: Easing, level: Double => Double): Effect =
    new TimedEffect(totalDuration, easing):
      def transform(progress: Double, buffer: Buffer, area: Rect): Unit =
        val scale = level(progress)
        if scale < 1.0 then mapCells(buffer, area)(style => withFgScaled(style, scale))

  /** Seeded per-cell reveal shared by [[coalesce]] and [[dissolve]]. `threshold` turns eased progress into the noise
    * cutoff a cell must beat to stay hidden — `identity` reveals cells as progress rises, `1 - _` hides them.
    */
  private def scatter(totalDuration: FiniteDuration, easing: Easing, seed: Int, threshold: Double => Double): Effect =
    new TimedEffect(totalDuration, easing):
      def transform(progress: Double, buffer: Buffer, area: Rect): Unit =
        val cutoff = threshold(progress)
        eraseWhere(buffer, area)((x, y) => cellNoise(x, y, seed) >= cutoff)

  // the mixing constants of the integer hash `cellNoise` applies per cell — arbitrary large odd constants, chosen so
  // neighbouring coordinates and neighbouring seeds avalanche apart in the product
  private val NoiseMixX: Int     = 374761393
  private val NoiseMixY: Int     = 668265263
  private val NoiseMixSeed: Int  = 987654323
  private val NoiseFinalMix: Int = 1274126177

  /** Deterministic per-cell noise in `[0, 1)` — a small integer hash, stable across frames. */
  private def cellNoise(x: Int, y: Int, seed: Int): Double =
    var h = x * NoiseMixX + y * NoiseMixY + seed * NoiseMixSeed
    h = (h ^ (h >>> 13)) * NoiseFinalMix
    ((h ^ (h >>> 16)) & 0x7fffffff).toDouble / Int.MaxValue

  private def eraseWhere(buffer: Buffer, area: Rect)(hide: (Int, Int) => Boolean): Unit =
    area.foreachPosition: (x, y) =>
      if hide(x, y) then buffer.set(x, y, Cell.Empty)

  /** Replaces every non-blank cell's style in `area` with `transform` of it.
    *
    * `transform` must be a pure function of the style: the walk runs through a [[MemoizedStyleTransform]], which
    * re-derives the result only when the style actually changes. Without that, a whole-frame fade built a fresh `Color`
    * and `Style` for each of a frame's ten thousand cells to arrive at the same answer.
    */
  /** [[mapCells]] for the colour layers `target` names: `fg` on text cells, `bg` on every cell, each memoized. */
  private def mapLayers(buffer: Buffer, area: Rect, target: ColorTarget)(fg: Style => Style, bg: Style => Style): Unit =
    if target == ColorTarget.Foreground then mapCells(buffer, area)(fg)
    else
      val foreground = MemoizedStyleTransform(fg)
      val background = MemoizedStyleTransform(bg)
      area.foreachPosition: (x, y) =>
        val cell  = buffer.get(x, y)
        val style = if target.foreground && !cell.isBlank then foreground(cell.style) else cell.style
        buffer.set(x, y, cell.copy(style = background(style)))

  private def mapCells(buffer: Buffer, area: Rect)(transform: Style => Style): Unit =
    val memoized = MemoizedStyleTransform(transform)
    area.foreachPosition: (x, y) =>
      val cell = buffer.get(x, y)
      if !cell.isBlank then buffer.set(x, y, cell.copy(style = memoized(cell.style)))

  /** Scales a style's foreground by `level`.
    *
    * `Color.rgb` rather than the bare `Rgb` constructor because the overshoot easings (`Back*`, `Elastic*`) dip below
    * zero mid-flight, and an unclamped negative channel would reach the terminal as a literal `38;2;-7;-7;-7`.
    */
  private def withFgScaled(style: Style, level: Double): Style =
    val (r, g, b) = Color.approximateRgb(style.fg.getOrElse(Color.White))
    style.withFg(
      Color.rgb(
        math.round(r * level).toInt,
        math.round(g * level).toInt,
        math.round(b * level).toInt,
      )
    )

/** The running form of [[Effect.patterned]], with its per-step evaluation cache. Render-thread-only. */
private final class PatternedEffect(pattern: Pattern, effect: Effect, total: FiniteDuration) extends Effect:

  // the frame and area the cached steps were evaluated over, and each evaluated step's cells (row-major over `area`)
  private var cachedFrame: Option[Buffer] = None
  private var cachedArea: Rect            = Rect(0, 0, 0, 0)
  private val evaluated                   = scala.collection.mutable.HashMap.empty[Int, Array[Cell]]

  def duration: Duration = total

  def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
    val progress     = Progress.normalized(elapsed, total)
    val rendered     = buffer.snapshot
    if area != cachedArea || !cachedFrame.contains(rendered) then
      evaluated.clear()
      cachedFrame = Some(rendered)
      cachedArea = area
    val levels       = new Array[Int](area.area)
    area.foreachPosition: (x, y) =>
      val local = pattern(progress, x, y, area)
      levels((y - area.y) * area.width + (x - area.x)) = math.round(local * Effect.PatternLevels).toInt
    lazy val scratch = Buffer(buffer.area)
    area.foreachPosition: (x, y) =>
      val index = (y - area.y) * area.width + (x - area.x)
      val cells = evaluated.getOrElseUpdate(levels(index), evaluate(levels(index), rendered, scratch, area))
      buffer.set(x, y, cells(index))
    // a finished run will not ask again; the cells are an area's worth per step, so do not keep them around
    if elapsed >= total then
      evaluated.clear()
      cachedFrame = None

  /** `effect` at step `level` over a fresh copy of `rendered`, read back as the area's cells in row-major order. */
  private def evaluate(level: Int, rendered: Buffer, scratch: Buffer, area: Rect): Array[Cell] =
    scratch.copyFrom(rendered)
    // the top step is `total` itself, not a division of it rounded down a few nanoseconds short of the end
    val at    =
      if level >= Effect.PatternLevels then total else (total.toNanos / Effect.PatternLevels * level).nanos
    effect.process(at, scratch, area)
    val cells = new Array[Cell](area.area)
    area.foreachPosition((x, y) => cells((y - area.y) * area.width + (x - area.x)) = scratch.get(x, y))
    cells
