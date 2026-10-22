package io.worxbend.tui.terminal

/** The shape the terminal draws its own hardware cursor in — DECSCUSR, `CSI n SP q` (XTerm `ctlseqs.ms`, "Set cursor
  * style").
  *
  * What it is for: a modal editor shows a block cursor in its command mode and a bar in its insert mode, and that
  * difference is how the user knows which mode they are in without reading a status line. This is the shape of the
  * *hardware* caret — the one an input method editor anchors its candidate popup to and a screen reader reports as the
  * insertion point — not of a highlighted cell a widget paints into a `Buffer`.
  *
  * [[io.worxbend.tui.terminal.CursorShape.Default Default]] hands the shape back to whatever the user configured in
  * their terminal. It is what `AnsiSequences.RestoreAll` emits, so a process killed mid-edit does not leave a bar
  * cursor behind in the shell it came from. A terminal that does not implement DECSCUSR ignores the sequence, so asking
  * for a shape is never an error, merely sometimes without effect.
  */
enum CursorShape:
  case Default, BlinkingBlock, SteadyBlock, BlinkingUnderline, SteadyUnderline, BlinkingBar, SteadyBar

object CursorShape:

  /** The DECSCUSR parameter for `shape`, 0 to 6.
    *
    * Written out case by case rather than taken from `ordinal`, even though the two agree today. The numbers are a wire
    * format defined by someone else, and tying them to declaration order would mean a case inserted in the middle
    * silently renumbers every case after it. Spelled out, that edit fails to compile instead. It is the same rule
    * `AnsiSequences` follows for colour codes.
    */
  def parameter(shape: CursorShape): Int =
    shape match
      case Default           => 0
      case BlinkingBlock     => 1
      case SteadyBlock       => 2
      case BlinkingUnderline => 3
      case SteadyUnderline   => 4
      case BlinkingBar       => 5
      case SteadyBar         => 6
