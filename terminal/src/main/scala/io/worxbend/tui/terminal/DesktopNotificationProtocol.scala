package io.worxbend.tui.terminal

import java.util.Locale

/** Which of the three desktop-notification escape sequences a terminal understands.
  *
  * There is no query for this, and the three dialects are not interchangeable: a terminal that speaks two of them shows
  * two notifications for one message, and one that speaks none ignores all three. So exactly one is sent, chosen from
  * the environment once per backend by [[detect]], and the choice is a best guess that errs towards the dialect most
  * terminals accept.
  */
enum DesktopNotificationProtocol:

  /** `OSC 9 ; text ST` — iTerm2's form, also accepted by WezTerm, Ghostty and Windows Terminal. One field: the title
    * and the body are joined into it.
    */
  case Osc9

  /** `OSC 777 ; notify ; title ; body ST` — rxvt-unicode's form, and the one most other terminals (kitty, foot,
    * WezTerm, Ghostty, Contour) accept as well. The default when nothing in the environment says otherwise.
    */
  case Osc777

  /** `OSC 99 ; … ST` — kitty's own protocol, with a title chunk and a body chunk. Used only when the environment says
    * this is kitty, because kitty also accepts [[Osc777]] and would otherwise show the message twice.
    */
  case Osc99

object DesktopNotificationProtocol:

  /** The dialect this environment calls for, or `None` when no notification should be sent at all.
    *
    * `None` is the capability gate: under tmux or GNU screen (`TMUX` or `STY` set) the multiplexer swallows an OSC it
    * does not know rather than passing it to the outer terminal, so a notification written there reaches nobody and the
    * backend reports `desktopNotifications` as `Support.No`. The same two variables gate the other unprobeable OSC
    * strings — pointer shapes and taskbar progress — for the same reason.
    *
    * Otherwise: iTerm2 (`TERM_PROGRAM=iTerm.app`) gets [[Osc9]], kitty (`TERM=xterm-kitty` or `KITTY_WINDOW_ID` set)
    * gets [[Osc99]], and everything else gets [[Osc777]].
    */
  def detect(env: Map[String, String] = sys.env): Option[DesktopNotificationProtocol] =
    if insideMultiplexer(env) then None
    else if env.get("TERM_PROGRAM").exists(_.equalsIgnoreCase("iTerm.app")) then Some(Osc9)
    else if env.contains("KITTY_WINDOW_ID") || env.get("TERM").exists(_.toLowerCase(Locale.ROOT) == "xterm-kitty")
    then Some(Osc99)
    else Some(Osc777)

  /** Whether the process is talking to tmux or screen rather than to a terminal that would show an OSC string. */
  def insideMultiplexer(env: Map[String, String]): Boolean =
    env.get("TMUX").exists(_.nonEmpty) || env.get("STY").exists(_.nonEmpty)
