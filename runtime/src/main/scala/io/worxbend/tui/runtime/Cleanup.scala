package io.worxbend.tui.runtime

/** Narrow teardown aggregation shared by run-owned resources and screen callbacks. The first failure remains primary;
  * later failures are suppressed, with self-suppression excluded. Only used while releasing already-owned resources.
  */
private[tui] final class Cleanup:
  private var failure: Option[Throwable] = None

  def attempt(body: => Unit): Unit =
    try body
    catch
      case error: Throwable =>
        failure match
          case None        => failure = Some(error)
          case Some(first) => Cleanup.suppress(first, error)

  def collected: Option[Throwable] = failure

  def rethrow(): Unit = failure.foreach(error => throw error)

private[tui] object Cleanup:
  def suppress(primary: Throwable, secondary: Throwable): Unit =
    if !(primary eq secondary) && !primary.getSuppressed.exists(_ eq secondary) then primary.addSuppressed(secondary)

  def all(actions: (() => Unit)*): Unit =
    val cleanup = new Cleanup
    actions.foreach(action => cleanup.attempt(action()))
    cleanup.rethrow()
