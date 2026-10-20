package io.worxbend.tui.widgets

import io.worxbend.tui.core.RgbaImage

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import scala.util.Using
import scala.util.control.NonFatal

/** Explicit JVM-only image decoding: JPEG, GIF, BMP, interlaced PNG and installed ImageIO formats.
  *
  * Not re-exported by the DSL: import this object deliberately when AWT is available. Native applications should use
  * [[ImageLoader]] instead. Missing AWT linkage returns `Left`; a native image still needs AWT JNI metadata and shared
  * libraries if this adapter is reachable. Blocking I/O and decoding belong off the render thread. Malformed files,
  * unsupported formats and images larger than 4 Mi pixels return `Left`; VM resource failures are not intercepted.
  */
object ImageIoLoader:

  /** Decodes the first image in a file using JVM ImageIO. */
  def fromFile(path: Path): Either[String, RgbaImage] =
    try Using.resource(Files.newInputStream(path))(decode(_, path.toString))
    catch
      case NonFatal(error)     => Left(s"failed to read $path: ${describe(error)}")
      case error: LinkageError => Left(s"ImageIO is unavailable: ${describe(error)}")

  /** Decodes the first image in encoded bytes using JVM ImageIO. */
  def fromBytes(bytes: Array[Byte]): Either[String, RgbaImage] = decode(ByteArrayInputStream(bytes), "the given bytes")

  /** The half-block [[Image]] loader formerly exposed as `Image.fromFile`. All ImageIO formats remain available. */
  def imageFromFile(path: Path): Either[String, Image] = fromFile(path).map(Image.fromRgba)

  private def decode(input: InputStream, source: String): Either[String, RgbaImage] =
    try
      // Explicit memory stream: no process-global disk cache, and inspect dimensions before decoding the raster.
      Using.resource(new javax.imageio.stream.MemoryCacheImageInputStream(input)) { stream =>
        val readers = ImageIO.getImageReaders(stream)
        if !readers.hasNext then Left(s"unsupported image format: $source")
        else
          val reader = readers.next()
          try
            reader.setInput(stream)
            val width  = reader.getWidth(0)
            val height = reader.getHeight(0)
            if width <= 0 || height <= 0 || width.toLong * height > 4L * 1024 * 1024 then
              Left("image exceeds the 4 Mi-pixel limit")
            else
              val decoded = reader.read(0)
              Right(RgbaImage.tabulate(width, height)(decoded.getRGB))
          finally reader.dispose()
      }
    catch
      case NonFatal(error)     => Left(s"failed to decode $source: ${describe(error)}")
      case error: LinkageError => Left(s"ImageIO is unavailable: ${describe(error)}")

  private def describe(error: Throwable): String = Option(error.getMessage).getOrElse(error.getClass.getName)
