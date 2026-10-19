package io.worxbend.tui.examples.gallery

import io.worxbend.tui.dsl.*

/** Every page, grouped by family in the order the sidebar lists them. Builds fresh pages — and so fresh states — on
  * every call; the app calls it once.
  */
object Gallery:

  def pages(fixtures: GalleryFixtures): Vector[GalleryPage] =
    LayoutPages.all() ++
      DisplayPages.all(fixtures) ++
      DataPages.data(fixtures) ++
      DataPages.charts() ++
      DataPages.loading() ++
      InteractionPages.textEntry(fixtures) ++
      InteractionPages.choice(fixtures) ++
      InteractionPages.navigation() ++
      InteractionPages.windows()

/** `gallery`: every factory the DSL exports, one page each, grouped by family — the visual index of the toolkit and,
  * through its test, a golden frame per factory.
  *
  * The sidebar is a list: Up/Down (or a click) picks a page, Tab moves into the page so its widget takes the keys, and
  * `alt+n`/`alt+p` step pages from anywhere. Every page keeps its own state for the whole run, so a slider moved on one
  * page is still where it was when the user comes back. `ctrl+q` quits.
  *
  * `fixtures` is the directory, date and instant the pages treat as the outside world — the real ones by default, fixed
  * ones in the test.
  */
class GalleryApp(fixtures: GalleryFixtures = GalleryFixtures.live()) extends TuiApp:

  /** The pages, built once: each owns the states it draws, for the life of the app. */
  val pages: Vector[GalleryPage] = Gallery.pages(fixtures)

  private val sidebar: ListState = ListState(selected = Some(0))

  private def current: Int = sidebar.selected.getOrElse(0)

  private def step(by: Int): Unit =
    sidebar.selected = Some(Math.floorMod(current + by, pages.size))

  override def bindings: KeyBindings = KeyBindings(
    binding("alt+n", "next page")(step(1)),
    binding("alt+p", "previous page")(step(-1)),
    binding("ctrl+q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    val page  = pages(current)
    val items = pages.map(p => Line(Seq(Span(f"${p.family.take(10)}%-10s ", Style.Default.dim), Span(p.factory))))
    column(
      row(
        panel(s"Gallery · ${pages.size} factories")(list(items, sidebar).autofocus.fill).length(34),
        panel(s"${page.factory} — ${page.family}")(
          text(page.summary).dim.length(1),
          spacer.length(1),
          page.view,
        ).fill,
      ).fill,
      statusBar(bindings),
    )

/** `TuiApp` supplies `main`: with no terminal it exits 1 printing `terminal not supported`, like every example. */
object Main extends GalleryApp()
