package io.worxbend.tui.widgets

import io.worxbend.tui.core.Constraint
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import java.util.Locale

/** One place that states the whole invariant: **a widget's output never depends on the JVM's default locale.**
  *
  * Case folding and number formatting are the two ways it leaks in, and both have bitten this codebase:
  * `"ID".toLowerCase` is `"ıd"` under `Locale.forLanguageTag("tr")`, so a filter needle of `"id"` matched nothing;
  * `f"$value%.1f"` writes `"12,5"` under `Locale.GERMANY`, so an axis label disagreed with the number in the column
  * beside it. Every site now names [[Locale.ROOT]], and each widget carries its own regression test next to itself —
  * `DataTableLocaleSpec`, `ChartSpec`, `DirectoryTreeSpec`, `SyntaxHighlighterSpec`. This file is the statement they
  * are all instances of, so a new widget has one place to be added to.
  *
  * Each test runs its body three times, once under each of a dotless-i locale, a comma-decimal locale and
  * [[Locale.ROOT]], and requires all three answers to be *the same value* — not merely "some plausible string". Under a
  * default-locale implementation the Turkish and German runs diverge from the ROOT run, which is what makes these
  * assertions worth their lines.
  *
  * Mutating `Locale.getDefault` is safe here because `TuiTests` forks one JVM per test class (`testForkGrouping` in
  * `build.mill`), and [[underEachLocale]] restores the previous default in a `finally` regardless.
  */
final class LocaleIndependenceSpec extends AnyFunSuite:

  /** Turkish folds `I` to a dotless `ı` (U+0131, which also sorts *above* `z`); Germany writes a decimal comma; ROOT is
    * the answer every one of them has to agree with.
    */
  private val Locales: Seq[Locale] = Seq(Locale.forLanguageTag("tr"), Locale.GERMANY, Locale.ROOT)

  private def underEachLocale[A](body: => A): Seq[(Locale, A)] =
    val previous = Locale.getDefault
    try
      Locales.map { locale =>
        Locale.setDefault(locale)
        (locale, body)
      }
    finally Locale.setDefault(previous)

  /** Runs `body` under each of [[Locales]] and requires every answer to equal `expected`, naming the locale that
    * disagreed — a bare `false` would leave the reader to guess which of the three broke.
    */
  private def assertLocaleIndependent[A](expected: A)(body: => A): Unit =
    underEachLocale(body).foreach { (locale, actual) =>
      assert(actual == expected, s"under default locale '$locale'")
    }

  // ---- case folding: matching ----

  /** The single row's only cell is `"ID"` and the needle is `"id"`, so the match exists *only* after case folding: a
    * fixture that also matched literally would pass against the broken implementation too.
    */
  private val idTable: DataTable[Int] =
    DataTable.fromStrings(
      columns = Seq("ID"),
      rows = Seq(Seq("ID")),
      widths = Seq(Constraint.Length(4)),
    )

  test("DataTable.filteredRows matches a cell that only case folding matches, under every default locale"):
    assertLocaleIndependent(Seq(KeyedRow(0, Seq("ID")))) {
      // a fresh state per run: the filtered view is memoized on the state, so a shared one would answer the second
      // and third locales out of the first one's cache and prove nothing
      val state = DataTableState()
      state.setFilter("id")
      idTable.filteredRows(state)
    }

  // ---- case folding: ordering ----

  test("DirectoryTree lists entries in one order under every default locale"):
    val root = Files.createTempDirectory("glyphora-locale")
    root.toFile.deleteOnExit()
    Seq("Index.txt", "jazz.txt", "zebra.txt").foreach(name => Files.writeString(root.resolve(name), ""))
    // ROOT folds "Index.txt" to "index.txt", which sorts before "jazz.txt"; Turkish folds the 'I' to 'ı', which
    // sorts after "zebra.txt" — so a default-locale sort key reverses which name the reader sees first.
    assertLocaleIndependent(Vector("Index.txt", "jazz.txt", "zebra.txt")) {
      // fresh state per run for the same reason: DirectoryTreeState caches each directory listing after one read
      val state = DirectoryTreeState(root)
      state.loadVisible()
      state.visiblePaths().map(_.getFileName.toString)
    }

  // ---- case folding: name resolution ----

  /** Every alias `Language.of` understands, plus the two fallback shapes. Kept complete on purpose: this is the only
    * place `sc`, `sbt`, `shell`, `zsh` and `console` are asserted at all, and it is the guard that a future alias
    * carrying an `i` — `ini`, `kotlin`, `swift` — cannot be resolved through the default locale.
    */
  private val Aliases: Seq[(String, Language)] = Seq(
    "scala"   -> Language.Scala,
    "sc"      -> Language.Scala,
    "sbt"     -> Language.Scala,
    "json"    -> Language.Json,
    "bash"    -> Language.Bash,
    "sh"      -> Language.Bash,
    "shell"   -> Language.Bash,
    "zsh"     -> Language.Bash,
    "console" -> Language.Bash,
    "  scala" -> Language.Scala, // the info string is trimmed before it is folded
    "rust"    -> Language.Generic,
    ""        -> Language.Generic,
  )

  test("Language.of resolves every alias, in either case, under every default locale"):
    val expected = Aliases.flatMap((_, language) => Seq(language, language))
    assertLocaleIndependent(expected) {
      // the input is upper-cased through ROOT so that only the *resolution* varies between runs, never the argument
      Aliases.flatMap((name, _) => Seq(Language.of(name), Language.of(name.toUpperCase(Locale.ROOT))))
    }

  // ---- number formatting ----

  /** `Chart.formatBound` has two branches and only the whole-number one was ever rendered by a test. A fractional bound
    * goes through `"%.1f"`, which is the branch a comma-decimal default locale rewrites — and `DataTable`'s own
    * numeric-column probe (`cell.toDoubleOption`) accepts only a `'.'`, so the two widgets would stop agreeing about
    * what a number looks like on the same screen.
    */
  test("Chart's fractional axis bounds print with a dot under every default locale"):
    val chart = Chart(Seq.empty, Bounds(0.0, 1.0), Bounds(0.5, 12.5), ChartOptions(showLabels = true))
    // "12.5" is four columns wide, so the gutter is four and the axis stands at x = 4; the y-max label sits on the
    // top row and the y-min label one row above the axis, both right-aligned against the rule.
    assertLocaleIndependent(Seq("12.5│", "    │", "    │", "    │", " 0.5│", "    └───────────────")) {
      trimmedLines(rendered(chart, 20, 6))
    }
