package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, KeyModifiers, Position, Size}

import java.io.InterruptedIOException

import scala.concurrent.duration.Duration

/** Decodes terminal input bytes into [[Event]]s: printable keys, control keys, ANSI CSI/SS3 escape sequences for
  * navigation and function keys, kitty-protocol keys, bracketed paste, and both SGR and legacy X10 mouse reports.
  *
  * Reads through an injected `read(timeoutMillis) => Int` function, so the decoder is testable with scripted input and
  * independent of JLine. That function answers with a character code, or with one of two *different* negative values,
  * spelled the way JLine's `NonBlockingReader` spells them:
  *
  *   - [[InputDecoder.EndOfStream]] (`-1`) — end of file. The other end of the stream is gone (piped input that ran
  *     out, a closed terminal) and no later read can ever produce a character. This decodes to `Event.EndOfInput`.
  *   - any other negative value, in practice `-2` — the read timed out with nothing available. This decodes to `None`,
  *     and the next read may well produce a character.
  *
  * Telling those two apart is the whole point of the distinction: while they were collapsed into one "nothing
  * available" answer, a caller polling after end of file got an instant `None` every time and spun at 100% CPU.
  *
  * A sequence the decoder does not recognize — a device-attributes reply, a cursor-position report, a torn sequence
  * that timed out mid-flight — decodes to `None` and is dropped. It must never be reported as a key: synthesizing an
  * `Escape` from unparsed bytes means a capability probe silently closes the user's dialog.
  *
  * An instance is **not** thread-safe: it owns a shared read cursor and one character of pushback, so exactly one
  * reading thread may call `decode`. Two threads calling it at once interleave their reads into sequences neither of
  * them sent, and the single pushback slot can hand one thread's character to the other. `JLine3Backend` upholds this
  * by only ever decoding from the thread parked in its blocking read.
  *
  * `escapeTimeoutMillis` bounds how long the decoder waits for the rest of a sequence after `ESC`. It also decides how
  * long a lone `ESC` takes to report; raise it on high-latency links where a real `ESC [ A` can arrive split.
  *
  * An `ESC` always *starts* a sequence, so one arriving mid-sequence aborts whatever was being read (ECMA-48 says so,
  * and every terminal relies on it). The decoder hands that `ESC` back rather than consuming it, which means two
  * genuine Escape presses report two `Escape` keys — the second one after the escape timeout — where a truncated
  * sequence followed by a real one reports only the real one.
  */
