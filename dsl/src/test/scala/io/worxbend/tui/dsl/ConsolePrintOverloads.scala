package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}

/** Compile-time evidence that the `Console.print` overloads leave a [[View]]'s context function inferable: each call
  * below has to resolve for this file to compile. Nothing here is ever invoked — every call is behind a thunk — so no
  * test writes to the forked JVM's standard output.
  *
  * A plain object rather than lines in `ConsoleSpec`, because `DisciplineSpec` rightly fails any *suite* that contains
  * a console-print call, and this file is the one place such calls are written on purpose without being run.
  */
private[dsl] object ConsolePrintOverloads:

  private val widget: Widget = (_: Rect, _: Buffer) => ()

  val calls: Seq[() => Unit] = Seq(
    () => Console.print(panel("B")(text("x"))),
    () => Console.print(panel("B")(text("x")), width = 10, colors = ColorDepth.NoColor),
    () => Console.print(widget, Size(2, 2)),
    () => Console.printLines(Text.raw("x")),
  )
