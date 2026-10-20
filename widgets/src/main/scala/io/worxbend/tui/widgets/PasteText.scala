package io.worxbend.tui.widgets

/** Line-ending policies for bracketed and clipboard paste. Pure and backend-independent. */
object PasteText:
  /** CRLF and bare CR are both logical line breaks, as are LF line endings. */
  def multiline(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

  /** A one-row field retains word boundaries rather than concatenating pasted lines. */
  def singleLine(text: String): String = multiline(text).replace('\n', ' ')

  /** Search prompts accept only the first logical line of a paste. */
  def firstLine(text: String): String = multiline(text).takeWhile(_ != '\n')