private[terminal] final class InputDecoder(
    read: Long => Int,
    escapeTimeoutMillis: Long = InputDecoder.DefaultEscapeTimeoutMillis,
):

  import InputDecoder.*

  /** One character of lookahead, so a speculative read can be undone instead of swallowing input. */
  private var pushedBack = NoChar

  /** Whether the stream has already reported end of file.
    *
    * End of file is permanent, so this is a latch: once it is set, [[decode]] answers `Event.EndOfInput` without
    * reading again. Remembering it is what keeps a caller from being told "nothing available" a moment later and going
    * back to waiting on a stream that can never speak — and it means the underlying reader is not touched again after
    * it has said it is finished.
    */
  private var ended = false

  /** In-flight paste progress belongs to the decoder, not a decode call: a wake only suspends its read. */
  private var paste: Option[PasteProgress] = None

  /** Events decoded while a reply round trip was waiting for its reply, waiting their turn.
    *
    * A cursor-position query is a round trip: the reply travels back on the same stream the user's keystrokes do, and a
    * key pressed while it is in flight arrives first. Dropping those keys would make an inline app lose input every
    * time it anchored itself, so they are held here and handed to the next [[decode]] in the order they arrived.
    *
    * Bounded: a query can be armed with `Duration.Inf` — see [[awaitReply]] — and on a terminal that never answers it
    * waits forever, so input that keeps arriving in the meantime must not grow this queue without limit. Past
    * [[InputDecoder.DeferredQueueLimit]] the *oldest* is dropped, and [[deferredDrops]] counts how many were: the same
    * bounded-queue policy the runtime's render loop documents for work nobody is draining.
    */
  private val deferred = scala.collection.mutable.Queue.empty[Event]

  /** How many events have been dropped because [[deferred]] was full, since this decoder was constructed. */
  private var deferredDropped = 0L

  /** The count a test can assert on: a queue that silently drops work needs one number that says it did. */
  private[terminal] def deferredDrops: Long = deferredDropped

  /** Queues `event` for a later [[decode]], dropping the oldest deferred event once the queue is full.
    *
    * The oldest rather than the newest, for the reason the render loop gives for the same choice: the events describe
    * arriving state, and if only some can be kept, the most recent ones are the ones worth keeping. The drop is counted
    * in [[deferredDrops]] rather than swallowed.
    */
  private def defer(event: Event): Unit =
    if deferred.length >= InputDecoder.DeferredQueueLimit then
      val _ = deferred.dequeue()
      deferredDropped += 1
    deferred.enqueue(event)

  /** Whether a `CSI … R` currently means a cursor-position report rather than the F3 key. See [[readCursorReport]]. */
  private var awaitingCursorReport = false

  /** Where the report that [[readCursorReport]] is waiting for said the cursor was, once one arrives. */
  private var reportedCursor: Option[Position] = None

  /** What the terminal has said about itself so far, while [[readCapabilityReport]] is listening, and whether the DA1
    * fence that ends the listening has arrived.
    *
    * Only meaningful during a probe. Outside one a private-parameter reply is still dropped, unread, exactly as it was
    * before — a stray device-attributes answer that arrives mid-session must not rewrite what was established at
    * start-up.
    */
  private var probing           = false
  private var probed            = TerminalCapabilities.unknown
  private var probeFenceReached = false

  /** How large the terminal said its text area was in pixels, once a `CSI 4 ; h ; w t` reply arrives.
    *
    * No companion flag, unlike [[awaitingCursorReport]]: a `t` final byte encodes no key, so this reply is unambiguous
    * whether or not a query is outstanding and can be recognised at any moment.
    */
  private var reportedTextArea: Option[Size] = None

  /** Decodes the next event, blocking up to `timeoutMillis` for the first character.
    *
    * `None` means "no event": either the timeout elapsed or the bytes read were a sequence with no glyphora meaning.
    *
    * An event held back by a cursor-position query is delivered first and costs no read at all, so a key pressed during
    * that round trip reaches the application in the order it was typed.
    */
  def decode(timeoutMillis: Long): Option[Event] =
    if deferred.nonEmpty then Some(deferred.dequeue())
    else decodeOnce(timeoutMillis)

  /** [[decode]] without the deferred queue: one read, one decode. */
  private def decodeOnce(timeoutMillis: Long): Option[Event] =
    if ended then Some(Event.EndOfInput)
    else if paste.nonEmpty then paste.flatMap(decodePaste)
    else
      val first = next(timeoutMillis)
      if first == EndOfStream then
        ended = true
        Some(Event.EndOfInput)
      else if first < 0 then None
      else decodeFirst(first)

  /** Reads the terminal's reply to a Device Status Report, giving up after `timeout`.
    *
    * Called only by [[JLine3Backend.queryCursorPosition]], immediately after it has written `ESC[6n`. While this is
    * running, a `CSI row ; column R` is read as that report; at every other moment the same bytes stay the F3 key, and
    * [[isFunctionKey3]] explains why the two cannot be told apart on their contents alone.
    *
    * Anything else that decodes while the reply is awaited is a real event the user produced, so it is queued rather
    * than dropped and comes back out of the next [[decode]] calls in order — in order up to [[deferred]]'s bound, past
    * which the oldest waiters are dropped and counted in [[deferredDrops]]. `None` means the terminal did not answer in
    * time — which is the ordinary outcome on a terminal that does not implement the report, not a failure.
    *
    * Subject to the same one-reader rule as [[decode]], and for a sharper reason: a second thread decoding concurrently
    * would take the reply out of this one's hands and drop it as an unrecognised sequence.
    */
  private[terminal] def readCursorReport(timeout: Duration): Option[Position] =
    awaitingCursorReport = true
    reportedCursor = None
    try awaitReply(timeout, () => reportedCursor)
    finally awaitingCursorReport = false

  /** Reads the terminal's reply to [[AnsiSequences.RequestTextAreaPixels]], giving up after `timeout`.
    *
    * Called only by [[JLine3Backend.windowSize]], immediately after it has written `ESC[14t`. Everything
    * [[readCursorReport]] says about ordering applies unchanged: it must run on the render thread, a key typed while
    * the reply is in flight is queued rather than dropped, and `None` means the terminal did not answer — which is the
    * ordinary outcome on the many terminals that do not implement the report.
    */
  private[terminal] def readTextAreaSize(timeout: Duration): Option[Size] =
    reportedTextArea = None
    awaitReply(timeout, () => reportedTextArea)

  /** Reads the terminal's answers to the capability queries, giving up after `timeout`.
    *
    * Called only by [[JLine3Backend.enableRawMode]], immediately after it has written the queries — DA1 last, because
    * DA1 is the fence: terminals answer in the order the queries arrived, so its reply means everything that was going
    * to be answered has been.
    *
    * Subject to the same rules as the other two reply round trips: render thread only, and a key typed while the
    * answers are in flight is queued rather than dropped, both bounded as [[deferred]] documents. A terminal that
    * answers nothing at all — including one that does not implement DA1 — costs the full timeout once, at start-up, and
    * yields [[TerminalCapabilities.unknown]], which every caller reads as "use the features anyway".
    */
  private[terminal] def readCapabilityReport(timeout: Duration): TerminalCapabilities =
    probing = true
    probed = TerminalCapabilities.unknown
    probeFenceReached = false
    try
      val _ = awaitReply(timeout, () => Option.when(probeFenceReached)(()))
      probed
    finally probing = false

  /** Pumps the decoder until `arrived` answers or `timeout` runs out, queueing every real event it meets.
    *
    * Shared by the two reply round trips so there is one loop, and therefore one place where the ordering guarantee
    * they both make — nothing the user typed is lost, it is only deferred — is actually implemented. The "nothing is
    * lost" half holds up to [[deferred]]'s bound: past it the oldest deferred events are dropped and counted, which is
    * what keeps an unbounded wait on a chatty terminal from growing the queue without end.
    *
    * `Duration.Inf` means "wait until the reply arrives", and so does any finite wait too large to express in
    * nanoseconds. That second case is not pedantry: `Long.MaxValue * 1_000_000` does not fit in a `Long`, it wraps
    * round to a small negative number, and the deadline computed from it is a moment about a millisecond in the *past*
    * — the loop below would never run once, and the query would answer "the terminal cannot say" without having waited
    * at all. `MaxWaitMillis` is where that clamp sits.
    */
  private def awaitReply[A](timeout: Duration, arrived: () => Option[A]): Option[A] =
    val unbounded = !timeout.isFinite || timeout.toMillis >= InputDecoder.MaxWaitMillis
    val deadline  = if unbounded then Long.MaxValue else System.nanoTime() + timeout.toMillis * NanosPerMilli
    while arrived().isEmpty && (unbounded || System.nanoTime() < deadline) do
      // an unbounded wait still reads in bounded steps, so the loop keeps its shape and each read has a sane timeout
      // to hand the terminal
      val remaining =
        if unbounded then InputDecoder.UnboundedPollMillis
        else math.max(1L, (deadline - System.nanoTime()) / NanosPerMilli)
      decodeOnce(remaining).foreach(defer)
    arrived()

  private def decodeFirst(first: Int): Option[Event] =
    if first == Esc then decodeEscape() else decodeControl(first, KeyModifiers.None)

  /** One non-`ESC` input byte as a key, folding in whatever modifiers its prefix already established.
    *
    * Shared by the bare and the `ESC`-prefixed paths, so Alt+Backspace reports [[KeyCode.Backspace]] with
    * [[KeyModifiers.Alt]] rather than a raw `Char(127)`. A key spec names the key, so a decoded event that names
    * something else is a binding that can never fire — and the kitty path (`CSI 127;3u`) already reports the named key,
    * which would otherwise make the very same binding work on one terminal and not on another.
    *
    * The C0 controls that are not a named key are reported as the Ctrl-plus-character they encode: `0x01`-`0x1a` are
    * Ctrl+A to Ctrl+Z, `0x00` is Ctrl+Space, and `0x1c`-`0x1f` are Ctrl plus `\`, `]`, `^`, `_` (ASCII caret notation).
    * Reporting those as an unmodified `Char` is worse than useless — text inputs insert any character that arrives with
    * no modifier, so the control code lands in the model as an invisible, zero-width, corrupt cell.
    *
    * The 8-bit C1 range (`0x80`-`0x9f`) names no key at all — it is what an 8-bit-mode terminal sends instead of an
    * `ESC` prefix — so it decodes to `None` for the same reason: reporting one as an unmodified `Char` corrupts every
    * text model it reaches. The reader hands back UTF-16 code units, never raw UTF-8 bytes, so a value in that range
    * here is a genuine C1 character and never a continuation byte.
    */
  private def decodeControl(byte: Int, modifiers: KeyModifiers): Option[Event] =
    byte match
      case 0x0d | 0x0a                 => key(KeyCode.Enter, modifiers)
      case 0x09                        => key(KeyCode.Tab, modifiers)
      case 0x7f | 0x08                 => key(KeyCode.Backspace, modifiers)
      case c if c >= 1 && c <= 26      => key(KeyCode.Char('a' + c - 1), modifiers | KeyModifiers.Ctrl)
      case 0                           => key(KeyCode.Char(' '), modifiers | KeyModifiers.Ctrl)
      case c if c >= 0x1c && c <= 0x1f => key(KeyCode.Char(c + 0x40), modifiers | KeyModifiers.Ctrl)
      case c if c >= 0x80 && c <= 0x9f => None
      case c                           => printable(c, modifiers)

  /** A printable character, recombining a UTF-16 surrogate pair into the single code point it encodes.
    *
    * The reader hands back UTF-16 code units, so an astral character (any emoji) arrives as two surrogates; reporting
    * them separately would deliver two unpaired halves that corrupt whatever string they land in. A surrogate that
    * turns out to have no partner is dropped for the same reason: half a character is not a keypress, and the byte that
    * followed it is pushed back so the next `decode` still sees it.
    */
  private def printable(first: Int, modifiers: KeyModifiers): Option[Event] =
    if Character.isHighSurrogate(first.toChar) then
      val low = next(escapeTimeoutMillis)
      if low >= 0 && Character.isLowSurrogate(low.toChar) then
        key(KeyCode.Char(Character.toCodePoint(first.toChar, low.toChar)), modifiers)
      else
        pushBack(low)
        None
    else if Character.isLowSurrogate(first.toChar) then None
    else key(KeyCode.Char(first), modifiers)

  /** A lone ESC is the Escape key; ESC `[`/`O` opens a control sequence; ESC `]`/`P`/`_` opens a terminal reply; ESC
    * plus anything else is Alt plus whatever that byte decodes to on its own.
    */
  private def decodeEscape(): Option[Event] = decodeEscapeBody(next(escapeTimeoutMillis))

  /** The body of [[decodeEscape]], taking the character after the `ESC` as an argument rather than reading it.
    *
    * Split out so [[skipControlString]] can re-dispatch a character it has already consumed. It cannot push that
    * character back, because the `ESC` that preceded it is gone by then and the single pushback slot holds one
    * character, not two.
    */
  private def decodeEscapeBody(byte: Int): Option[Event] =
    byte match
      case c if c < 0      => key(KeyCode.Escape)
      case '['             => decodeCsi()
      case 'O'             => decodeSs3()
      case Esc             =>
        // ESC ESC: the second ESC opens the *next* sequence, so it is handed back rather than swallowed. That is what
        // makes `ESC` followed by an arrow key report Escape and then Up instead of Escape and the literal text `[A`.
        pushBack(Esc)
        key(KeyCode.Escape)
      case ']' | 'P' | '_' => skipControlString()
      case c               => decodeControl(c, KeyModifiers.Alt)

  /** Consumes an OSC (`ESC ]`), DCS (`ESC P`) or APC (`ESC _`) control string up to its terminator and drops it.
    *
    * These carry terminal *replies* — an OSC 11 background-colour answer, a DECRQSS status report, a kitty graphics
    * acknowledgement. glyphora never asks for one, but anything sharing the tty (tmux, a shell prompt, a program run
    * under [[Backend.suspend]]) can leave one in the buffer, and without this the introducer surfaces as Alt+`]` and
    * every byte of the payload is dispatched as a keystroke — where a `q` quits and an Enter submits.
    *
    * The price is that Alt+`]`, Alt+Shift+P and Alt+`_` can no longer be bound: their legacy encoding is byte-identical
    * to a control-string introducer, and one byte of lookahead cannot tell the two apart. That trade is deliberate — a
    * dead rare key beats a reply typing itself into the focused widget.
    *
    * A truncated reply — one whose `ESC` opens a new sequence instead of the `ESC \` terminator — is *not* dropped: the
    * character after that `ESC` is re-dispatched through [[decodeEscapeBody]] and whatever it decodes to is returned
    * from here. Pushing it back instead would lose the `ESC` (the pushback slot holds one character), and the sequence
    * it opened would then be delivered as ordinary keystrokes, where a `q` quits and an Enter submits.
    */
  private def skipControlString(): Option[Event] =
    var consumed              = 0
    var done                  = false
    var result: Option[Event] = None
    while !done && consumed < MaxControlStringLength do
      val c = next(escapeTimeoutMillis)
      consumed += 1
      if c < 0 || c == Bel then done = true
      else if c == Esc then
        val terminator = next(escapeTimeoutMillis)
        done = true
        // `ESC \` ends the string and means nothing else; anything else after the ESC is a new sequence that has to be
        // decoded here. A timed-out read stays `None`: turning a truncated reply into an `Escape` is exactly the
        // invented keypress this class promises never to report.
        if terminator >= 0 && terminator != '\\' then result = decodeEscapeBody(terminator)
    result

  /** CSI sequences: parameters (digits, `;`, `:` and the private `<`/`>`/`=`/`?` prefixes) then a final byte in
    * 0x40-0x7E. [[scanCsi]] reads the sequence off the wire and says how it ended; only a complete one becomes an
    * event, because a torn or oversized sequence must never be turned into an invented key.
    */
  private def decodeCsi(): Option[Event] =
    scanCsi() match
      case CsiScan.Complete(params, finalByte) => decodeCsiFinal(params, finalByte)
      case CsiScan.Torn | CsiScan.Overrun      => None

  /** Reads the body of a CSI sequence — everything after the `ESC [` introducer — and names how it ended.
    *
    * Called only from [[decodeCsi]], on the same thread as [[decode]], because it advances the decoder's shared read
    * cursor (and therefore its one-character pushback slot). It never decides what a sequence *means*; it only hands
    * back the parameters and the final byte, or the reason there are none.
    *
    * The three outcomes:
    *   - [[CsiScan.Complete]] — a final byte in 0x40-0x7E arrived with the parameters intact.
    *   - [[CsiScan.Torn]] — the read timed out mid-flight, so the sequence never finished arriving.
    *   - [[CsiScan.Overrun]] — the sequence outgrew [[MaxParamLength]] (and possibly [[MaxSequenceLength]] on top of
    *     that), so its parameters are no longer trustworthy.
    *
    * Only `Complete` produces an event; the other two are dropped by the caller. Past [[MaxParamLength]] the scan keeps
    * reading anyway, discarding characters until the final byte, because a verbose primary-DA reply really does overrun
    * that budget on some terminals and stopping mid-sequence left the remaining parameter digits in the buffer to be
    * decoded as keystrokes at startup. [[MaxSequenceLength]] is the backstop that keeps that resynchronization from
    * running forever: reached only once the parameter budget has already overflowed, it abandons the read outright.
    */
  private def scanCsi(): CsiScan =
    val params = StringBuilder()

    // `overrun` records that parameters have been dropped; the scan continues so the stream stays aligned
    @annotation.tailrec
    def scan(consumed: Int, overrun: Boolean): CsiScan =
      // An InterruptedIOException is `wake()`'s interrupt reaching a read parked inside a sequence (JLine's reader
      // converts the interrupt into one and throws it). The read was cut short exactly as a timeout cuts it short, so
      // the sequence is Torn for the same reason and with the same consequences; letting the exception unwind out of
      // `decode` instead aborted the whole decode with an error `decode`'s contract never throws — the reply round
      // trips drive `decodeOnce` and would surface the interrupt as a spurious I/O failure.
      val c    =
        try next(escapeTimeoutMillis)
        catch case _: InterruptedIOException => -1 // read the interrupt as "nothing arrived", like a timeout
      val read = consumed + 1
      if c < 0 then CsiScan.Torn
      else if c == Esc then
        // an ESC means the sequence in flight was torn off by a new one; hand it back so the next `decode` re-parses it
        // rather than consuming the new sequence's `[` as this one's final byte
        pushBack(c)
        CsiScan.Torn
      else if c >= 0x40 && c <= 0x7e then if overrun then CsiScan.Overrun else CsiScan.Complete(params.result(), c)
      else if params.length < MaxParamLength then
        params.append(c.toChar)
        scan(read, overrun)
      else if read >= MaxSequenceLength then CsiScan.Overrun // not a sequence at all: stop looking for a final byte
      else scan(read, overrun = true)

    scan(consumed = 0, overrun = false)

  /** Dispatches a complete CSI sequence to its decoding tier: mouse report, terminal reply, or key.
    *
    * The tiers are tried in this order on purpose, and the order is load-bearing twice over: the mouse forms claimed by
    * [[isMouseReport]] overlap the urxvt form's bare `M` final byte, and a terminal reply (`?`/`>`/`=` parameters, a
    * cursor report's `R`, XTWINOPS's `t`) must be recognised before the key path can reach for it, because a reply is
    * never a key. Within each tier the same rule holds — see [[decodeMouseForm]] and [[decodeTerminalReply]].
    */
  private def decodeCsiFinal(params: String, finalByte: Int): Option[Event] =
    if isMouseReport(params, finalByte) then decodeMouseForm(params, finalByte)
    else if isTerminalReply(params, finalByte) then decodeTerminalReply(params, finalByte)
    else decodeCsiModifiedKey(params, finalByte)

  /** Whether a sequence is one of the three mouse report forms: X10 (`CSI M`), SGR (`CSI < … M/m`) or urxvt (`CSI …
    * M`).
    */
  private def isMouseReport(params: String, finalByte: Int): Boolean =
    finalByte == 'M' || (params.startsWith("<") && finalByte == 'm')

  /** The three mouse report forms, in the order they have to be tried. */
  private def decodeMouseForm(params: String, finalByte: Int): Option[Event] =
    if params.isEmpty && finalByte == 'M' then decodeX10Mouse()
    else if params.startsWith("<") && (finalByte == 'M' || finalByte == 'm') then
      decodeSgrMouse(params.drop(1), finalByte == 'M')
    // empty params and the `<` prefix were both taken above, so a bare `M` here is the urxvt form
    else decodeUrxvtMouse(params)

  /** Whether a sequence is a reply from the terminal rather than user input: a private-parameter reply (DA, DECRPM,
    * XTVERSION), a cursor-position report, or an XTWINOPS answer.
    */
  private def isTerminalReply(params: String, finalByte: Int): Boolean =
    isPrivateReply(params) || (awaitingCursorReport && finalByte == 'R') || finalByte == 't'

  /** The terminal replies, in the order they have to be tried. Every branch reports no event: a reply is captured,
    * never delivered.
    */
  private def decodeTerminalReply(params: String, finalByte: Int): Option[Event] =
    if isPrivateReply(params) then captureProbeReply(params, finalByte)
    // A cursor-position report is caught here rather than down in `decodeCsiKey`, because its second parameter is a
    // *column*, and the modifier extraction below rejects any second parameter above 16 — so a report from anywhere
    // right of column 16 would never have reached a case at all.
    else if awaitingCursorReport && finalByte == 'R' then captureCursorReport(parameterNumbers(params))
    // XTWINOPS answers `CSI 4 ; height ; width t`. Caught here, before the key path, because `t` is not a key on any
    // terminal and a reply must never be dispatched into whatever has focus.
    else captureTextAreaReport(parameterNumbers(params))

  /** A CSI key sequence: the modifier, when the sequence carries one, is the second parameter. */
  private def decodeCsiModifiedKey(params: String, finalByte: Int): Option[Event] =
    val numbers = parameterNumbers(params)
    numbers.drop(1).headOption match
      case Some(code) => modifiersFromCode(code).flatMap(decodeCsiKey(numbers, params, finalByte, _))
      case None       => decodeCsiKey(numbers, params, finalByte, KeyModifiers.None)

  /** The numeric parameters of a CSI sequence, in order.
    *
    * One reading of the parameter string for every sequence shape, because the two that existed disagreed: the mouse
    * one accepted neither an empty field nor a sub-parameter, so an SGR report written as `CSI <0:1;10;5M` — legal, and
    * what a terminal that reports a sub-parameter sends — matched nothing and the whole click was dropped.
    *
    * An empty field keeps its position and defaults to 1 (`CSI ;5A` is Ctrl+Up, exactly as `CSI 1;5A` is — ECMA-48
    * §5.4.1: "a default value of 1 is used when no explicit value is given"), and a `:` sub-parameter (kitty writes the
    * shifted key and the base layout key after a colon) is discarded: glyphora has no vocabulary for it, and a
    * parameter it cannot read must not shift the ones after it out of position.
    */
  private def parameterNumbers(params: String): Seq[Int] =
    parameterFields(params).flatMap(_.takeWhile(_ != ':').toIntOption)

  /** The positional fields of a CSI parameter string.
    *
    * One owner for "which field is at which index", because that is the rule the numeric reading above and the
    * sub-parameter reading in [[kittyEventType]] must agree on. An empty field (`CSI ;5A`, a sequence that omits its
    * first parameter) keeps its position and reads as `"1"`: skipping it instead used to shift every later field one
    * slot left, and the modifier code landed in the key's own slot — `CSI ;5A` decoded as a plain Up with Ctrl dropped.
    * A sequence with no parameters at all stays field-less, so `CSI ~` names nothing rather than defaulting to
    * `CSI 1~`.
    */
  private def parameterFields(params: String): Seq[String] =
    if params.isEmpty then Seq.empty
    else params.split(';').toSeq.map(field => if field.isEmpty then "1" else field)

  /** Decodes a CSI key sequence, then applies the kitty event type the sequence carried.
    *
    * The event type is read once, here, rather than inside the arm for the kitty `u` form. A terminal asked for event
    * types by [[Backend.enableKeyEventTypes]] reports them on *every* key it sends, and the keys that kept their legacy
    * shapes — `CSI 1;1:3B` for a released Down arrow, `CSI 3;1:3~` for a released Delete — carry it in the same
    * sub-parameter of the same parameter. Reading it in one place is what keeps an arrow release from arriving as a
    * second press, which made a list scroll two rows per keystroke in an application that opted in.
    */
  private def decodeCsiKey(
      numbers: Seq[Int],
      params: String,
      finalByte: Int,
      modifiers: KeyModifiers,
  ): Option[Event] =
    decodeCsiKeyPress(numbers, finalByte, modifiers).map(withEventType(_, kittyEventType(params)))

  /** The event a CSI key sequence names, always as a press; [[decodeCsiKey]] turns it into a release when the sequence
    * said so.
    */
  private def decodeCsiKeyPress(
      numbers: Seq[Int],
      finalByte: Int,
      modifiers: KeyModifiers,
  ): Option[Event] =
    finalByte match
      case 'A'                                            => key(KeyCode.Up, modifiers)
      case 'B'                                            => key(KeyCode.Down, modifiers)
      case 'C'                                            => key(KeyCode.Right, modifiers)
      case 'D'                                            => key(KeyCode.Left, modifiers)
      case 'H'                                            => key(KeyCode.Home, modifiers)
      case 'F'                                            => key(KeyCode.End, modifiers)
      // kitty reports F1/F2/F4 as `CSI 1 P/Q/S`, and F3 as `CSI 1 R`, matching the SS3 forms in `decodeSs3`
      case 'P'                                            => key(KeyCode.F(1), modifiers)
      case 'Q'                                            => key(KeyCode.F(2), modifiers)
      case 'S'                                            => key(KeyCode.F(4), modifiers)
      case 'R' if isFunctionKey3(numbers)                 => key(KeyCode.F(3), modifiers)
      case 'Z'                                            => key(KeyCode.Tab, modifiers | KeyModifiers.Shift)
      case 'I'                                            => Some(Event.FocusGained)
      case 'O'                                            => Some(Event.FocusLost)
      case 'u'                                            => decodeKittyKey(numbers, modifiers)
      case '~' if numbers.headOption.contains(PasteStart) =>
        val progress = PasteProgress()
        paste = Some(progress)
        decodePaste(progress)
      case '~'                                            => decodeTilde(numbers, modifiers)
      case _                                              => None

  /** Folds a DA/DECRPM/kitty-query reply into the capability set being probed, and reports no event for it.
    *
    * These replies are never input and never a key. During a capability probe they are also the answers being waited
    * for, so they are read on the way past instead of only being dropped; outside a probe the read is skipped and the
    * reply is dropped exactly as before.
    */
  private def captureProbeReply(params: String, finalByte: Int): Option[Event] =
    if probing then
      probed = CapabilityReplies.fold(probed, params, finalByte)
      if CapabilityReplies.endsProbe(finalByte) then probeFenceReached = true
    None

  /** Records a cursor-position report and reports no event for it.
    *
    * Deliberately not an [[Event]]. A cursor report is a reply to something this library asked, not something the user
    * did, and adding a case for it to the event ADT would put a branch no application ever writes into every exhaustive
    * `match` over `Event` in every application.
    *
    * The wire format is one-based row then column; [[Position]] is zero-based column then row, which is the coordinate
    * space every other part of glyphora uses. Both conversions happen here, once.
    */
  private def captureCursorReport(numbers: Seq[Int]): Option[Event] =
    numbers match
      case Seq(row, column) if row >= 1 && column >= 1 =>
        reportedCursor = Some(Position(column - 1, row - 1))
        None
      case _                                           => None

  /** Records the terminal's text-area size in pixels and reports no event for it.
    *
    * Deliberately not an [[Event]], for the reason [[captureCursorReport]] gives: a reply to something this library
    * asked is not something the user did, and a case for it in the event ADT would put a branch no application ever
    * writes into every exhaustive `match` over `Event`.
    *
    * The wire order is `4 ; height ; width`, height first; [[Size]] is width first. The swap happens here, once. A
    * reply with a different leading parameter is some other XTWINOPS answer and is dropped rather than misread.
    */
  private def captureTextAreaReport(numbers: Seq[Int]): Option[Event] =
    numbers match
      case Seq(TextAreaReport, height, width) if height >= 0 && width >= 0 =>
        reportedTextArea = Some(Size(width, height))
        None
      case _                                                               => None

  /** Whether a `CSI … R` is F3 rather than a cursor-position report.
    *
    * The two collide: F3 (modified) is `CSI 1 ; modifier R`, a CPR is `CSI row ; column R`, and a CPR must never
    * surface as a key. They are told apart by F3's first parameter always being 1 and its second always being a
    * plausible xterm modifier code — rejecting every two-parameter form instead, as this used to, drops Ctrl+F3 and
    * Shift+F3 while their F1/F2/F4 neighbours work. A report of row 1 at a column of 16 or less stays ambiguous and is
    * read as F3; glyphora never requests a CPR, so nothing it sends can produce one.
    */
  private def isFunctionKey3(numbers: Seq[Int]): Boolean =
    numbers match
      case Seq()        => true
      case Seq(1)       => true
      case Seq(1, code) => code >= 1 && code <= MaxModifierCode
      case _            => false

  /** Private-parameter CSI sequences are replies from the terminal (DA, DECRPM, XTVERSION), never user input. */
  private def isPrivateReply(params: String): Boolean =
    params.nonEmpty && (params.head == '?' || params.head == '>' || params.head == '=')

  /** `CSI n ~` navigation/function keys; the modifier, when present, is the second parameter. */
  private def decodeTilde(numbers: Seq[Int], modifiers: KeyModifiers): Option[Event] =
    numbers.headOption.flatMap(CsiKeys.tildeKey).map(c => Event.Key(KeyEvent(c, modifiers)))

  /** Kitty keyboard protocol `CSI codepoint ; modifiers u`: unambiguous keys, no Esc timeout heuristic.
    *
    * The code point vocabulary itself lives in [[KittyKeys]]; [[foldShiftedChar]] rewrites the one shape kitty reports
    * differently from every legacy terminal.
    */
  private def decodeKittyKey(numbers: Seq[Int], modifiers: KeyModifiers): Option[Event] =
    numbers.headOption.flatMap(KittyKeys.keyCode).map(code => Event.Key(foldShiftedChar(code, modifiers)))

  /** Rewrites a decoded press as a release when the sequence carried [[KittyRelease]], and leaves it alone otherwise.
    *
    * Only a [[Event.Key]] is rewritten. The other things a CSI key sequence can decode to — focus in/out, a bracketed
    * paste — are not keypresses and have no released form, so an event type reported alongside them means nothing.
    */
  private def withEventType(event: Event, eventType: Int): Event =
    event match
      case Event.Key(key) if eventType == KittyRelease => Event.KeyRelease(key)
      case other                                       => other

  /** The event-type sub-parameter of a kitty key report — the `3` in `CSI 97;5:3u`.
    *
    * Kitty writes `modifiers:eventType` in the *second* CSI parameter. [[parameterNumbers]] deliberately drops
    * everything after a `:`, because a sub-parameter it cannot read must not shift the positional parameters after it;
    * this reads that one field back without disturbing that reading, which is why it takes the raw parameter text
    * rather than the numbers.
    *
    * Missing, unparseable, or any value other than [[KittyRelease]] reads as a press. That is the safe default twice
    * over: it is what a terminal without the flag would have sent, and kitty's auto-repeat (`:2`) is deliberately a
    * press here — see [[Event.KeyRelease]] for why a repeat is not an event of its own.
    */
  private def kittyEventType(params: String): Int =
    parameterFields(params).lift(1).flatMap(_.split(':').toSeq.lift(1)).flatMap(_.toIntOption).getOrElse(KittyPress)

  /** Rewrites a kitty "base key plus a Shift bit" report into the single legacy encoding, so a key spec has one
    * spelling that works on every terminal.
    *
    * Kitty reports the *unshifted* key together with a Shift modifier — Alt+Shift+A arrives as `CSI 97;4u`, which is
    * `Char('a')` with Alt|Shift. A legacy terminal sends `ESC 'A'` for the same keypress, which [[decodeControl]]
    * reports as `Char('A')` with Alt and no Shift bit. Left alone, the two encodings reach the binding table as
    * different keys: `"alt+A"` would fire under xterm and never under kitty, and `"alt+shift+a"` the reverse. Folding
    * here means exactly one vocabulary ever reaches a [[KeyEvent]], and the legacy one is chosen because it is what
    * every other code path in the library already produces.
    *
    * Two cases, matching what the legacy wire format can actually carry:
    *   - Ctrl is *not* held: emit the uppercase code point and clear Shift — `CSI 97;4u` becomes `Char('A')` + Alt.
    *   - Ctrl *is* held: emit the base (lower-case) code point and clear Shift — a legacy terminal collapses both
    *     Ctrl+S and Ctrl+Shift+S onto the control byte `0x13`, which decodes as `Char('s')` + Ctrl, so case cannot
    *     survive there and must not survive here either. `CSI 115;6u` therefore decodes identically to `0x13`.
    *
    * A character with no uppercase form (a digit, punctuation, an emoji) is left exactly as reported: dropping its
    * Shift bit would throw away the only signal that the key was shifted at all, and reconstructing the shifted glyph
    * would need a keyboard-layout table the decoder does not have.
    */
  private def foldShiftedChar(code: KeyCode, modifiers: KeyModifiers): KeyEvent =
    code match
      case KeyCode.Char(codePoint) if modifiers.hasAny(KeyModifiers.Shift) =>
        val upper = Character.toUpperCase(codePoint)
        if upper == codePoint then KeyEvent(code, modifiers)
        else if modifiers.hasAny(KeyModifiers.Ctrl) then KeyEvent(code, modifiers.without(KeyModifiers.Shift))
        else KeyEvent(KeyCode.Char(upper), modifiers.without(KeyModifiers.Shift))
      case _                                                               => KeyEvent(code, modifiers)

  /** Bracketed paste: everything between `CSI 200~` and `CSI 201~` is one paste payload.
    *
    * The terminator is matched against a rolling tail rather than by consuming a fixed lookahead. A payload may
    * legitimately contain `ESC` (bracketed paste does not guarantee control-free text, and terminals differ on what
    * they filter), and any speculative read that cannot push back would swallow the real terminator's own `ESC [`.
    *
    * [[PasteLimit]] caps how much of the payload is *kept*, never how much is read. Stopping the loop at the cap left
    * the rest of the payload — and its terminator — in the buffer, where the following `decode` calls read it as
    * ordinary keystrokes and dispatch it into whatever has focus: a `q` quits, an Enter submits. Past the cap the text
    * is truncated but the terminator is still consumed, so the stream stays aligned. [[PasteDrainLimit]] bounds each
    * batch, so a paste whose terminator never arrives cannot spin here forever. At a budget boundary its retained
    * prefix is delivered once; subsequent calls keep discarding in bounded batches until the stream is aligned again.
    *
    * The two negative answers the reader can give are told apart here, exactly as [[decodeOnce]] tells them apart for
    * an event's first byte — and for the same reason, one sequence deeper. [[EndOfStream]] is permanent: no later read
    * can produce the rest of the payload, so the paste ends where the stream did. A timed-out read is not: the sender
    * has merely gone quiet for [[PasteTimeoutMillis]], which a large paste arriving over a slow link does routinely,
    * and which the reader produces on its own whenever it is configured to return as soon as no byte is ready. Ending
    * the paste there truncated it *and* left the rest of the payload and the `CSI 201~` terminator in the buffer, to be
    * dispatched into the focused widget one keystroke at a time — a paste containing a `q` quit the application and one
    * containing a newline submitted the form. So a stall is waited out instead, up to [[PasteStallLimit]] consecutive
    * empty reads, and the counter resets the moment a character arrives. Exhausting that budget yields the retained
    * prefix once, but keeps the rolling terminator: a sender that resumes even later must still not dispatch commands.
    *
    * A reader interrupt is a wake, not silence or EOF: it yields `None` and leaves the payload, rolling terminator and
    * both budgets intact. The next decode resumes this paste before interpreting any more bytes as keys.
    */
  private def decodePaste(progress: PasteProgress): Option[Event] =
    // The reader clears the interrupt and consumes no character. Keep every counter and the rolling tail so a wake
    // inside the terminator, or while draining oversized input, resumes exactly where it stopped.
    try
      var done = false
      while !done && progress.batchRead < PasteDrainLimit && progress.stalls < PasteStallLimit do
        val c = next(PasteTimeoutMillis)
        if c == EndOfStream then
          ended = true
          done = true
        else if c < 0 then progress.stalls += 1
        else done = progress.consume(c)
      // A budget is a yield, not a trustworthy end marker. Keep discarding until the terminator or permanent EOF;
      // otherwise a late q/newline becomes an application command. Only the retained prefix is delivered, once.
      if done then paste = None
      progress.batchRead = 0
      progress.stalls = 0
      if progress.delivered then None
      else
        val event = Event.Paste(progress.content.result())
        progress.delivered = true
        progress.content.clear()
        Some(event)
    catch case _: InterruptedIOException => None

  /** Mutable progress confined to the decoder's one reading thread, retained only until this paste completes. */
  private final class PasteProgress:
    val content   = StringBuilder()
    val tail      = StringBuilder()
    var read      = 0
    var batchRead = 0
    var stalls    = 0
    var delivered = false

    /** Folds one arrived character into the payload and the rolling tail, answering whether it completed the
      * terminator.
      */
    def consume(c: Int): Boolean =
      stalls = 0
      batchRead += 1
      // Payload accounting stops when its one event is delivered, so an endless drain cannot overflow it.
      if !delivered then
        read += 1
        if content.length < PasteLimit then content.append(c.toChar)
      tail.append(c.toChar)
      if tail.length > PasteEnd.length then tail.deleteCharAt(0)
      if isTerminator(tail) then
        if !delivered then trimTerminator(content, read)
        true
      else false

  /** Drops whatever part of the paste terminator was appended to `content`.
    *
    * `charsRead` counts everything consumed from the wire, the terminator included, while `content` holds only what
    * fitted under [[PasteLimit]] — so the terminator's own characters are in `content` exactly when the payload stayed
    * under the cap, and only that many of them are trimmed off the end.
    */
  private def trimTerminator(content: StringBuilder, charsRead: Int): Unit =
    val payloadChars        = charsRead - PasteEnd.length
    val terminatorCharsKept = math.max(0, content.length - payloadChars)
    content.setLength(content.length - terminatorCharsKept)

  /** Whether the rolling tail *is* the paste terminator. Compared character by character so that a payload the size of
    * a file costs no allocation per byte.
    */
  private def isTerminator(tail: StringBuilder): Boolean =
    tail.length == PasteEnd.length && {
      var offset = 0
      var same   = true
      while same && offset < PasteEnd.length do
        if tail.charAt(offset) != PasteEnd.charAt(offset) then same = false
        offset += 1
      same
    }

  /** SS3 sequences (`ESC O x`): F1-F4, Home/End, the DECCKM application-mode cursor keys, and the DECKPAM application
    * keypad.
    *
    * The keypad block is what a terminal sends for the numeric keypad once DECKPAM (`ESC =`, "application keypad mode")
    * is on — the same tmux `xterm-keys` setting that motivates the DECCKM cursor arms below turns it on too. Before
    * these arms existed every one of those finals fell through to `None`, so on such a terminal the whole numeric
    * keypad was dead: pressing keypad `4` typed nothing at all rather than a `4`.
    *
    * Keypad finals fold onto the plain keys they print, exactly as [[KittyKeys]] folds its `KP_*` code points. glyphora
    * has no separate keypad concept, so the same physical key must produce the same event whichever protocol the
    * terminal happens to speak.
    */
  private def decodeSs3(): Option[Event] =
    next(escapeTimeoutMillis) match
      case 'P' => key(KeyCode.F(1))
      case 'Q' => key(KeyCode.F(2))
      case 'R' => key(KeyCode.F(3))
      case 'S' => key(KeyCode.F(4))
      case 'H' => key(KeyCode.Home)
      case 'F' => key(KeyCode.End)
      // application cursor keys: what a terminal sends once DECCKM (`CSI ?1h`) is on, e.g. under tmux `xterm-keys`
      case 'A' => key(KeyCode.Up)
      case 'B' => key(KeyCode.Down)
      case 'C' => key(KeyCode.Right)
      case 'D' => key(KeyCode.Left)
      // application keypad (DECKPAM). The finals are those in XTerm's `ctlseqs.ms`, "PC-Style Function Keys".
      case 'M' => key(KeyCode.Enter)     // keypad Enter
      case 'j' => key(KeyCode.Char('*'))
      case 'k' => key(KeyCode.Char('+'))
      case 'l' => key(KeyCode.Char(',')) // keypad separator, a comma on the layouts that have one
      case 'm' => key(KeyCode.Char('-'))
      case 'n' => key(KeyCode.Char('.')) // keypad Delete doubles as the decimal point
      case 'o' => key(KeyCode.Char('/'))
      case 'X' => key(KeyCode.Char('='))
      case ' ' => key(KeyCode.Char(' '))
      case 'I' => key(KeyCode.Tab)
      // `p` through `y` are the digits 0 through 9, in order. Placed after the letter arms above so that the guard
      // cannot shadow one of them if a final is ever added inside the range.
      case digit if digit >= 'p' && digit <= 'y' => key(KeyCode.Char(('0' + (digit - 'p')).toChar))
      case _                                     => None

  /** SGR mouse report `CSI < b ; x ; y (M|m)`. The parameters are parsed here so that [[parameterNumbers]] stays the
    * decoder's one reading of a CSI parameter string; what the numbers mean is [[MouseReports]]'s.
    */
  private def decodeSgrMouse(params: String, isPress: Boolean): Option[Event] =
    MouseReports.sgr(parameterNumbers(params), isPress)

  /** urxvt mouse report `CSI b ; x ; y M` (DEC mode 1015); see [[MouseReports.urxvt]] for the format. */
  private def decodeUrxvtMouse(params: String): Option[Event] =
    MouseReports.urxvt(parameterNumbers(params))

  /** Legacy X10 mouse report `CSI M b x y`: three raw bytes read straight off the wire, which is why this half stays in
    * the decoder — [[MouseReports.x10]] owns the arithmetic on them, including the range check that rejects a
    * replacement character standing in for a coordinate byte.
    */
  private def decodeX10Mouse(): Option[Event] =
    val button = next(escapeTimeoutMillis)
    val column = next(escapeTimeoutMillis)
    val row    = next(escapeTimeoutMillis)
    if button < 0 || column < 0 || row < 0 then None
    else MouseReports.x10(button, column, row)

  /** xterm modifier parameter: `code - 1` is a bitmask of shift/alt/ctrl.
    *
    * kitty extends it with super (8), hyper (16) and meta (32), which have no [[KeyModifiers]] counterpart, and with
    * caps-lock (64) and num-lock (128), which are lock *states* rather than held modifiers and carry no meaning for a
    * binding. A key held with a modifier glyphora cannot represent is dropped, never delivered bare: Super+Q arriving
    * as a plain `q` fires the quit binding.
    */
  private def modifiersFromCode(code: Int): Option[KeyModifiers] =
    val bits = code - 1
    if (bits & UnrepresentableModifiers) != 0 then None else Some(modifiersFromBits(bits))

  private def key(code: KeyCode, modifiers: KeyModifiers = KeyModifiers.None): Option[Event] =
    Some(Event.Key(KeyEvent(code, modifiers)))

  private def next(timeoutMillis: Long): Int =
    if pushedBack >= 0 then
      val c = pushedBack
      pushedBack = NoChar
      c
    else read(timeoutMillis)

  /** Whether a character read speculatively is waiting to be decoded again.
    *
    * A caller that drives the decoder from a finite script — [[HeadlessBackend.postInput]] — cannot tell from its own
    * script iterator whether the decoder is done: a CSI torn off by a fresh `ESC` hands that `ESC` back, so the last
    * character of the script can still be inside the decoder after the iterator has run dry. A blocking reader never
    * needs this, because it simply reads again.
    */
  private[terminal] def hasPushback: Boolean = pushedBack >= 0

  private def pushBack(c: Int): Unit =
    if c >= 0 then pushedBack = c

