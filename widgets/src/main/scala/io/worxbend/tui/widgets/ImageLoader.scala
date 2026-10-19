package io.worxbend.tui.widgets

import io.worxbend.tui.core.RgbaImage

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import scala.util.Using
import scala.util.control.NonFatal

/** Decodes picture files into an [[RgbaImage]] for [[PixelImage]], two ways:
  *
  *   - [[decodePng]] / [[readPng]] — a built-in PNG decoder on `java.util.zip`. Works everywhere, native images
  *     included, and links nothing from `java.desktop`.
  *   - [[fromBytes]] / [[fromFile]] — `javax.imageio`, for JPEG, GIF, BMP and anything else it reads. On the JVM this
  *     just works. In a GraalVM native image it does not, out of the box: verified on GraalVM CE 25.0.2 (Linux x64), a
  *     `--no-fallback` build that reaches it succeeds but ships `libawt*.so` beside the binary, and the first decode
  *     aborts with `NoClassDefFoundError: java/awt/GraphicsEnvironment` from AWT's JNI start-up. Collecting AWT
  *     metadata with the tracing agent is the documented way past that; converting the pictures to PNG is simpler.
  *
  * The two halves are separate methods, not one with a fallback, so an app that calls only the PNG decoder never makes
  * `javax.imageio` reachable and its native image stays a single self-contained file. Encoding pictures for the
  * terminal never goes through `javax.imageio` either — the iTerm2 encoder writes its PNGs with `java.util.zip`.
  *
  * Every failure is a `Left` with a readable message; nothing throws. Blocking I/O: call it off the render thread (an
  * `Async` task, or at start-up) for anything larger than an icon.
  */
object ImageLoader:

  /** The PNG file at `path`, read by the built-in decoder — the loader to use in a native image. See [[decodePng]]. */
  def readPng(path: Path): Either[String, RgbaImage] =
    try decodePng(Files.readAllBytes(path))
    catch case NonFatal(error) => Left(s"failed to read $path: ${describe(error)}")

  /** The PNG picture in `bytes`, read with `java.util.zip` alone — no `java.desktop`, so it works unchanged in a native
    * image. Greyscale, truecolour and palette files with or without alpha; interlaced files are a `Left`.
    */
  def decodePng(bytes: Array[Byte]): Either[String, RgbaImage] = PngDecoder.decode(bytes)

  /** The picture in the file at `path`, in any format `javax.imageio` reads. JVM only in practice: see the object. */
  def fromFile(path: Path): Either[String, RgbaImage] =
    try Using.resource(Files.newInputStream(path))(decode(_, path.toString))
    catch case NonFatal(error) => Left(s"failed to read $path: ${describe(error)}")

  /** The picture encoded in `bytes`, in any format `javax.imageio` reads. JVM only in practice: see the object. */
  def fromBytes(bytes: Array[Byte]): Either[String, RgbaImage] = decode(ByteArrayInputStream(bytes), "the given bytes")

  private def decode(input: InputStream, source: String): Either[String, RgbaImage] =
    try
      // `ImageIO.read` reports an unsupported format by returning null rather than throwing
      Option(ImageIO.read(input))
        .toRight(s"unsupported image format: $source")
        .map(decoded => RgbaImage.tabulate(decoded.getWidth, decoded.getHeight)(decoded.getRGB))
    catch case NonFatal(error) => Left(s"failed to decode $source: ${describe(error)}")

  private def describe(error: Throwable): String = Option(error.getMessage).getOrElse(error.getClass.getName)
