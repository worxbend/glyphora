package io.worxbend.tui.examples.gallery

import io.worxbend.tui.dsl.*

/** One page of the gallery: a single exported element factory, drawn with representative data.
  *
  * `factory` is the factory's name exactly as it appears in the `export Element.{…}` block of `dsl.scala` — the test
  * matches pages to that block by this name, so a page cannot be renamed away from the factory it shows. `family` is
  * the group the sidebar lists it under. `view` is evaluated every frame like any other view; the states it closes over
  * were created once, when the page was, and are owned by the page for the life of the gallery. `props` are the simple
  * values the page's view reads that the gallery lets the user edit beside it ([[PropEditor]]); their starting values
  * are what the page's golden frame shows.
  */
final class GalleryPage(
    val factory: String,
    val family: String,
    val summary: String,
    val view: View,
    val props: Seq[Prop] = Seq.empty,
):
  override def toString: String = s"GalleryPage($factory)"

  /** The page as the gallery lays it out: inside a column, so a `.length(n)` on the page's own root is honoured the way
    * it is in an application rather than stretched to the whole frame.
    */
  def framed: View = column(view)

/** Everything a page reads from outside the program: the directory the file pages browse, and the date and instant the
  * calendar-like and log pages treat as "now". The live gallery uses the real ones; the golden test pins all three so
  * every frame is reproducible.
  */
final case class GalleryFixtures(directory: java.nio.file.Path, today: java.time.LocalDate, now: java.time.Instant)

object GalleryFixtures:

  /** The working directory, today and now — what the interactive gallery shows. */
  def live(): GalleryFixtures =
    GalleryFixtures(java.nio.file.Path.of("").toAbsolutePath, java.time.LocalDate.now(), java.time.Instant.now())
