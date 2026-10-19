package io.worxbend.tui.core

import scala.collection.mutable.ArrayBuffer
import scala.quoted.{Expr, Quotes, Varargs}

/** `markup"[bold red]error[/] in [link=$url]$file[/]"` — inline styling in a string literal, checked when the code is
  * compiled.
  *
  * A tag is written in square brackets and holds one or more space-separated words; it styles everything up to the
  * matching `[/]`, and tags nest, each inner one layered onto the outer with [[Style.patch]]. The words are:
  *
  *   - '''attributes''': `bold`, `dim`, `italic`, `underline`, `blink`, `reverse`, `hidden`, `strike`;
  *     `underline=double`, `underline=curly`, `underline=dotted`, `underline=dashed` for a styled underline;
  *   - '''colours''', in any spelling [[Color.parse]] reads — `red`, `bright_cyan`, `#ff8800`, `208` — plus `ansi(n)`
  *     for a palette index that reads as one at a glance. A bare colour is the foreground; `on <colour>` is the
  *     background, so `[red on blue]` is red text on blue. `fg=<colour>` and `bg=<colour>` are the explicit forms;
  *   - `link=<url>`, an OSC 8 hyperlink, carried on [[Style.link]].
  *
  * `[/]` closes the innermost open tag. A named close — `[/bold]`, `[/bold red]`, `[/link]` for a `[link=…]` — closes
  * it too, and is checked: it must name the innermost open tag (its words as written, ignoring case and spacing, or the
  * key of a single `key=value` word), so `[bold][red]x[/bold]` is a compile error rather than a silent mis-nesting.
  * `[[` is a literal `[`; a `]` outside a tag is already literal and needs no escaping. The usual string escapes work,
  * and a `\n` in the literal starts a new row of the resulting [[Text]].
  *
  * An interpolated `$value` is '''text, never markup''': whatever it contains — `[red]` included — is rendered as the
  * characters it is, in the style in force where it appears. That is what makes it safe to write
  * `markup"[bold]$input[/]"` over input the program did not author. A value may also stand as the whole value of a
  * `link=`, `fg=` or `bg=` word — `[link=$url]`, `[fg=$colour]` — where it must be a `String` for a link and a
  * [[Color]] for a colour, and the compiler checks that too.
  *
  * Every error in the literal parts — a word that is not a style (`[bogus]`), a tag that is never closed, a `[/]` with
  * nothing to close, a named close that names some other tag, a `[` with no `]` — stops the build with a message naming
  * it, instead of being found the first time that view is drawn. What survives into the compiled code is the
  * constructor calls for the spans, with every literal style already a constant: nothing is parsed when the program
  * runs, and nothing is reflected over.
  */
extension (inline sc: StringContext) inline def markup(inline args: Any*): Text = ${ MarkupLiteral.expand('sc, 'args) }

/** The implementation behind the `markup"…"` interpolator above.
  *
  * Public only because an inline method's body is expanded in whichever module *calls* it, so this object has to be
  * reachable from there — the same reason [[KeySpecLiteral]] is. It is not an API: call the interpolator.
  */