private[terminal] object InputDecoder:

  /** The xterm shift/alt/ctrl bitmask (bit 0 shift, bit 1 alt, bit 2 ctrl) as a [[KeyModifiers]] set.
    *
    * Bits above those three are ignored here; a caller that must reject them — `modifiersFromCode`, because a key held
    * with a modifier glyphora cannot express must not be delivered bare — checks for them before calling.
    *
    * It lives on the companion, rather than on the decoder that reads the bits off the wire, because [[MouseReports]]
    * needs the same reading: a mouse report's modifier bits are the CSI ones shifted up by two, and one owner is what
    * keeps a Ctrl+click and a Ctrl+arrow from disagreeing about which bit is Ctrl.
    */
  private[terminal] def modifiersFromBits(bits: Int): KeyModifiers =
    var modifiers = KeyModifiers.None
    if (bits & 1) != 0 then modifiers = modifiers | KeyModifiers.Shift
    if (bits & 2) != 0 then modifiers = modifiers | KeyModifiers.Alt
    if (bits & 4) != 0 then modifiers = modifiers | KeyModifiers.Ctrl
    modifiers

  /** How long to wait for the rest of an escape sequence — and therefore how long a lone `ESC` takes to report. */
  val DefaultEscapeTimeoutMillis: Long = 50L

  /** How a CSI sequence ended, as read off the wire by `InputDecoder.scanCsi`.
    *
    * Purely a description of the bytes: what a completed sequence *means* is decided afterwards, by `decodeCsiFinal`.
    */
  private enum CsiScan:

    /** A final byte in 0x40-0x7E arrived; `params` is everything between the introducer and it. */
    case Complete(params: String, finalByte: Int)

    /** The read timed out before the final byte: the sequence never finished arriving and is dropped. */
    case Torn

    /** The sequence outgrew its size budget, so its parameters are incomplete and the whole sequence is dropped. */
    case Overrun

  /** What the read function returns when the input stream has reached end of file.
    *
    * The value is JLine's: `NonBlockingReader.EOF`. Its sibling `READ_EXPIRED` is `-2`, and everything in the decoder
    * that merely asks "did a character arrive?" mid-sequence still tests `< 0`, because a sequence torn off by end of
    * file is torn exactly as one torn off by a timeout is. Only the *first* read of an event distinguishes them.
    */
  private[terminal] val EndOfStream = -1

  /** What the read function returns when the wait elapsed and no character arrived — JLine's `READ_EXPIRED`.
    *
    * This is the sentinel a *scripted* reader wants once its script runs dry: it means "nothing right now", which is
    * what a quiet terminal reports, whereas [[EndOfStream]] means the stream is gone for good and makes the decoder
    * answer `Event.EndOfInput`. A test harness that hands back `EndOfStream` instead is telling the decoder the
    * terminal died, and will be told so on every subsequent call.
    */
  private[terminal] val ReadExpired = -2

  private val NoChar             = -1
  private val Bel                = 7
  private val Esc                = 0x1b
  private val PasteTimeoutMillis = 200L
  private val PasteLimit         = 1 << 20
  private val PasteStart         = 200
  private val PasteEnd           = "\u001b[201~"
  private val MaxParamLength     = 64

  /** The leading parameter of the XTWINOPS reply that carries the text area's pixel size (`CSI 4 ; h ; w t`). Every
    * other leading value is a different XTWINOPS answer — `8` is the size in characters, `9` the screen size — and is
    * dropped rather than misread as pixels.
    */
  private val TextAreaReport = 4

  /** The kitty keyboard protocol's event-type sub-parameter values. Only the release is acted on: a repeat is reported
    * as an ordinary press, which is what every legacy terminal already produces for a held key.
    */
  private val KittyPress   = 1
  private val KittyRelease = 3

  /** How many paste characters one batch may consume before yielding, without abandoning resynchronization. A wake
    * preserves the unfinished batch's budget, so repeated wakes cannot postpone its boundary indefinitely.
    */
  private val PasteDrainLimit = PasteLimit * 16

  /** How many consecutive empty reads — [[PasteTimeoutMillis]] apiece — a paste waits through before yielding its
    * retained prefix and continuing in discard-only mode.
    *
    * It bounds only a sender that has gone *silent*: the count resets on every character that arrives, so a slow paste
    * that keeps trickling is never abandoned, however long it takes in total. After yielding, each subsequent batch has
    * a fresh stall budget; silence never restores ordinary key decoding without a terminator.
    */
  private val PasteStallLimit = 10

  /** How many bytes one CSI sequence may consume before it is abandoned outright. Only reachable once the parameter
    * budget has already overflowed, and far past anything a real terminal emits.
    */
  private val MaxSequenceLength = 4096

  /** The same bound for a control string, whose payload (a colour, a title, a graphics acknowledgement) is short. */
  private val MaxControlStringLength = 4096

  /** How many events may wait in the `deferred` queue before the oldest start being dropped.
    *
    * The render loop's bounded queues use the same number for the same reason. A reply round trip that waits on a
    * silent terminal — a `Duration.Inf` cursor query is documented as legal — defers every key typed while it waits;
    * without a cap, an unattended terminal with something still writing to it grows the queue without end. A quarter
    * thousand is far past anything a real round trip defers: the queries that use the queue otherwise time out within a
    * tenth of a second.
    */
  private[terminal] val DeferredQueueLimit: Int = 256

  /** The largest xterm modifier parameter: `1 + shift|alt|ctrl|meta`. Used only to tell a modified F3 from a
    * cursor-position report, which are otherwise the same shape.
    */
  private val MaxModifierCode = 16

  /** Nanoseconds in a millisecond, for the deadline arithmetic in `awaitReply`. A named constant so the two places that
    * convert cannot disagree by a factor of a thousand — which reads as a query that returns instantly or one that
    * hangs, and neither points at the arithmetic.
    */
  private val NanosPerMilli = 1000000L

  /** The largest millisecond wait that still converts to nanoseconds inside a `Long`. A finite timeout at or above it
    * is treated as "wait until the reply arrives" — the same as `Duration.Inf` — rather than multiplied into a negative
    * deadline.
    */
  private val MaxWaitMillis = Long.MaxValue / NanosPerMilli

  /** How long one read of an unbounded wait blocks for before looping. Long enough that waiting costs nothing, short
    * enough that a thread parked here is not parked in a way a debugger cannot make sense of.
    */
  private val UnboundedPollMillis = 1000L

  /** kitty's super, hyper and meta bits, none of which [[KeyModifiers]] can express. */
  private val UnrepresentableModifiers = 8 | 16 | 32
