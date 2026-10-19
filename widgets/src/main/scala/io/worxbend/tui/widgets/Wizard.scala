package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, Style, VersionedState, Widget}

/** Caller-owned state of a multi-step flow: the step on screen and the validation message the last refused `Next` left,
  * if any. Moving in either direction clears the message.
  *
  * Render-thread-only and `Versioned`: every mutation raises [[version]], which is how a running app learns it owes a
  * frame.
  */
final class WizardState(initialStep: Int = 0) extends VersionedState:

  private var currentValue: Int          = math.max(0, initialStep)
  private var errorValue: Option[String] = None

  /** The step on screen, from 0. */
  def current: Int = currentValue

  /** Why the last `Next` was refused, until the user moves. */
  def error: Option[String] = errorValue

  /** Moves to the following step of `count`, clearing any error; a no-op past the last. */
  def next(count: Int): Unit =
    currentValue = math.max(0, math.min(count - 1, currentValue + 1))
    errorValue = None
    touch()

  /** Moves to the previous step, clearing any error; a no-op on the first. */
  def back(): Unit =
    currentValue = math.max(0, currentValue - 1)
    errorValue = None
    touch()

  /** Records why the current step may not be left. */
  def fail(message: String): Unit =
    errorValue = Some(message)
    touch()

  /** Clears the error without moving — what a successful finish does. */
  def clearError(): Unit =
    errorValue = None
    touch()

object WizardState:

  /** A flow starting on step `initialStep`. */
  def apply(initialStep: Int = 0): WizardState = new WizardState(initialStep)

/** The progress header of a multi-step flow: `✓ Account ─ ● Profile ─ ○ Confirm` — finished steps in `doneStyle`, the
  * current one in `currentStyle`, the ones ahead in `pendingStyle`. When the row cannot hold every title it says
  * `Step 2 of 3 · Profile` instead, clipped by display width.
  *
  * Stateless: `current` is read from the flow's [[WizardState]] by whoever builds it. Render-thread use only.
  */
final case class Wizard(
    titles: Seq[String],
    current: Int,
    style: Style = Style.Default,
    currentStyle: Style = Style.Default.bold,
    doneStyle: Style = Style.Default,
    pendingStyle: Style = Style.Default.dim,
    separator: String = " ─ ",
    doneSymbol: String = "✓ ",
    currentSymbol: String = "● ",
    pendingSymbol: String = "○ ",
) extends Widget
    with Measured:

  override def widthAt(height: Int): Option[Int] =
    val _ = height
    Some(fullWidth)

  override def heightAt(width: Int): Option[Int] =
    val _ = width
    Some(1)

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty && titles.nonEmpty then
      val step = math.max(0, math.min(titles.size - 1, current))
      if fullWidth <= area.width then
        var x = area.x
        titles.zipWithIndex.foreach { case (title, index) =>
          if index > 0 then x += buffer.setString(x, area.y, separator, style.patch(pendingStyle), area.right - x)
          val (symbol, own) =
            if index < step then (doneSymbol, doneStyle)
            else if index == step then (currentSymbol, currentStyle)
            else (pendingSymbol, pendingStyle)
          x += buffer.setString(x, area.y, symbol + title, style.patch(own), area.right - x)
        }
      else
        val summary = s"Step ${step + 1} of ${titles.size} · ${titles(step)}"
        buffer.setString(area.x, area.y, CharWidth.substringByWidth(summary, area.width), style.patch(currentStyle))

  private def fullWidth: Int =
    val labels = titles.zipWithIndex.map { case (title, index) =>
      val symbol = if index < current then doneSymbol else if index == current then currentSymbol else pendingSymbol
      CharWidth.of(symbol + title)
    }
    labels.sum + CharWidth.of(separator) * math.max(0, titles.size - 1)
