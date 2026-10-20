package io.worxbend.tui.widgets

import io.worxbend.tui.core.RgbaImage

import java.nio.file.{Files, Path}

import scala.util.Using
import scala.util.control.NonFatal

/** Native-safe PNG loading built on `java.util.zip`, with no dependency on `java.desktop`.
  *
  * Encoded input is capped at 32 MiB, decoded size at 4 Mi pixels and inflated scanlines at 32 MiB. The decoder grows
  * storage only as real compressed data arrives, never from an untrusted header alone. Malformed, unsupported and
  * oversized inputs return readable `Left`s; VM resource failures are not intercepted. Blocking I/O and decoding belong
  * off the render thread. For JPEG, GIF, BMP or interlaced PNG on the JVM, explicitly import [[ImageIoLoader]]; its
  * `fromFile` / `fromBytes` replace the former ImageLoader methods without dropping those formats.
  */
object ImageLoader:

  /** Reads a bounded PNG file using the built-in decoder, including in native images. */
  def readPng(path: Path): Either[String, RgbaImage] =
    try
      Using.resource(Files.newInputStream(path)) { input =>
        decodePng(input.readNBytes(PngDecoder.MaxInputBytes + 1))
      }
    catch case NonFatal(error) => Left(s"failed to read $path: ${describe(error)}")

  /** Decodes non-interlaced greyscale, truecolour or palette PNG with or without alpha, within the object limits. */
  def decodePng(bytes: Array[Byte]): Either[String, RgbaImage] = PngDecoder.decode(bytes)

  private def describe(error: Throwable): String = Option(error.getMessage).getOrElse(error.getClass.getName)
