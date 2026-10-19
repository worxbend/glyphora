package io.worxbend.tui.terminal

/** The terminal image protocols glyphora can draw a [[io.worxbend.tui.core.GraphicPlacement]] with. */
enum ImageProtocol:

  /** The kitty graphics protocol — kitty, Ghostty and WezTerm among others answer its query. See [[KittyGraphics]]. */
  case Kitty

  /** iTerm2's inline images (OSC 1337), which iTerm2 and WezTerm draw. See [[ITerm2Images]]. */
  case ITerm2

object ImageProtocol:

  /** The protocol to draw pictures with, or `None` for the half-block fallback the widget already put in the cells.
    *
    * Only an explicit `Yes` counts. A terminal that does not draw a picture shows its payload — or, for an APC string,
    * nothing at all where the picture should be — so unlike the modes in [[TerminalCapabilities]], silence must not
    * turn a protocol on. Kitty is preferred where both are available: it is the one with a delete, so a covered picture
    * is taken down exactly instead of by repainting its cells.
    */
  def select(capabilities: TerminalCapabilities): Option[ImageProtocol] =
    if capabilities.kittyGraphics == Support.Yes then Some(Kitty)
    else if capabilities.inlineImages == Support.Yes then Some(ITerm2)
    else None

  /** Whether the environment names a terminal that draws iTerm2 inline images: `TERM_PROGRAM` is `iTerm.app` or
    * `WezTerm`, or `LC_TERMINAL` (which iTerm2 forwards over ssh) is `iTerm2`.
    */
  def namesInlineImageTerminal(env: Map[String, String]): Boolean =
    env.get("TERM_PROGRAM").exists(program => program.equalsIgnoreCase("iTerm.app") || program == "WezTerm") ||
      env.get("LC_TERMINAL").contains("iTerm2")
