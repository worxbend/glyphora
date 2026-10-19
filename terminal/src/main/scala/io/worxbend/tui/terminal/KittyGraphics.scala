package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Rect, RgbaImage}

import java.util.Base64

/** The kitty graphics protocol (https://sw.kovidgoyal.net/kitty/graphics-protocol/) as far as glyphora speaks it:
  * transmit-and-display one RGBA image into a box of cells, delete it again, and ask whether the terminal implements
  * the protocol at all.
  *
  * Every command is an APC string, `ESC _ G <key=value,…> ; <payload> ESC \`. The keys glyphora always sends:
  *   - `a=T` transmit and display in one command, `f=32` raw RGBA, `s`/`v` the pixel size, `i` the image id;
  *   - `c`/`r` the box in cells, which the terminal scales the picture into — so no cell-pixel size is needed here;
  *   - `C=1` leave the cursor where it was, so the frame that follows is not shifted;
  *   - `q=2` never answer: an `OK` or an error reply would otherwise arrive on the input stream mid-session.
  *
  * Payloads longer than [[ChunkSize]] base64 bytes are split, as the protocol requires: every chunk but the last
  * carries `m=1`, the last `m=0`, and only the first carries the control keys.
  *
  * Pure string building; nothing here writes to a terminal. [[GraphicsLayer]] decides when each string is sent.
  */
object KittyGraphics:

  private val Esc = "\u001b"

  /** The most base64 bytes one chunk may carry, per the protocol documentation. A multiple of four, so every chunk but
    * the last decodes on its own.
    */
  val ChunkSize: Int = 4096

  /** The documented capability query: a 1x1 RGB image sent with `a=q`, which a terminal implementing the protocol
    * answers with `ESC _ G i=31 ; OK ESC \` and does not display. Any other terminal ignores the APC string.
    */
  val Query: String = s"${Esc}_Gi=$QueryId,s=1,v=1,a=q,t=d,f=24;AAAA$Esc\\"

  /** The image id [[Query]] uses, which is how its reply is told apart from anything else. */
  private[terminal] inline val QueryId = 31

  /** The base64 encoding of `image`'s RGBA bytes — the payload [[display]] sends, exposed so a caller that places one
    * picture many times encodes it once.
    */
  def payload(image: RgbaImage): String = Base64.getEncoder.encodeToString(image.copyRgbaBytes)

  /** Transmits `image` under `id` and displays it at the cursor, scaled into `columns` x `rows` cells. */
  def display(image: RgbaImage, id: Int, columns: Int, rows: Int): String =
    display(payload(image), image.width, image.height, id, columns, rows)

  /** [[display]] with the base64 payload already computed by [[payload]].
    *
    * `source`, when given, is the rectangle of the image in pixels to show — the documented `x`, `y`, `w`, `h` keys —
    * which is how a picture cut by a scroll view's edge is drawn: the whole image is sent, and only the visible slice
    * of it is scaled into the `columns` x `rows` cells that are still on screen.
    */
  def display(
      encoded: String,
      width: Int,
      height: Int,
      id: Int,
      columns: Int,
      rows: Int,
      source: Option[Rect] = None,
  ): String =
    val crop = source.fold("")(r => s"x=${r.x},y=${r.y},w=${r.width},h=${r.height},")
    val keys = s"a=T,f=32,s=$width,v=$height,${crop}i=$id,c=$columns,r=$rows,C=1,q=2"
    if encoded.length <= ChunkSize then s"${Esc}_G$keys;$encoded$Esc\\"
    else
      val chunks = encoded.grouped(ChunkSize).toVector
      val out    = StringBuilder(encoded.length + chunks.size * 16 + keys.length)
      chunks.zipWithIndex.foreach { (chunk, index) =>
        val more = if index == chunks.size - 1 then 0 else 1
        val head = if index == 0 then s"$keys,m=$more" else s"m=$more"
        out.append(Esc).append("_G").append(head).append(';').append(chunk).append(Esc).append('\\')
      }
      out.result()

  /** Deletes image `id` and every placement of it, freeing its data (`d=I`), without a reply. */
  def delete(id: Int): String = s"${Esc}_Ga=d,d=I,i=$id,q=2$Esc\\"

  /** What a kitty graphics reply says about support, or `None` when `payload` is not a reply to [[Query]].
    *
    * `payload` is the APC string's body after `ESC _`: `Gi=31;OK` is a yes, `Gi=31;<ERRORCODE>:…` a no — the terminal
    * implements the protocol but refused the query, which is as unusable as not implementing it.
    */
  private[terminal] def queryReply(payload: String): Option[Support] =
    val prefix = s"Gi=$QueryId"
    if !payload.startsWith(prefix + ";") && !payload.startsWith(prefix + ",") then None
    else
      val message = payload.dropWhile(_ != ';').drop(1)
      Some(if message == "OK" then Support.Yes else Support.No)
