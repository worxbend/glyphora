package io.worxbend.tui.widgets

import io.worxbend.tui.core.Text

/** Closes the syntax a half-written Markdown document leaves open, so text that is still arriving — an LLM's reply,
  * token by token — renders the way it will once it is complete instead of flashing literal markers.
  *
  * Only what is still being written is touched: an open code fence is closed, and on the *last* line an unclosed
  * `**strong`, `*emphasis` or `` `code `` gets its closer, a half-written `[label](ur` shows just its label, a `[lab`
  * still being typed drops its bracket, and a bare `#`/`##` with no heading text yet is held back. Earlier lines are
  * complete by definition and pass through untouched, as does well-formed text — `sanitize(doc) == doc` for every
  * document whose last line closes what it opens, which is why a finished stream renders exactly like the document. A
  * marker followed by a blank (`2 * 3`) is not an opener, so arithmetic is never "closed". Pure and thread-safe.
  */
object MarkdownSanitizer:

  private val BareHeading = """\s*#{1,6}\s*""".r

  /** `partial` with its unterminated syntax closed — see the object docs for exactly what is closed. */
  def sanitize(partial: String): String =
    val lines = Text.splitLines(partial)
    val fence = lines.count(_.trim.startsWith("```")) % 2 == 1
    if fence then partial + (if partial.endsWith("\n") then "```" else "\n```")
    else (lines.init :+ closeLine(lines.last)).mkString("\n")

  private def closeLine(line: String): String =
    if BareHeading.matches(line) then ""
    else if line.trim.startsWith("```") then line // a closing fence is syntax of its own, not an open code span
    else closeInline(line)

  /** Scans from `from` the way the parser does; the first marker it cannot close is the one being written. */
  private def closeInline(line: String): String =
    var index  = 0
    var result = Option.empty[String]
    while result.isEmpty && index < line.length do
      MarkdownParser.runLengthAt(line, index) match
        case Some(consumed) => index += consumed
        case None           =>
          val opened =
            if line.startsWith("**", index) then delimiter(line, index, "**")
            else
              line.charAt(index) match
                case '*'                            => delimiter(line, index, "*")
                case '`'                            => delimiter(line, index, "`")
                case '[' if atBoundary(line, index) => link(line, index)
                case _                              => None
          opened match
            case Some(closed) => result = Some(closed)
            case None         => index += 1
    result.getOrElse(line)

  /** An unclosed `marker` at `index`: closed when text follows it directly, dropped when nothing follows it yet. */
  private def delimiter(line: String, index: Int, marker: String): Option[String] =
    val content = line.drop(index + marker.length)
    if marker != "`" && !atBoundary(line, index) then None
    else if content.isEmpty || content.isBlank then Some(line.take(index).stripTrailing)
    else if marker != "`" && (content.head.isWhitespace || isPunctuation(content.head)) then None
    else Some(line + marker)

  private def atBoundary(line: String, index: Int): Boolean =
    index == 0 || line.charAt(index - 1).isWhitespace || isPunctuation(line.charAt(index - 1))

  private def isPunctuation(c: Char): Boolean =
    Character.getType(c) match
      case Character.CONNECTOR_PUNCTUATION | Character.DASH_PUNCTUATION | Character.START_PUNCTUATION |
          Character.END_PUNCTUATION | Character.INITIAL_QUOTE_PUNCTUATION | Character.FINAL_QUOTE_PUNCTUATION |
          Character.OTHER_PUNCTUATION =>
        true
      case _ => false

  /** `[label](partial` shows the label; `[label` (or `[label]` at the very end) drops the brackets. */
  private def link(line: String, index: Int): Option[String] =
    val middle = line.indexOf("](", index + 1)
    val label  = if middle < 0 then line.drop(index + 1) else line.slice(index + 1, middle)
    if middle >= 0 then Option.when(label.nonEmpty)(line.take(index) + label)
    else if !label.contains(']') then Some(line.take(index) + label)
    else
      Option.when(label.endsWith("]") && label.indexOf(']') == label.length - 1)(line.take(index) + label.dropRight(1))
