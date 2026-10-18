package io.worxbend.tui.widgets.lexers

import io.worxbend.tui.core.Line
import io.worxbend.tui.widgets.{Lexer, SyntaxTheme}

/** Unified-diff lines: each line is one token, classified by its first characters. File headers (`diff`, `index`,
  * `---`, `+++`) are checked before the `+`/`-` content lines they would otherwise be mistaken for.
  */
private[widgets] object DiffLexer extends Lexer:
  val name: String         = "diff"
  val aliases: Set[String] = Set("diff", "patch", "udiff")

  def highlightLine(line: String, theme: SyntaxTheme): Line =
    val style =
      if Seq("diff ", "index ", "--- ", "+++ ").exists(line.startsWith) then theme.keyword
      else if line.startsWith("@@") then theme.function
      else if line.startsWith("+") then theme.inserted
      else if line.startsWith("-") then theme.deleted
      else if line.startsWith("\\") then theme.comment
      else theme.default
    LexerSupport.whole(line, style)
