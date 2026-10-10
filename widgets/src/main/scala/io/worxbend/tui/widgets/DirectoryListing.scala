package io.worxbend.tui.widgets

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Immutable entry metadata. Directory identities must be resolved before publication, never during painting. */
final case class DirectoryEntry(path: Path, isDirectory: Boolean, identity: Path)

/** An immutable filesystem snapshot, safe to acquire on a worker and pass back to its owning render thread. Links are
  * followed, including outside the root; this browser is not a filesystem sandbox.
  */
final case class DirectoryListing(directory: Path, identity: Path, entries: Vector[DirectoryEntry])

object DirectoryListing:
  /** Blocking acquisition only; touches no widget state. Failure is distinguishable from an empty directory. */
  def load(directory: Path): Either[Throwable, DirectoryListing] =
    try
      val identity = directory.toRealPath()
      val stream   = Files.list(directory)
      try
        val entries = stream
          .iterator()
          .asScala
          .map { path =>
            val isDirectory = Files.isDirectory(path)
            DirectoryEntry(path, isDirectory, if isDirectory then path.toRealPath() else path)
          }
          .toVector
          .sortBy(entry => (!entry.isDirectory, entry.path.getFileName.toString.toLowerCase(Locale.ROOT)))
        Right(DirectoryListing(directory, identity, entries))
      finally stream.close()
    catch case NonFatal(error) => Left(error)

/** Observable acquisition state; failure stays failed until explicitly retried or invalidated. */
enum DirectoryLoadState:
  case Unloaded, Loading, Loaded
  case Failed(error: Throwable)

/** A caller-owned acquisition generation. Create on the render thread, use off-thread, install on the owner thread. */
final class DirectoryLoadRequest private[widgets] (val directory: Path)
