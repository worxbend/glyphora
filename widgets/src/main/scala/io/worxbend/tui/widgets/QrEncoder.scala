package io.worxbend.tui.widgets

import java.nio.charset.StandardCharsets

/** The error-correction level of a [[QrCode]]: how much of the symbol can be damaged and still read, traded against how
  * much data fits. Roughly 7 %, 15 %, 25 % and 30 % of codewords recoverable.
  */
enum QrEcc:
  case Low, Medium, Quartile, High

/** An encoded QR symbol: `size × size` modules, `dark(x, y)` for each, plus the version and mask chosen.
  *
  * Immutable once built. The module grid is the symbol alone, without the quiet zone a reader needs around it — that is
  * the renderer's to add.
  */
final class QrMatrix private[widgets] (val version: Int, val mask: Int, modules: Array[Array[Boolean]]):

  /** Modules along one side: `17 + 4 * version`. */
  val size: Int = modules.length

  /** Whether the module at column `x`, row `y` is dark; `false` outside the symbol. */
  def dark(x: Int, y: Int): Boolean = x >= 0 && y >= 0 && x < size && y < size && modules(y)(x)

/** A QR Code Model 2 encoder for byte-mode data, versions 1 to 10, every error-correction level — ISO/IEC 18004,
  * implemented here so the widgets module stays dependency-free.
  *
  * The text is encoded as UTF-8 in one byte-mode segment. The smallest version that holds it at the requested level is
  * chosen (the level is never boosted), the data is split into Reed–Solomon blocks over GF(256) and interleaved, the
  * modules are placed along the standard zigzag, and of the eight masks the one with the lowest penalty score under the
  * standard's four rules is applied, the lowest-numbered mask winning a tie. Pure; safe to call from any thread.
  */
