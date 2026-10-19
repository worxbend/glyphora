package io.worxbend.tui.terminal

import io.worxbend.tui.core.Size

/** ANSI escape sequences the backend emits. Pure string construction, no I/O — separately testable. */
private[terminal] object AnsiSequences:

  private[terminal] val Esc = "\u001b"

  val EnterAlternateScreen: String = s"$Esc[?1049h"
  val LeaveAlternateScreen: String = s"$Esc[?1049l"
  val ClearScreen: String          = clear(ClearType.All)
  val HideCursor: String           = s"$Esc[?25l"
  val ShowCursor: String           = s"$Esc[?25h"

  /** DECSCUSR with parameter 0: hands the cursor's shape back to the user's own terminal configuration. */
  val ResetCursorShape: String          = s"$Esc[0 q"
  // DECSET/DECRST 12, "cursor blink" — whether the terminal's own caret blinks. Purely cosmetic; a form might blink
  // the caret in the field being typed into and hold it steady while idle. Deliberately *not* part of RestoreAll
  // below: everything in that string is a mode *reset*, which is idempotent and therefore safe to send unconditionally
  // from a shutdown hook that cannot know what was enabled. Re-enabling blink is not idempotent in the way that
  // matters — a user whose emulator is configured for a steady caret would have that preference overwritten by an app
  // that never touched blink at all. JLine3Backend therefore restores it only when it was the one that turned it off.
  val EnableCursorBlink: String         = s"$Esc[?12h"
  val DisableCursorBlink: String        = s"$Esc[?12l"
  // Why four modes, in that order. 1000 asks for presses and releases and 1002 adds motion while a button is held;
  // 1003 (see MouseCaptureMode) adds motion with none held. The other two are *encodings* of the report rather than
  // requests for more of them. The original X10 encoding writes each coordinate as one byte biased by 32, which cannot
  // express a column past 223 — and, because this decoder reads a UTF-8 stream, cannot be read past column 95 at all
  // (see InputDecoder.decodeX10Mouse). 1015 (urxvt) and 1006 (SGR) both replace that with decimal text and lift the
  // ceiling. 1015 is requested *before* 1006 and released *after* it, so a terminal understanding both settles on SGR,
  // which is the better of the two because it names which button was released instead of reporting "one came up".
  // 1015 is here only for a terminal that ignores 1006: without it such a terminal keeps sending X10, and its clicks
  // are unreadable across most of a modern window. Pixel reporting (1016) is deliberately not requested — its
  // coordinates are pixels, and every layer above `Backend` addresses cells.
  val EnableMouseCapture: String        = s"$Esc[?1000h$Esc[?1002h$Esc[?1015h$Esc[?1006h"
  val EnableMouseAllMotion: String      = s"$Esc[?1000h$Esc[?1002h$Esc[?1003h$Esc[?1015h$Esc[?1006h"
  // resets 1003 as well, whether or not it was ever set: a DEC private-mode reset for a mode that is already off is a
  // no-op on every terminal, and sending it unconditionally means no path can leave all-motion tracking stuck on,
  // flooding the user's shell with reports after the app has exited
  val DisableMouseCapture: String       = s"$Esc[?1006l$Esc[?1015l$Esc[?1003l$Esc[?1002l$Esc[?1000l"
  val ResetStyle: String                = s"$Esc[0m"
  val EnableBracketedPaste: String      = s"$Esc[?2004h"
  val DisableBracketedPaste: String     = s"$Esc[?2004l"
  val EnableFocusReporting: String      = s"$Esc[?1004h"
  val DisableFocusReporting: String     = s"$Esc[?1004l"
  // DECSTBM with no parameters — the scroll region becomes the whole screen again. Declared here, above RestoreAll,
  // rather than beside `setScrollRegion` further down: an object's `val`s initialise in declaration order, and
  // RestoreAll names this one, so a definition below it would be read as null while RestoreAll was being built.
  // See `setScrollRegion` for what a region is and why leaving one set is the failure worth guarding against.
  val ResetScrollRegion: String         = s"$Esc[r"
  val BeginSynchronized: String         = s"$Esc[?2026h"
  val EndSynchronized: String           = s"$Esc[?2026l"
  // The three modern input modes the capability probe asks about, each enabled only after the terminal has been heard
  // from (see JLine3Backend.dressRawMode) and reset with everything else on the way out. 2031 asks for a colour-scheme
  // report whenever the user's system theme flips; 2048 asks for resize reports on the input stream instead of only
  // SIGWINCH; 2027 asks the terminal to measure text in grapheme clusters, which is how CharWidth measures it.
  val EnableColorSchemeUpdates: String  = enablePrivateMode(CapabilityReplies.ColorSchemeUpdatesMode)
  val DisableColorSchemeUpdates: String = disablePrivateMode(CapabilityReplies.ColorSchemeUpdatesMode)
  val EnableInBandResize: String        = enablePrivateMode(CapabilityReplies.InBandResizeMode)
  val DisableInBandResize: String       = disablePrivateMode(CapabilityReplies.InBandResizeMode)
  val EnableUnicodeWidth: String        = enablePrivateMode(CapabilityReplies.UnicodeWidthMode)
  val DisableUnicodeWidth: String       = disablePrivateMode(CapabilityReplies.UnicodeWidthMode)

  /** [[pointerShape]] for [[PointerShape.Default]]: the user's own pointer back. Declared up here, above RestoreAll,
    * for the reason ResetScrollRegion gives — RestoreAll names it, and a `val` declared below RestoreAll would be read
    * as null while RestoreAll was being built. It belongs in RestoreAll because asking for the default pointer when
    * nothing ever changed it leaves the pointer exactly where it was.
    */
  val ResetPointerShape: String = pointerShape(PointerShape.Default)

  /** One frame's worth of output, wrapped so the terminal shows the previous frame until the whole batch arrives.
    *
    * `synchronizedOutput` is what a capability probe established: a terminal that reported mode 2026 as unrecognised
    * gets the bare body, because sending it a `?2026h` it does not understand is at best noise and at worst two stray
    * sequences printed into the frame. Everything else — including every terminal that answered nothing — gets the
    * wrapper, since an unsupported private mode is ignored by an overwhelming majority of terminals and tearing is a
    * visible defect where the wrapper is missing.
    *
    * Extracted from `JLine3Backend.draw` so this decision can be read, and tested, without a terminal.
    */
  def frame(body: String, synchronizedOutput: Boolean): String =
    if synchronizedOutput then s"$BeginSynchronized$body$ResetStyle$EndSynchronized"
    else s"$body$ResetStyle"

  /** DECSCUSR (`CSI n SP q`): selects the shape the terminal draws its hardware cursor in — see [[CursorShape]]. */
  def cursorShape(shape: CursorShape): String = s"$Esc[${CursorShape.parameter(shape)} q"

  /** The mouse-capture request for `mode` — see [[MouseCaptureMode]] for what each one costs and buys; `Off` is the
    * request to stop.
    */
  def enableMouseCapture(mode: MouseCaptureMode): String =
    mode match
      case MouseCaptureMode.Off       => DisableMouseCapture
      case MouseCaptureMode.Buttons   => EnableMouseCapture
      case MouseCaptureMode.AllMotion => EnableMouseAllMotion

  /** The ED and EL erase forms — ECMA-48 §8.3.39 "Erase in Display" and §8.3.41 "Erase in Line".
    *
    * The cursor-relative variants are what a viewport that does not own the whole screen needs: an inline app drawing a
    * few rows under the shell prompt erases its own rows and nothing else, where `CSI 2J` would take the user's
    * scrollback with it.
    *
    * None of these moves the cursor; they only blank cells, using the current background colour.
    */
  def clear(kind: ClearType): String =
    kind match
      case ClearType.All          => s"$Esc[2J"
      case ClearType.AfterCursor  => s"$Esc[0J"
      case ClearType.BeforeCursor => s"$Esc[1J"
      case ClearType.CurrentLine  => s"$Esc[2K"
      case ClearType.UntilNewLine => s"$Esc[0K"

  /** Kitty keyboard progressive-enhancement flag 1, "disambiguate escape codes": a lone Esc arrives as `CSI 27 u`
    * instead of a bare ESC byte, removing the read-timeout heuristic on terminals that support it.
    */
  val KittyDisambiguate: Int = 1

  /** Kitty keyboard progressive-enhancement flag 2, "report event types": every key report gains a `:press/repeat/
    * release` sub-parameter, which is the only way a terminal can say a key came back *up*.
    *
    * Requested only when an application asks for it, through `RunnerConfig.keyEventTypes`. It is not in the default set
    * because a release doubles the input volume for every keystroke, and an application that ignores releases gains
    * nothing for the extra traffic.
    */
  val KittyReportEventTypes: Int = 2

  /** The kitty progressive-enhancement push for `flags` (`CSI > flags u`).
    *
    * Flags 4 (report alternate keys), 8 (report all keys as escape codes) and 16 (report associated text) are
    * deliberately never requested. 8 without 16 stops ordinary text arriving at all, and 8 with 16 needs an
    * associated-text vocabulary that no `KeyEvent` in this library carries.
    */
  def pushKittyKeyboard(flags: Int): String = s"$Esc[>${flags}u"

  /** The default push: disambiguation only. An unsupported terminal ignores it and keeps sending legacy encoding. */
  val PushKittyKeyboard: String = pushKittyKeyboard(KittyDisambiguate)

  /** The push an application that asked for key releases gets: disambiguation *and* event types. */
  val PushKittyKeyboardEvents: String = pushKittyKeyboard(KittyDisambiguate | KittyReportEventTypes)

  val PopKittyKeyboard: String = s"$Esc[<u"
  val LinkClose: String        = s"$Esc]8;;$Esc\\"

  /** DECSC (`ESC 7`), "save cursor": stores the cursor position, and the terminal's own graphic-rendition and
    * character-set state, in a one-slot register.
    *
    * The two-byte form rather than `CSI s`: `CSI s` is also DECSLRM, "set left and right margin", on terminals that
    * have margin support switched on, so the same bytes mean two different things depending on a mode this library
    * never sets and cannot observe. `ESC 7` is unambiguous everywhere.
    */
  val SaveCursor: String = s"${Esc}7"

  /** DECRC (`ESC 8`), "restore cursor": puts back whatever [[SaveCursor]] stored.
    *
    * Deliberately absent from [[RestoreAll]], unlike every mode reset there. A terminal whose save register was never
    * written restores the cursor to the home position instead of leaving it alone, so a shutdown hook firing for a
    * process that never entered raw mode — and therefore never saved anything — would move the user's shell cursor to
    * the top-left corner. Pairing it with raw mode, which every dressed-up app enters, keeps every restore matched to a
    * save.
    */
  val RestoreCursor: String = s"${Esc}8"

  /** Every mode the backend can turn on, turned off, in reverse acquisition order.
    *
    * Emitted verbatim by the shutdown hook straight to the process's stdout descriptor, so a terminal left dressed up
    * by a signal-terminated process is still handed back usable. Almost every sequence here is a DEC private-mode
    * *reset* (XTerm `ctlseqs.ms`, "DEC Private Mode Reset"), which is idempotent — resetting a mode that was never set
    * is a no-op, so the hook needs no knowledge of what was actually enabled. [[ResetScrollRegion]] is the exception in
    * form only: DECSTBM with no parameters is not a private-mode reset, but it is idempotent in exactly the same way,
    * and it belongs here for exactly the same reason. A process killed while a scrolling region was set would otherwise
    * leave the user's *shell* scrolling inside a sub-rectangle of their terminal, which is the class of damage this
    * string exists to prevent.
    *
    * [[ResetScrollRegion]] is the one member that is not a private-mode reset. It earns its place by the same property:
    * releasing a scroll region that was never set leaves the screen exactly as it was, and a region left clamped
    * outlives the process and turns most of the user's terminal into rows that scrolling refuses to touch.
    * [[ResetCursorShape]] is here on the same terms: it is DECSCUSR rather than a private-mode reset, but asking for
    * the user's configured cursor shape when nothing ever changed it leaves the shape exactly where it was.
    *
    * [[EndSynchronized]] leads. A frame is written as one `?2026h` … `?2026l` pair (see `JLine3Backend.draw`), which
    * asks the terminal to hold everything back until the closing half arrives so a half-drawn frame is never shown. A
    * process killed between the two halves leaves that update open: until the emulator's own timeout expires the screen
    * stays frozen and would swallow the rest of this restore. Closing the update first makes everything after it appear
    * at once, and closing one that was never opened does nothing.
    */
  val RestoreAll: String =
    s"$EndSynchronized$DisableMouseCapture$ResetCursorShape$ShowCursor$LeaveAlternateScreen$ResetScrollRegion$PopKittyKeyboard" +
      s"$DisableFocusReporting$DisableBracketedPaste$DisableColorSchemeUpdates$DisableInBandResize$DisableUnicodeWidth" +
      s"$ResetPointerShape$ResetStyle"

  /** OSC 8 hyperlink opener; pair every open with [[LinkClose]].
    *
    * The URL is stripped of C0/C1 controls and DEL: an OSC string ends at BEL or ST (XTerm `ctlseqs.ms`, "Operating
    * System Commands"), so an `ESC \` inside the target would close the hyperlink early and let the rest of the string
    * execute as terminal commands. Link targets routinely come from untrusted text (Markdown, log lines, API
    * responses), which makes this a security boundary, not a cosmetic one. RFC 3986 §2 forbids these bytes in a URI, so
    * nothing legitimate is lost.
    */
  def linkOpen(url: String): String = s"$Esc]8;;${stripControls(url)}$Esc\\"

  /** Removes C0 controls, DEL and C1 controls from `text`, keeping tab. */
  def stripControls(text: String): String =
    if text.forall(isSafeText) then text else text.filter(isSafeText)

  /** Whether `c` can safely travel inside a control-sequence-delimited string: tab, or a printable character outside
    * the C1 range.
    */
  private def isSafeText(c: Char): Boolean =
    c == '\t' || !isControl(c)

  /** Whether `c` is a control code a terminal would act on rather than draw: a C0 code, DEL, or a C1 code.
    *
    * One owner for the character class, because [[FrameEncoder]] guards every cell symbol with the same question and
    * the two must never disagree about what is a control.
    */
  private[terminal] def isControl(c: Char): Boolean =
    c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)

  /** OSC 52 clipboard write: sets the system clipboard (`c`) to `text`, base64-encoded per the protocol. Terminals that
    * don't support OSC 52 ignore it.
    */
  def clipboardCopy(text: String): String =
    val encoded = java.util.Base64.getEncoder.encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"$Esc]52;c;$encoded$Esc\\"

  /** XTerm window manipulation, "resize the text area" — `CSI 8 ; rows ; columns t` (XTerm `ctlseqs.ms`, "Window
    * manipulation").
    *
    * Note the argument order: rows first, then columns, which is the reverse of how [[io.worxbend.tui.core.Size]] reads
    * (width, then height). Getting that backwards produces a terminal of the transposed shape rather than an error, so
    * the swap lives here, named, rather than being written out at each call site.
    *
    * Most emulators disable window operations by default and ignore this silently, which is why the caller can never
    * learn from the sequence alone whether it worked.
    */
  def resizeWindow(size: Size): String = s"$Esc[8;${size.height};${size.width}t"

  /** OSC 2 window/tab title.
    *
    * Controls are stripped for exactly the reason [[linkOpen]] strips them: an OSC string ends at BEL or ST, so an
    * `ESC \` inside a document name would close the title early and leave the rest of the name to run as terminal
    * commands. Titles come from the same untrusted places link targets do — a filename, a branch name, a fetched page's
    * `<title>`.
    */
  def setTitle(title: String): String = s"$Esc]2;${stripControls(title)}$Esc\\"

  /** XTerm `CSI 22;2t` — pushes the terminal's current window title onto its own title stack.
    *
    * Emitted once, before the first [[setTitle]], so that [[PopTitle]] can hand the shell's own title back on exit
    * without this library ever having to read the title (there is no reliable, non-blocking way to do that).
    */
  val PushTitle: String = s"$Esc[22;2t"

  /** XTerm `CSI 23;2t` — pops the title pushed by [[PushTitle]], restoring whatever the shell had set.
    *
    * Deliberately absent from [[RestoreAll]]: everything in that string is a DEC private-mode *reset*, which is
    * idempotent and therefore safe for a shutdown hook that cannot know what was enabled. A pop is not idempotent — an
    * unmatched one would discard a title stack entry that belonged to something else.
    */
  val PopTitle: String = s"$Esc[23;2t"

  /** DSR 6 — asks the terminal where its cursor is (ECMA-48 §8.3.35, "Device Status Report").
    *
    * The terminal answers on the *input* stream, as `CSI row ; column R`, one-based. That reply is indistinguishable on
    * its contents from a modified F3 key, which is why only [[InputDecoder.readCursorReport]] may read it and only for
    * as long as a query is actually outstanding.
    *
    * A terminal that does not implement the report simply never answers, so every caller needs a timeout.
    */
  val RequestCursorPosition: String = s"$Esc[6n"

  /** XTWINOPS 14 — asks the terminal how large its text area is *in pixels* (`CSI 14 t`).
    *
    * The terminal answers on the input stream as `CSI 4 ; height ; width t`, in that order: height first, which is the
    * opposite of the column-then-row order [[io.worxbend.tui.core.Size]] uses, so the two are swapped once in
    * [[InputDecoder]] and nowhere else.
    *
    * Unlike a cursor-position report, this reply is unambiguous — no key is encoded with a `t` final byte — so the
    * decoder can recognise it whether or not a query is outstanding. A terminal that does not implement it never
    * answers at all, which is the common case, so every caller needs a timeout.
    */
  val RequestTextAreaPixels: String = s"$Esc[14t"

  /** DECSET (`CSI ? mode h`) — turns a DEC private mode on. */
  def enablePrivateMode(mode: Int): String = s"$Esc[?${mode}h"

  /** DECRST (`CSI ? mode l`) — turns a DEC private mode off. Resetting a mode that was never set is a no-op on every
    * terminal, which is what lets [[RestoreAll]] carry one for each mode the backend can enable.
    */
  def disablePrivateMode(mode: Int): String = s"$Esc[?${mode}l"

  /** OSC 11 with a `?` payload — asks the terminal what colour it paints behind the text.
    *
    * The answer comes back on the input stream as `ESC ] 11 ; rgb:RRRR/GGGG/BBBB ST` and is read by
    * `CapabilityReplies.backgroundColor` inside the capability probe. A terminal that does not implement it never
    * answers, which the probe's DA1 fence absorbs.
    */
  val QueryBackgroundColor: String = s"$Esc]11;?$Esc\\"

  /** DSR 996 (`CSI ? 996 n`) — asks a terminal that implements mode 2031 whether it is light or dark right now.
    *
    * The answer is `CSI ? 997 ; 1 n` for dark and `; 2 n` for light, the same report the terminal sends unprompted once
    * mode 2031 is on. A terminal without the extension ignores an unknown DSR parameter.
    */
  val QueryColorScheme: String = s"$Esc[?996n"

  /** DECRQM (`CSI ? mode $ p`) — asks whether a DEC private mode is recognised, and what state it is in.
    *
    * The answer is a DECRPM, `CSI ? mode ; state $ y`; `CapabilityReplies` reads it. A terminal that does not implement
    * DECRQM answers nothing at all, which is why the probe needs a fence rather than a per-query timeout.
    */
  def queryPrivateMode(mode: Int): String = s"$Esc[?$mode$$p"

  /** Asks the terminal for its current kitty keyboard flags (`CSI ? u`).
    *
    * Only a terminal implementing the protocol answers, so the arrival of a reply *is* the answer — which is why there
    * is nothing to read out of it.
    */
  val QueryKittyKeyboard: String = s"$Esc[?u"

  /** XTVERSION (`CSI > 0 q`) — asks the terminal for its name and version.
    *
    * The answer is a DCS string, `ESC P > | name ST`, read into `TerminalCapabilities.terminalVersion` inside the
    * capability probe. xterm, kitty, WezTerm, foot, Ghostty, iTerm2 and tmux answer; a terminal that does not ignores
    * the unknown CSI, and the DA1 fence absorbs the silence.
    */
  val QueryTerminalVersion: String = s"$Esc[>0q"

  /** DA1 (`CSI c`), the primary device attributes query.
    *
    * Every terminal answers it, so it is the fence that ends a capability probe: terminals answer in the order the
    * queries arrived, so once DA1's reply is back, anything still unanswered was never going to be answered. Sent last
    * for exactly that reason.
    */
  val QueryPrimaryDeviceAttributes: String = s"$Esc[c"

  /** OSC 22 — asks the terminal to draw the mouse pointer as `shape` while it is over the window.
    *
    * The names are the CSS cursor keywords kitty, foot, WezTerm and Ghostty accept (xterm also takes its own X11 cursor
    * font names, which this library does not emit). A terminal without OSC 22 ignores the string.
    */
  def pointerShape(shape: PointerShape): String = s"$Esc]22;${PointerShape.name(shape)}$Esc\\"

  /** A desktop notification in the dialect `protocol` names — see [[DesktopNotificationProtocol]] for which terminal
    * speaks which.
    *
    * `title` and `body` are stripped of controls for the reason [[linkOpen]] strips them: an OSC string ends at BEL or
    * ST, so a stray `ESC \` inside a message would close the notification early and let the rest of the text run as
    * terminal commands — and a notification body is exactly the kind of text that comes from somewhere else. The `;`
    * that separates the OSC 777 fields is also removed from the title, since a title containing one would push the rest
    * of itself into the body.
    */
  def desktopNotification(protocol: DesktopNotificationProtocol, title: String, body: String): String =
    val safeTitle = stripControls(title).replace(";", ",")
    val safeBody  = stripControls(body)
    protocol match
      case DesktopNotificationProtocol.Osc9   =>
        val text = if safeTitle.isEmpty then safeBody else s"$safeTitle: $safeBody"
        s"$Esc]9;$text$Esc\\"
      case DesktopNotificationProtocol.Osc777 => s"$Esc]777;notify;$safeTitle;$safeBody$Esc\\"
      case DesktopNotificationProtocol.Osc99  =>
        // kitty's protocol sends the title and the body as two chunks of one notification: `d=0` says more is coming,
        // `d=1` closes it, and `p=body` names the second chunk as the body rather than a second title
        s"$Esc]99;i=1:d=0;$safeTitle$Esc\\$Esc]99;i=1:d=1:p=body;$safeBody$Esc\\"

  /** OSC 9;4 — the ConEmu progress indicator Windows Terminal, Ghostty, WezTerm and others show on the tab or taskbar.
    *
    * `Some(fraction)` is a determinate bar at `fraction` of the way, clamped to `0.0..1.0` and rounded to the whole
    * percent the protocol takes (`9;4;1;percent`); `NaN` reads as empty. `None` clears the indicator (`9;4;0`). The
    * protocol's error, indeterminate and paused states are deliberately not offered: the one caller is a progress
    * signal, and a signal is either a fraction or nothing.
    */
  def taskbarProgress(progress: Option[Double]): String =
    progress match
      case Some(fraction) =>
        val clamped = if fraction.isNaN then 0.0 else math.min(1.0, math.max(0.0, fraction))
        s"$Esc]9;4;1;${math.round(clamped * 100)}$Esc\\"
      case None           => s"$Esc]9;4;0$Esc\\"

  /** Moves the cursor to an absolute zero-based position (ANSI rows/columns are one-based). */
  def moveTo(x: Int, y: Int): String =
    s"$Esc[${y + 1};${x + 1}H"

  /** SU — scrolls the whole screen up by `n` rows (XTerm `ctlseqs.ms`, "Scroll Up").
    *
    * On the primary screen the rows that leave the top go into the terminal's scrollback, where the user can scroll
    * back to them; `n` blank rows appear at the bottom. Unlike writing `n` newlines this does not depend on where the
    * cursor is and does not move it. `n <= 0` produces the empty string, so a caller computing a delta needs no guard.
    */
  def scrollUp(n: Int): String = if n <= 0 then "" else s"$Esc[${n}S"

  /** SD — scrolls the whole screen (or the current scrolling region) down by `n` rows, blanking the rows exposed at the
    * top (XTerm `ctlseqs.ms`, "Scroll Down"). The mirror of [[scrollUp]]; rows pushed off the bottom are lost, since
    * only the top of the screen has scrollback. `n <= 0` produces the empty string.
    */
  def scrollDown(n: Int): String = if n <= 0 then "" else s"$Esc[${n}T"

  /** DECSTBM — confines scrolling to rows `top`..`bottom` inclusive, zero-based (the sequence itself is one-based).
    *
    * A "scrolling region" is the band of rows a scroll is allowed to move. With one set, [[scrollUp]] shifts only the
    * rows inside the band and leaves everything above and below exactly as it was. That is what lets lines be inserted
    * above a live interface without repainting the interface: confine the scroll to the rows above it, scroll them, and
    * write into the space that opened up.
    *
    * Two things every caller has to know. Setting a region homes the cursor (DEC STD 070), so follow this with an
    * explicit [[moveTo]] rather than assuming the cursor stayed where it was. And a region left set makes the *user's
    * shell* scroll inside a sub-rectangle of their terminal after the app exits, so pair every call with
    * [[ResetScrollRegion]] — which is why [[RestoreAll]] carries one too.
    */
  def setScrollRegion(top: Int, bottom: Int): String = s"$Esc[${top + 1};${bottom + 1}r"
