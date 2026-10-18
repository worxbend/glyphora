package io.worxbend.tui.core

/** Whether the terminal is drawing light text on a dark background or the other way round — or nobody knows.
  *
  * Three states and not a `Boolean`, for the reason `terminal.Support` gives: "the terminal said light", "the terminal
  * said dark" and "the terminal said nothing" call for different behaviour. An application picking a theme reads
  * [[Unknown]] as "dark", which is what every terminal looked like before any of them could be asked — so a terminal
  * that cannot say gets exactly what it always got.
  *
  * A pure value in `tui-core` rather than in `tui-terminal`, because it travels in two directions that cross the tier
  * boundary: upward as the payload of [[Event.ColorSchemeChanged]], and downward into whichever widget or theme wants
  * to pick a colour by it. Where the value *comes from* — an OSC 11 background-colour reply, a mode 2031 notification —
  * is the terminal tier's business and is read there.
  */
enum ColorScheme:

  /** Dark text on a light background. */
  case Light

  /** Light text on a dark background — what every terminal is assumed to be until it says otherwise. */
  case Dark

  /** The terminal was not asked, or did not answer. */
  case Unknown

object ColorScheme:

  /** The relative luminance below which a background is read as dark.
    *
    * This is the point at which white text and black text have the same WCAG contrast against the background — the same
    * rule [[Color.readableOn]] applies — rather than the midpoint of the channel range. A mid-grey `#808080` (luminance
    * 0.22) is therefore a *light* background: black text reads better on it, which is exactly what picking
    * `Theme.Light` buys.
    */
  private val DarkBelow: Double = 0.179

  /** The scheme a background painted in `background` implies.
    *
    * Decided by [[Color.luminance]], so a `Color.Rgb` answers exactly and a named or indexed colour answers against the
    * nominal palette `Color.approximateRgb` assumes. Only [[Color.Reset]] — "the terminal's own default", which is the
    * very thing being asked about — has no answer and stays [[Unknown]].
    */
  def of(background: Color): ColorScheme =
    background match
      case Color.Reset => Unknown
      case other       => if Color.luminance(other) < DarkBelow then Dark else Light