private[widgets] object QrEncoder:

  val MaxVersion = 10

  /** `text` encoded, or why it cannot be: more UTF-8 bytes than version 10 holds at `ecc`. */
  def encode(text: String, ecc: QrEcc, forcedMask: Option[Int] = None): Either[String, QrMatrix] =
    val data = text.getBytes(StandardCharsets.UTF_8)
    (1 to MaxVersion).find(version => bitsNeeded(data.length, version) <= dataCodewords(version, ecc) * 8) match
      case None          =>
        Left(s"${data.length} bytes do not fit a version-$MaxVersion QR code at level $ecc")
      case Some(version) =>
        val codewords = withErrorCorrection(dataBits(data, version, ecc), version, ecc)
        val symbol    = ModuleGrid(version)
        symbol.drawFunctionPatterns()
        symbol.placeCodewords(codewords)
        val mask      = forcedMask.getOrElse((0 until 8).minBy { candidate =>
          symbol.applyMask(candidate)
          symbol.drawFormat(ecc, candidate)
          val score = symbol.penalty
          symbol.applyMask(candidate) // XOR again to undo
          score
        })
        symbol.applyMask(mask)
        symbol.drawFormat(ecc, mask)
        Right(QrMatrix(version, mask, symbol.modules.map(_.clone())))

  // ---- capacity -------------------------------------------------------------------------------------------------

  /** Error-correction codewords per block, by level then version. */
  private val EcPerBlock: Map[QrEcc, Vector[Int]] = Map(
    QrEcc.Low      -> Vector(7, 10, 15, 20, 26, 18, 20, 24, 30, 18),
    QrEcc.Medium   -> Vector(10, 16, 26, 18, 24, 16, 18, 22, 22, 26),
    QrEcc.Quartile -> Vector(13, 22, 18, 26, 18, 24, 18, 22, 20, 24),
    QrEcc.High     -> Vector(17, 28, 22, 16, 22, 28, 26, 26, 24, 28),
  )

  /** Reed–Solomon blocks, by level then version. */
  private val Blocks: Map[QrEcc, Vector[Int]] = Map(
    QrEcc.Low      -> Vector(1, 1, 1, 1, 1, 2, 2, 2, 2, 4),
    QrEcc.Medium   -> Vector(1, 1, 1, 2, 2, 4, 4, 4, 5, 5),
    QrEcc.Quartile -> Vector(1, 1, 2, 2, 4, 4, 6, 6, 8, 8),
    QrEcc.High     -> Vector(1, 1, 2, 4, 4, 4, 5, 6, 8, 8),
  )

  /** Modules left for data and error correction once every function pattern is drawn. */
  private def rawDataModules(version: Int): Int =
    var result = (16 * version + 128) * version + 64
    if version >= 2 then
      val alignments = version / 7 + 2
      result -= (25 * alignments - 10) * alignments - 55
      if version >= 7 then result -= 36
    result

  private def totalCodewords(version: Int): Int = rawDataModules(version) / 8

  private def dataCodewords(version: Int, ecc: QrEcc): Int =
    totalCodewords(version) - EcPerBlock(ecc)(version - 1) * Blocks(ecc)(version - 1)

  /** Bits of the character-count field in byte mode: 8 up to version 9, 16 from version 10. */
  private def countBits(version: Int): Int = if version <= 9 then 8 else 16

  private def bitsNeeded(bytes: Int, version: Int): Int =
    if bytes >= (1 << countBits(version)) then Int.MaxValue else 4 + countBits(version) + bytes * 8

  // ---- data -----------------------------------------------------------------------------------------------------

  /** The data codewords: mode, count, the bytes, the terminator, byte padding, then the alternating pad codewords. */
  private def dataBits(data: Array[Byte], version: Int, ecc: QrEcc): Array[Int] =
    val bits                                  = scala.collection.mutable.ArrayBuffer.empty[Boolean]
    def append(value: Int, length: Int): Unit = (length - 1 to 0 by -1).foreach(i => bits += ((value >>> i) & 1) == 1)
    append(0x4, 4)
    append(data.length, countBits(version))
    data.foreach(byte => append(byte & 0xff, 8))
    val capacity                              = dataCodewords(version, ecc) * 8
    append(0, math.min(4, capacity - bits.size))
    append(0, (8 - bits.size % 8) % 8)
    var pad                                   = 0xec
    while bits.size < capacity do
      append(pad, 8)
      pad ^= 0xec ^ 0x11
    bits.grouped(8).map(_.foldLeft(0)((acc, bit) => (acc << 1) | (if bit then 1 else 0))).toArray

  /** The data split into blocks, each followed by its Reed–Solomon codewords, interleaved into the final sequence. */
  private def withErrorCorrection(data: Array[Int], version: Int, ecc: QrEcc): Array[Int] =
    val blockCount  = Blocks(ecc)(version - 1)
    val ecLength    = EcPerBlock(ecc)(version - 1)
    val total       = totalCodewords(version)
    val shortBlocks = blockCount - total % blockCount
    val shortLength = total / blockCount
    val divisor     = ReedSolomon.divisor(ecLength)
    var offset      = 0
    val blocks      = (0 until blockCount).map { index =>
      val length = shortLength - ecLength + (if index < shortBlocks then 0 else 1)
      val slice  = data.slice(offset, offset + length)
      offset += length
      (slice, ReedSolomon.remainder(slice, divisor))
    }
    val longest     = shortLength - ecLength + 1
    val out         = Array.newBuilder[Int]
    (0 until longest).foreach(i => blocks.foreach((slice, _) => if i < slice.length then out += slice(i)))
    (0 until ecLength).foreach(i => blocks.foreach((_, ec) => out += ec(i)))
    out.result()

  private object ReedSolomon:

    private def multiply(x: Int, y: Int): Int =
      var z = 0
      (7 to 0 by -1).foreach { i =>
        z = (z << 1) ^ ((z >>> 7) * 0x11d)
        z ^= ((y >>> i) & 1) * x
      }
      z

    /** The generator polynomial of `degree`, highest coefficient first and the leading 1 dropped. */
    def divisor(degree: Int): Array[Int] =
      val result = Array.fill(degree)(0)
      result(degree - 1) = 1
      var root   = 1
      (0 until degree).foreach { _ =>
        (0 until degree).foreach { j =>
          result(j) = multiply(result(j), root)
          if j + 1 < degree then result(j) ^= result(j + 1)
        }
        root = multiply(root, 0x02)
      }
      result

    def remainder(data: Array[Int], divisor: Array[Int]): Array[Int] =
      val result = Array.fill(divisor.length)(0)
      data.foreach { byte =>
        val factor = byte ^ result(0)
        System.arraycopy(result, 1, result, 0, result.length - 1)
        result(result.length - 1) = 0
        divisor.indices.foreach(i => result(i) ^= multiply(divisor(i), factor))
      }
      result

  // ---- the module grid ------------------------------------------------------------------------------------------

  /** The grid being built, with a parallel record of which modules are function patterns rather than data. */
  private final class ModuleGrid(version: Int):
    val size: Int                               = 17 + 4 * version
    val modules: Array[Array[Boolean]]          = Array.fill(size, size)(false)
    private val function: Array[Array[Boolean]] = Array.fill(size, size)(false)

    private def setFunction(x: Int, y: Int, dark: Boolean): Unit =
      modules(y)(x) = dark
      function(y)(x) = true

    def drawFunctionPatterns(): Unit =
      (0 until size).foreach { i =>
        setFunction(6, i, i % 2 == 0)
        setFunction(i, 6, i % 2 == 0)
      }
      drawFinder(3, 3)
      drawFinder(size - 4, 3)
      drawFinder(3, size - 4)
      val positions = alignmentPositions
      val last      = positions.size - 1
      for
        i <- positions.indices
        j <- positions.indices
        if !((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0))
      do drawAlignment(positions(i), positions(j))
      drawFormat(QrEcc.Medium, 0) // reserves the format areas; overwritten once the mask is known
      drawVersion()

    private def drawFinder(cx: Int, cy: Int): Unit =
      for
        dy <- -4 to 4
        dx <- -4 to 4
        x = cx + dx
        y = cy + dy
        if x >= 0 && x < size && y >= 0 && y < size
      do
        val distance = math.max(math.abs(dx), math.abs(dy))
        setFunction(x, y, distance != 2 && distance != 4)

    private def drawAlignment(cx: Int, cy: Int): Unit =
      for
        dy <- -2 to 2
        dx <- -2 to 2
      do setFunction(cx + dx, cy + dy, math.max(math.abs(dx), math.abs(dy)) != 1)

    private def alignmentPositions: Seq[Int] =
      if version == 1 then Seq.empty
      else
        val count = version / 7 + 2
        val step  = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        6 +: (0 until count - 1).map(i => size - 7 - i * step).reverse

    def drawFormat(ecc: QrEcc, mask: Int): Unit =
      val levelBits            = ecc match
        case QrEcc.Low      => 1
        case QrEcc.Medium   => 0
        case QrEcc.Quartile => 3
        case QrEcc.High     => 2
      val data                 = levelBits << 3 | mask
      var remainder            = data
      (0 until 10).foreach(_ => remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537))
      val bits                 = (data << 10 | remainder) ^ 0x5412
      def bit(i: Int): Boolean = ((bits >>> i) & 1) == 1
      (0 to 5).foreach(i => setFunction(8, i, bit(i)))
      setFunction(8, 7, bit(6))
      setFunction(8, 8, bit(7))
      setFunction(7, 8, bit(8))
      (9 until 15).foreach(i => setFunction(14 - i, 8, bit(i)))
      (0 until 8).foreach(i => setFunction(size - 1 - i, 8, bit(i)))
      (8 until 15).foreach(i => setFunction(8, size - 15 + i, bit(i)))
      setFunction(8, size - 8, true)

    private def drawVersion(): Unit =
      if version >= 7 then
        var remainder = version
        (0 until 12).foreach(_ => remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1f25))
        val bits      = version << 12 | remainder
        (0 until 18).foreach { i =>
          val dark = ((bits >>> i) & 1) == 1
          val a    = size - 11 + i % 3
          val b    = i / 3
          setFunction(a, b, dark)
          setFunction(b, a, dark)
        }

    def placeCodewords(codewords: Array[Int]): Unit =
      var bitIndex = 0
      var right    = size - 1
      while right >= 1 do
        if right == 6 then right = 5
        (0 until size).foreach { vertical =>
          (0 until 2).foreach { j =>
            val x      = right - j
            val upward = ((right + 1) & 2) == 0
            val y      = if upward then size - 1 - vertical else vertical
            if !function(y)(x) && bitIndex < codewords.length * 8 then
              modules(y)(x) = ((codewords(bitIndex >>> 3) >>> (7 - (bitIndex & 7))) & 1) == 1
              bitIndex += 1
          }
        }
        right -= 2

    /** XORs `mask` over every data module; applying the same mask twice restores the grid. */
    def applyMask(mask: Int): Unit =
      for
        y <- 0 until size
        x <- 0 until size
        if !function(y)(x)
      do
        val invert = mask match
          case 0 => (x + y)         % 2 == 0
          case 1 => y               % 2 == 0
          case 2 => x               % 3 == 0
          case 3 => (x + y)         % 3 == 0
          case 4 => (x / 3 + y / 2) % 2 == 0
          case 5 => x * y           % 2 + x * y % 3 == 0
          case 6 => (x * y          % 2 + x * y % 3) % 2 == 0
          case _ => ((x + y)        % 2 + x * y % 3) % 2 == 0
        if invert then modules(y)(x) = !modules(y)(x)

    /** The standard's penalty: runs of five or more, 2×2 blocks, finder-like patterns, and dark/light imbalance. */
    def penalty: Int =
      var result                             = 0
      def lineScore(at: Int => Boolean): Int =
        var score   = 0
        var runDark = false
        var run     = 0
        val history = new RunHistory(size)
        (0 until size).foreach { i =>
          if at(i) == runDark then
            run += 1
            if run == 5 then score += 3 else if run > 5 then score += 1
          else
            history.add(run)
            if !runDark then score += history.finderLike * 40
            runDark = at(i)
            run = 1
        }
        score + history.terminate(runDark, run) * 40
      (0 until size).foreach { y => result += lineScore(x => modules(y)(x)) }
      (0 until size).foreach { x => result += lineScore(y => modules(y)(x)) }
      for
        y <- 0 until size - 1
        x <- 0 until size - 1
      do
        val c = modules(y)(x)
        if c == modules(y)(x + 1) && c == modules(y + 1)(x) && c == modules(y + 1)(x + 1) then result += 3
      val dark                               = modules.iterator.map(_.count(identity)).sum
      val total                              = size * size
      val k                                  = (math.abs(dark * 20 - total * 10) + total - 1) / total - 1
      result + k * 10

  /** The last seven run lengths along a line, newest first, for spotting `1:1:3:1:1` finder look-alikes. */
  private final class RunHistory(size: Int):
    private val runs  = Array.fill(7)(0)
    private var first = true

    def add(length: Int): Unit =
      val padded = if first then length + size else length
      first = false
      System.arraycopy(runs, 0, runs, 1, runs.length - 1)
      runs(0) = padded

    /** How many finder-like patterns (with four light modules on either side) end at the newest light run. */
    def finderLike: Int =
      val n    = runs(1)
      val core = n > 0 && runs(2) == n && runs(3) == n * 3 && runs(4) == n && runs(5) == n
      (if core && runs(0) >= n * 4 && runs(6) >= n then 1 else 0) +
        (if core && runs(6) >= n * 4 && runs(0) >= n then 1 else 0)

    /** Closes the line as if it ran into the light border, and scores what that last stretch completes. */
    def terminate(runDark: Boolean, run: Int): Int =
      var length = run
      if runDark then
        add(length)
        length = 0
      add(length + size)
      finderLike
