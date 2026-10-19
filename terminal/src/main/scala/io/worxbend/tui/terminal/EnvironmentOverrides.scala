package io.worxbend.tui.terminal

import java.util.Locale

/** The `GLYPHORA_*` environment variables that override what the library detects about a terminal, for the user whose
  * terminal misreports itself — "my keys don't work", "the mouse eats my clicks", "Esc takes forever". Each one only
  * narrows or pins behaviour; none switches on something the app did not ask for.
  *
  *   - `GLYPHORA_COLOR_DEPTH` = `truecolor` | `256` | `16` | `mono` | `none` — pins [[ColorDepth.detect]]'s answer.
  *   - `GLYPHORA_KEYBOARD` = `legacy` — never enables the kitty keyboard protocol, even when the terminal offers it.
  *   - `GLYPHORA_MOUSE` = `off` | `buttons` — ignores an app's mouse capture, or holds it to clicks and drags when the
  *     app asks for all-motion tracking.
  *   - `GLYPHORA_ESCAPE_DELAY` = milliseconds, `1`..`2000` — how long a lone `Esc` waits for the rest of a sequence
  *     before it is reported as the Escape key (50 by default). Raise it over a slow SSH link where arrow keys arrive
  *     as Escape plus letters; lower it if Escape feels sluggish.
  *
  * Values compare case-insensitively under `Locale.ROOT`; an unrecognised value is ignored, never an error. The others
  * — `GLYPHORA_ASCII`, `GLYPHORA_NO_CAPABILITY_PROBE` — are read where they act, and all of them are listed on the
  * troubleshooting page.
  *
  * Pure functions of the environment map they are given; safe from any thread.
  */
object EnvironmentOverrides:

  val ColorDepthVar: String  = "GLYPHORA_COLOR_DEPTH"
  val KeyboardVar: String    = "GLYPHORA_KEYBOARD"
  val MouseVar: String       = "GLYPHORA_MOUSE"
  val EscapeDelayVar: String = "GLYPHORA_ESCAPE_DELAY"

  private val MaxEscapeDelayMillis: Long = 2000L

  private def read(env: Map[String, String], name: String): Option[String] =
    env.get(name).map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty)

  /** The palette `GLYPHORA_COLOR_DEPTH` pins, when it names one. */
  def colorDepth(env: Map[String, String]): Option[ColorDepth] =
    read(env, ColorDepthVar).collect {
      case "truecolor" | "24bit" | "rgb"  => ColorDepth.TrueColor
      case "256"                          => ColorDepth.Ansi256
      case "16"                           => ColorDepth.Ansi16
      case "mono" | "monochrome" | "2"    => ColorDepth.Monochrome
      case "none" | "0" | "off" | "false" => ColorDepth.NoColor
    }

  /** Whether `GLYPHORA_KEYBOARD=legacy` keeps the kitty keyboard protocol off. */
  def legacyKeyboard(env: Map[String, String]): Boolean = read(env, KeyboardVar).contains("legacy")

  /** The capture mode to actually enable when an app asks for `requested`: `Off` under `GLYPHORA_MOUSE=off`, at most
    * `Buttons` under `GLYPHORA_MOUSE=buttons`. An app that asked for `Off` gets `Off` whatever the variable says.
    */
  def mouseMode(requested: MouseCaptureMode, env: Map[String, String]): MouseCaptureMode =
    read(env, MouseVar) match
      case _ if requested == MouseCaptureMode.Off => MouseCaptureMode.Off
      case Some("off" | "none" | "0" | "false")   => MouseCaptureMode.Off
      case Some("buttons")                        => MouseCaptureMode.Buttons
      case _                                      => requested

  /** How long, in milliseconds, a lone `Esc` waits for the rest of a sequence. */
  def escapeDelayMillis(env: Map[String, String]): Long =
    read(env, EscapeDelayVar)
      .flatMap(_.toLongOption)
      .fold(InputDecoder.DefaultEscapeTimeoutMillis)(millis => math.max(1L, math.min(MaxEscapeDelayMillis, millis)))
