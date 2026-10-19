package io.worxbend.tui.terminal

import io.worxbend.tui.core.RgbaImage

import java.util.Base64

/** iTerm2's inline-image protocol (https://iterm2.com/documentation-images.html), which WezTerm and a few others also
  * implement: `ESC ] 1337 ; File = <arguments> : <base64 file> BEL` draws the file at the cursor.
  *
  * The file is a PNG made by [[PngEncoder]]. The arguments glyphora sends:
  *   - `inline=1` display rather than download, and `size` the file's length in bytes;
  *   - `width`/`height` as bare numbers, which the protocol reads as cells — the box the picture is scaled into;
  *   - `preserveAspectRatio=0`, because the widget already chose a box with the right shape and a terminal letterboxing
  *     inside it would leave stale cells showing at the edges;
  *   - `doNotMoveCursor=1`, so an image on the last rows cannot scroll the screen. Terminals that predate the argument
  *     ignore it, which is why [[GraphicsLayer]] also brackets every image in a cursor save and restore.
  *
  * The protocol has no delete. A picture is erased by repainting the cells it covered, which [[GraphicsLayer]] does by
  * handing the region back to the frame diff.
  */
object ITerm2Images:

  private val Esc = "\u001b"
  private val Bel = "\u0007"

  /** The base64 encoding of `image` as a PNG file — what [[display]] sends, exposed so it is encoded once per image. */
  def payload(image: RgbaImage): String = Base64.getEncoder.encodeToString(PngEncoder.encode(image))

  /** Draws `image` at the cursor, scaled into `columns` x `rows` cells. */
  def display(image: RgbaImage, columns: Int, rows: Int): String = display(payload(image), columns, rows)

  /** [[display]] with the payload already computed by [[payload]]. */
  def display(encoded: String, columns: Int, rows: Int): String =
    // base64 of n bytes is 4 * ceil(n / 3) characters, minus the padding; recovering n avoids decoding to count it
    val padding = encoded.reverseIterator.takeWhile(_ == '=').size
    val size    = encoded.length / 4 * 3 - padding
    s"$Esc]1337;File=inline=1;size=$size;width=$columns;height=$rows;preserveAspectRatio=0;doNotMoveCursor=1:$encoded$Bel"