object MarkupLiteral:

  /** Turns the literal parts of a `markup"…"` into the code that builds its [[Text]], or aborts the compilation. */
  def expand(sc: Expr[StringContext], args: Expr[Seq[Any]])(using q: Quotes): Expr[Text] =
    import q.reflect.report
    (sc.value, args) match
      case (Some(context), Varargs(holes)) =>
        parse(context.parts, holes.size) match
          case Right(lines)  => encodeText(lines, holes)
          case Left(problem) => report.errorAndAbort(s"bad markup: $problem", sc)
      case (Some(_), _)                    =>
        report.errorAndAbort("markup\"…\" needs its interpolated values spelled out at the call site", sc)
      case (None, _)                       =>
        report.errorAndAbort("markup\"…\" must be a string literal", sc)

  // ---------------------------------------------------------------------------------------------------------------
  // The grammar. Pure functions over the literal parts, so the whole of what the interpolator accepts is in one place.
  // ---------------------------------------------------------------------------------------------------------------

  /** One word of a tag, resolved. A hole is a `$value` standing in for the word's value. */
  private enum Attribute:
    case Literal(style: Style)
    case FgHole(index: Int)
    case BgHole(index: Int)
    case LinkHole(index: Int)

  /** One styled piece of one row of the result. */
  private enum Piece:
    case Literal(content: String, steps: List[Attribute])
    case Hole(index: Int, steps: List[Attribute])

  /** The `[name=` keys whose value may be a hole, with the attribute that carries it. */
  private val HoleKeys: Map[String, Int => Attribute] = Map(
    "link" -> Attribute.LinkHole.apply,
    "fg"   -> Attribute.FgHole.apply,
    "bg"   -> Attribute.BgHole.apply,
  )

  private val Vocabulary: String =
    "an attribute (bold, dim, italic, underline, blink, reverse, hidden, strike, underline=double|curly|dotted|dashed), " +
      "a colour (red, bright_cyan, #ff8800, ansi(208)) for the foreground, 'on <colour>' for the background, " +
      "fg=<colour>, bg=<colour>, link=<url>, or [/] (or [/name]) to close the innermost tag"

  /** Parses the literal `parts` of a template with `holeCount` interpolated values between them into rows of pieces.
    * `Left` carries the first problem found, worded for a compile error.
    */
  private def parse(parts: Seq[String], holeCount: Int): Either[String, Seq[Seq[Piece]]] =
    val lines   = ArrayBuffer(ArrayBuffer.empty[Piece])
    val stack   = ArrayBuffer.empty[List[Attribute]] // open tags, outermost first
    val written = ArrayBuffer.empty[String]          // each open tag's words as written, in step with `stack`
    val run     = StringBuilder()
    var pending = Option.empty[String]               // a tag left open at a part boundary, awaiting its hole and `]`

    def steps: List[Attribute] = stack.toList.flatten

    def flushRun(): Unit =
      if run.nonEmpty then
        lines.last += Piece.Literal(run.result(), steps)
        run.clear()

    def closeTag(): Unit =
      flushRun()
      stack.remove(stack.length - 1)
      written.remove(written.length - 1)

    def openTag(body: String, trailingHole: Option[Attribute], asWritten: String): Either[String, Unit] =
      val words = body.trim.split("\\s+").toList.filter(_.nonEmpty)
      if words == List("/") then
        if stack.isEmpty then Left("[/] closes nothing: there is no open tag before it")
        else Right(closeTag())
      else if words.headOption.exists(_.startsWith("/")) && trailingHole.isEmpty then
        val name = normalizedTag(body.trim.drop(1))
        if stack.isEmpty then Left(s"[/$name] closes nothing: there is no open tag before it")
        else if namesTag(name, written.last) then Right(closeTag())
        else
          Left(
            s"[/$name] does not close the innermost open tag [${written.last}]: a named close must name the tag it " +
              "closes, or use [/]"
          )
      else if words.isEmpty && trailingHole.isEmpty then Left("an empty tag [] is not a style; did you mean [[?")
      else
        attributes(words).map { resolved =>
          flushRun()
          stack += resolved ++ trailingHole.toList
          written += normalizedTag(asWritten)
        }

    def attributes(words: List[String]): Either[String, List[Attribute]] =
      words match
        case Nil                    => Right(Nil)
        case "on" :: colour :: rest =>
          parseColor(colour).flatMap(c => attributes(rest).map(Attribute.Literal(Style.bg(c)) :: _))
        case "on" :: Nil            => Left("'on' must be followed by a background colour, as in [red on blue]")
        case word :: rest           => attribute(word).flatMap(a => attributes(rest).map(a :: _))

    var index = 0
    var error = Option.empty[String]
    while error.isEmpty && index < parts.length do
      val part = processEscapes(parts(index))
      part match
        case Left(problem) => error = Some(problem)
        case Right(text)   =>
          var at = 0
          // a hole standing as a tag value must be followed immediately by the tag's `]`
          if pending.isDefined then
            if text.startsWith("]") then
              pending = None
              at = 1
            else
              error = Some(s"a tag whose value is an interpolated value must end right after it: [${pending.get}$$…]")
          while error.isEmpty && at < text.length do
            val c = text.charAt(at)
            if c == '[' then
              if at + 1 < text.length && text.charAt(at + 1) == '[' then
                run.append('[')
                at += 2
              else
                val close = text.indexOf(']', at + 1)
                if close >= 0 then
                  val body = text.slice(at + 1, close)
                  openTag(body, None, body) match
                    case Left(problem) => error = Some(problem)
                    case Right(())     => at = close + 1
                else
                  val body = text.drop(at + 1)
                  val key  = body.trim.split("\\s+").lastOption.filter(_.endsWith("="))
                  if index == parts.length - 1 then error = Some(s"the tag [$body is never closed with ]")
                  else if key.isEmpty then
                    error = Some(
                      s"a tag cannot be built from an interpolated value ([$body$$…]): an interpolated value is text, " +
                        "never markup, except as the whole value of link=, fg= or bg="
                    )
                  else
                    val name = key.get.dropRight(1)
                    HoleKeys.get(name) match
                      case None       =>
                        error = Some(
                          s"'$name=' cannot take an interpolated value: only link=, fg= and bg= can, and anywhere else " +
                            "a value is text, not markup"
                        )
                      case Some(hole) =>
                        val words = body.trim.split("\\s+").toList.dropRight(1).filter(_.nonEmpty)
                        openTag(words.mkString(" "), Some(hole(index)), body) match
                          case Left(problem) => error = Some(problem)
                          case Right(())     =>
                            pending = Some(body)
                            // the hole is consumed by the tag, not rendered as text
                            at = text.length
            else if c == '\n' then
              flushRun()
              lines += ArrayBuffer.empty[Piece]
              at += 1
            else
              run.append(c)
              at += 1
          // the hole after this part, unless the tag just opened took it as its value
          if error.isEmpty && index < holeCount && pending.isEmpty then
            flushRun()
            lines.last += Piece.Hole(index, steps)
      index += 1

    error match
      case Some(problem)             => Left(problem)
      case None if pending.isDefined => Left(s"the tag [${pending.get}$$…] is never closed with ]")
      case None if stack.nonEmpty    => Left(s"${stack.length} tag(s) are never closed: every tag needs a matching [/]")
      case None                      =>
        flushRun()
        Right(lines.map(_.toSeq).toSeq)

  /** A tag's words as the error messages and the named-close check compare them: lower case, single-spaced. */
  private def normalizedTag(body: String): String =
    body.trim.split("\\s+").filter(_.nonEmpty).mkString(" ").toLowerCase(java.util.Locale.ROOT)

  /** Whether a named close `[/name]` names the open tag written as `open` (both normalised): the same words, or — for a
    * tag that is a single `key=value` word, `[link=…]`, `[fg=…]` — the key alone.
    */
  private def namesTag(name: String, open: String): Boolean =
    name == open || (!open.contains(' ') && open.contains('=') && open.takeWhile(_ != '=') == name)

  /** One word of an open tag, or the reason it is not one. */
  private def attribute(word: String): Either[String, Attribute] =
    val lower = word.toLowerCase(java.util.Locale.ROOT)
    lower match
      case "bold" | "b"                              => Right(Attribute.Literal(Style.Default.bold))
      case "dim"                                     => Right(Attribute.Literal(Style.Default.dim))
      case "italic" | "i"                            => Right(Attribute.Literal(Style.Default.italic))
      case "underline" | "u"                         => Right(Attribute.Literal(Style.Default.underline))
      case "blink"                                   => Right(Attribute.Literal(Style.Default.blink))
      case "reverse"                                 => Right(Attribute.Literal(Style.Default.reverse))
      case "hidden"                                  => Right(Attribute.Literal(Style.Default.hidden))
      case "strike" | "strikethrough" | "crossedout" => Right(Attribute.Literal(Style.Default.crossedOut))
      case "underline=double"                        => Right(Attribute.Literal(Style.Default.doubleUnderline))
      case "underline=curly"                         => Right(Attribute.Literal(Style.Default.curlyUnderline))
      case "underline=dotted"                        => Right(Attribute.Literal(Style.Default.dottedUnderline))
      case "underline=dashed"                        => Right(Attribute.Literal(Style.Default.dashedUnderline))
      case _ if lower.startsWith("fg=")              =>
        parseColor(word.drop(3)).map(c => Attribute.Literal(Style.fg(c)))
      case _ if lower.startsWith("bg=")              =>
        parseColor(word.drop(3)).map(c => Attribute.Literal(Style.bg(c)))
      case _ if lower.startsWith("link=")            =>
        val url = word.drop(5)
        if url.isEmpty then Left("link= needs a URL, as in [link=https://example.com] or [link=$url]")
        else Right(Attribute.Literal(Style.Default.withLink(url)))
      case _                                         =>
        parseColor(word).map(c => Attribute.Literal(Style.fg(c)))

  /** A colour word: `ansi(n)` for a palette index, or anything [[Color.parse]] reads. */
  private def parseColor(word: String): Either[String, Color] =
    val lower = word.toLowerCase(java.util.Locale.ROOT)
    if lower.startsWith("ansi(") && lower.endsWith(")") then
      lower.slice(5, lower.length - 1).toIntOption.filter(n => n >= 0 && n <= 255) match
        case Some(n) => Right(Color.Indexed(n))
        case None    => Left(s"'$word' is not a palette colour: ansi(n) takes an index in 0..255")
    else Color.parse(word).left.map(_ => s"'$word' is not a style: expected $Vocabulary")

  private def processEscapes(part: String): Either[String, String] =
    try Right(StringContext.processEscapes(part))
    catch case e: StringContext.InvalidEscapeException => Left(e.getMessage)

  // ---------------------------------------------------------------------------------------------------------------
  // Code generation: the parsed rows rebuilt as constructor calls, every literal style folded into one constant.
  // ---------------------------------------------------------------------------------------------------------------

  private def encodeText(lines: Seq[Seq[Piece]], holes: Seq[Expr[Any]])(using Quotes): Expr[Text] =
    val encodedLines = lines.map { pieces =>
      val spans =
        if pieces.isEmpty then Seq('{ Span("", Style.Default) })
        else
          pieces.map {
            case Piece.Literal(content, steps) => '{ Span(${ Expr(content) }, ${ encodeStyle(steps, holes) }) }
            case Piece.Hole(index, steps)      =>
              val value = holes(index)
              '{ Span($value.toString, ${ encodeStyle(steps, holes) }) }
          }
      '{ Line(${ Expr.ofSeq(spans) }) }
    }
    '{ Text(${ Expr.ofSeq(encodedLines) }) }

  /** The style of a run: the open tags' attributes in order, literal ones folded together at compile time and the holes
    * applied as builder calls on the result, so a run with no hole in its tags is one constant.
    */
  private def encodeStyle(steps: List[Attribute], holes: Seq[Expr[Any]])(using q: Quotes): Expr[Style] =
    import q.reflect.report
    var emitted = Option.empty[Expr[Style]] // the code so far, once a hole has forced some
    var literal = Style.Default             // the literal attributes read since then, folded

    /** The code for everything read so far: the folded literal alone when no hole preceded it. */
    def flush(): Expr[Style] =
      val folded = literal
      literal = Style.Default
      emitted match
        case None                                  => encodeLiteral(folded)
        case Some(expr) if folded == Style.Default => expr
        case Some(expr)                            => '{ $expr.patch(${ encodeLiteral(folded) }) }

    steps.foreach:
      case Attribute.Literal(style)  => literal = literal.patch(style)
      case Attribute.FgHole(index)   =>
        val base = flush()
        emitted = Some('{ $base.withFg(${ colorArgument(holes(index), "fg") }) })
      case Attribute.BgHole(index)   =>
        val base = flush()
        emitted = Some('{ $base.withBg(${ colorArgument(holes(index), "bg") }) })
      case Attribute.LinkHole(index) =>
        val base = flush()
        holes(index) match
          case '{ $url: String } => emitted = Some('{ $base.withLink($url) })
          case other             => report.errorAndAbort(s"[link=$$…] takes a String, not a ${other.show}", other)
    flush()

  private def colorArgument(hole: Expr[Any], key: String)(using q: Quotes): Expr[Color] =
    import q.reflect.report
    hole match
      case '{ $color: Color } => color
      case other              => report.errorAndAbort(s"[$key=$$…] takes a Color, not a ${other.show}", other)

  /** A fully known style as the expression that rebuilds it. */
  private def encodeLiteral(style: Style)(using Quotes): Expr[Style] =
    if style == Style.Default then '{ Style.Default }
    else
      val fg                         = encodeOptionColor(style.fg)
      val bg                         = encodeOptionColor(style.bg)
      val underlineColor             = encodeOptionColor(style.underlineColor)
      val link: Expr[Option[String]] = style.link match
        case Some(url) => '{ Some(${ Expr(url) }) }
        case None      => '{ None }
      val modifiers                  = encodeModifiers(style.modifiers)
      val underlineStyle             = '{ UnderlineStyle.valueOf(${ Expr(style.underlineStyle.toString) }) }
      '{
        Style(
          fg = $fg,
          bg = $bg,
          modifiers = $modifiers,
          link = $link,
          underlineColor = $underlineColor,
          underlineStyle = $underlineStyle,
        )
      }

  private def encodeOptionColor(color: Option[Color])(using Quotes): Expr[Option[Color]] =
    color match
      case Some(c) => '{ Some(${ encodeColor(c) }) }
      case None    => '{ None }

  /** `Modifiers` is an opaque `Int`, so the held flags are ORed back together out of the public constants. */
  private def encodeModifiers(modifiers: Modifiers)(using Quotes): Expr[Modifiers] =
    val flags: List[(Modifiers, Expr[Modifiers])] = List(
      Modifiers.Bold       -> '{ Modifiers.Bold },
      Modifiers.Dim        -> '{ Modifiers.Dim },
      Modifiers.Italic     -> '{ Modifiers.Italic },
      Modifiers.Underline  -> '{ Modifiers.Underline },
      Modifiers.Blink      -> '{ Modifiers.Blink },
      Modifiers.RapidBlink -> '{ Modifiers.RapidBlink },
      Modifiers.Reverse    -> '{ Modifiers.Reverse },
      Modifiers.Hidden     -> '{ Modifiers.Hidden },
      Modifiers.CrossedOut -> '{ Modifiers.CrossedOut },
    )
    flags.filter((flag, _) => modifiers.hasAny(flag)).foldLeft('{ Modifiers.None }) { (acc, entry) =>
      val one = entry._2
      '{ $acc | $one }
    }

  /** One arm per [[Color]] case, so a colour added later fails this file at compile time. */
  private def encodeColor(color: Color)(using Quotes): Expr[Color] =
    color match
      case Color.Rgb(r, g, b)   => '{ Color.Rgb(${ Expr(r) }, ${ Expr(g) }, ${ Expr(b) }) }
      case Color.Indexed(index) => '{ Color.Indexed(${ Expr(index) }) }
      case Color.Reset          => '{ Color.Reset }
      case Color.Black          => '{ Color.Black }
      case Color.Red            => '{ Color.Red }
      case Color.Green          => '{ Color.Green }
      case Color.Yellow         => '{ Color.Yellow }
      case Color.Blue           => '{ Color.Blue }
      case Color.Magenta        => '{ Color.Magenta }
      case Color.Cyan           => '{ Color.Cyan }
      case Color.White          => '{ Color.White }
      case Color.BrightBlack    => '{ Color.BrightBlack }
      case Color.BrightRed      => '{ Color.BrightRed }
      case Color.BrightGreen    => '{ Color.BrightGreen }
      case Color.BrightYellow   => '{ Color.BrightYellow }
      case Color.BrightBlue     => '{ Color.BrightBlue }
      case Color.BrightMagenta  => '{ Color.BrightMagenta }
      case Color.BrightCyan     => '{ Color.BrightCyan }
      case Color.BrightWhite    => '{ Color.BrightWhite }
