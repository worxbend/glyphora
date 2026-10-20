package io.worxbend.tui.terminal

import org.jline.terminal.Terminal

/** Package-local production constructor exposed only to the runtime module's byte-level tests. */
object StdioTerminalFixture:
  def wrapping(terminal: Terminal): JLine3Backend =
    JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, knownCapabilities = Some(TerminalCapabilities.unknown))
