package io.worxbend.tui.core

/** Everything a running application can receive from its event source.
  *
  * `Key`/`Mouse`/`Resize` originate from the terminal backend; `Tick` is synthetic, injected by the runtime's runner at
  * its configured tick rate — it lives in this ADT because consumers pattern-match all four together.
  */
enum Event:
  case Key(event: KeyEvent)
  case Mouse(event: MouseEvent)
  case Resize(size: Size)
  case Tick

  /** A bracketed paste: the whole pasted text arrives as one event instead of a storm of key events. */
  case Paste(text: String)

  /** A key came back *up*.
    *
    * Only ever delivered when the application asked for it (`RunnerConfig.keyEventTypes`) *and* the terminal speaks the
    * kitty keyboard protocol's "report event types" flag. Every other terminal reports presses only, so an application
    * that acts solely on releases does nothing at all there — treat a release as an enrichment, never as the event a
    * feature depends on.
    *
    * There is deliberately no `KeyRepeat`. A held key on a legacy terminal already arrives as a stream of ordinary
    * presses, so reporting kitty's auto-repeat as anything else would make held-arrow scrolling behave differently
    * depending on which terminal the app happened to start in. A repeat is a press.
    */
  case KeyRelease(event: KeyEvent)

  /** The terminal window gained or lost focus (mode 1004 reporting). */
  case FocusGained, FocusLost

  /** The terminal switched between a light and a dark colour scheme while the app was running (mode 2031 reporting).
    *
    * A notification, not a reply: the terminal sends it on its own whenever the user's system theme flips, which is why
    * — unlike a cursor-position report or a background-colour answer, which are read inside the backend and never
    * surface — it is an event the application gets to see. The scheme the terminal had *at start-up* does not arrive
    * this way; it is established by the capability probe and read from `Backend.capabilities`.
    *
    * Only ever delivered by a terminal that implements mode 2031 and was asked to report. Everywhere else the scheme
    * stays whatever start-up established, so an application must treat this as an enrichment: a theme that follows it
    * is nicer, and a theme that never hears it is still correct.
    */
  case ColorSchemeChanged(scheme: ColorScheme)

  /** An interrupt signal (SIGINT/SIGQUIT — typically `Ctrl+C`) reached the process.
    *
    * Delivered as an ordinary event rather than killing the JVM, so the runner unwinds through its normal teardown and
    * the terminal is restored. An application that does not consume it quits cleanly.
    */
  case Interrupt

  /** The input stream ended and can never deliver another event.
    *
    * What causes it: stdin reached end of file. Input piped in from a file or another program ran out (`my-app <
    * script.txt`), or the terminal on the other end was closed while the process kept running. This is not the same
    * thing as "no input right now" — a poll that finds nothing simply produces no event at all, and the next one may
    * well produce a key.
    *
    * Unlike [[Interrupt]] this is not a request the application may decline. The handler is offered the event so it can
    * save its work, and the runner then stops the loop and restores the terminal whatever the handler answered: there
    * is no input left to wait for, and a loop that kept polling a dead stream would spin at 100% CPU.
    *
    * A backend with no real input stream — `HeadlessBackend`, and anything driving a test — never produces it.
    */
  case EndOfInput
