package io.worxbend.tui.dsl

/** Why a line-based text file — a theme file ([[Theme.parse]]), a keymap ([[Keymap.parse]]) — could not be read: the
  * 1-based `line` it went wrong on and what was wrong there. Prints as `line 4: …`.
  */
final case class LineError(line: Int, message: String):
  override def toString: String = s"line $line: $message"

/** One `key = value` line of such a file: its 1-based number, and both sides of its first `=`, trimmed. */
private[dsl] final case class KeyValueLine(number: Int, key: String, value: String)

/** The reader the `key = value` formats share: blank lines and lines whose first non-blank character is `#` are
  * skipped, and every other line is split at its first `=`. What a key or a value may be is the caller's business.
  */
private[dsl] object KeyValueLines:

  /** The `key = value` lines of `text` in order, or the first line with no `=` — reported as not being `shape`, the
    * format's own spelling of a line (`"id = keys"`, `"key = value"`).
    */
  def read(text: String, shape: String): Either[LineError, Vector[KeyValueLine]] =
    val lines = text.linesIterator.zipWithIndex.map((line, index) => (index + 1, line.trim)).toVector
    lines
      .filter((_, line) => line.nonEmpty && !line.startsWith("#"))
      .foldLeft[Either[LineError, Vector[KeyValueLine]]](Right(Vector.empty)) { case (acc, (number, line)) =>
        acc.flatMap { read =>
          line.indexOf('=') match
            case -1     => Left(LineError(number, s"expected '$shape', found '$line'"))
            case equals => Right(read :+ KeyValueLine(number, line.take(equals).trim, line.drop(equals + 1).trim))
        }
      }
