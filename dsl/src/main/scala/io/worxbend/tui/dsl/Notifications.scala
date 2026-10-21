package io.worxbend.tui.dsl

import io.worxbend.tui.widgets.NoticeLevel

import scala.concurrent.duration.FiniteDuration

/** One application's toast stack, as a value that can be passed around.
  *
  * `TuiApp.notify` is a `protected` method on the app, so only code written inside the app's own body can raise a
  * toast. A helper function or an element factory living in another file — the shape most applications reach for as
  * soon as a view grows past one screen — had to be handed a callback by the app to say anything to the user.
  *
  * This is the same capability expressed as a value. Take it as a `using` parameter and any helper anywhere can notify:
  *
  * {{{
  * def saveRow(row: Row)(using notifications: Notifications): Unit =
  *   repository.save(row)
  *   notifications.success(s"saved \${row.name}")
  * }}}
  *
  * Ownership and threads: an instance is bound to one `TuiApp` and, like everything else that writes a `Signal`, must
  * be called on that app's render thread — which every event handler, timer body and `Async` continuation already is. A
  * toast ages out on its own: the run keeps its ambient tick going for as long as a toast is live, so neither a
  * `config.tickRate` nor an ambient animation is needed for one to disappear.
  */
trait Notifications:

  /** Shows `message` in the top-right corner for `duration`. */
  def notify(message: String, level: NoticeLevel, duration: FiniteDuration): Unit

  /** Drops every toast, shown or still queued. */
  def dismissToasts(): Unit

  /** Tells the user something finished, wherever they are looking.
    *
    * Always a toast, so the message is on the frame when the user comes back to it. When the terminal window is
    * *unfocused* (the terminal reported `FocusLost` and no `FocusGained` since) the same message also goes out as a
    * desktop notification — OSC 9, 777 or 99, whichever the terminal speaks — because a toast nobody is looking at
    * tells nobody anything. Best-effort on that second half: a terminal without a notification protocol, or one inside
    * a multiplexer, shows the toast alone.
    */
  def desktop(title: String, body: String): Unit

  /** `message` for the default three seconds, in the theme's accent colour. */
  final def info(message: String): Unit = notify(message, NoticeLevel.Info, DefaultToastDuration)

  /** `message` for the default three seconds, in the theme's success colour. */
  final def success(message: String): Unit = notify(message, NoticeLevel.Success, DefaultToastDuration)

  /** `message` for the default three seconds, in the theme's warning colour. */
  final def warn(message: String): Unit = notify(message, NoticeLevel.Warning, DefaultToastDuration)

  /** `message` for the default three seconds, in the theme's error colour. */
  final def error(message: String): Unit = notify(message, NoticeLevel.Error, DefaultToastDuration)
