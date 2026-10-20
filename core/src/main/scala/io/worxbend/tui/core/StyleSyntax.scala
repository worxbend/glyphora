package io.worxbend.tui.core

import java.util.Locale

/** The word grammar behind [[Style.parse]] and [[Style.render]] — kept out of `Style` itself so the value class stays
  * about values. Pure; no state.
  */
private[core] object StyleSyntax:

  /** Every text attribute by the word a style is written with, in the order [[render]] writes them. */
  private val Attributes: Seq[(String, Modifiers)] = Seq(
    "bold"        -> Modifiers.Bold,
    "dim"         -> Modifiers.Dim,
    "italic"      -> Modifiers.Italic,
    "underline"   -> Modifiers.Underline,
    "blink"       -> Modifiers.Blink,
    "rapid-blink" -> Modifiers.RapidBlink,
    "reverse"     -> Modifiers.Reverse,
    "hidden"      -> Modifiers.Hidden,
    "crossed-out" -> Modifiers.CrossedOut,
  )

  private val Underlines: Seq[(String, UnderlineStyle)] = Seq(
    "straight-underline" -> UnderlineStyle.Straight,
    "double-underline"   -> UnderlineStyle.Double,
    "curly-underline"    -> UnderlineStyle.Curly,
    "dotted-underline"   -> UnderlineStyle.Dotted,
    "dashed-underline"   -> UnderlineStyle.Dashed,
  )

  /** The first words of the two-word colour names [[Color.parse]] reads, so `bright red` can keep its space. */
  private val ColourPrefixes: Set[String] = Set("bright", "light", "dark")

  def parse(text: String): Either[String, Style] =
    tokens(text).flatMap {
      case Nil                                                           => Right(Style.Default)
      case single :: Nil if single.toLowerCase(Locale.ROOT) == "default" => Right(Style.Default)
      case words                                                         => read(words, Style.Default, fgSet = false)
    }

  private def tokens(text: String): Either[String, List[String]] =
    val words   = List.newBuilder[String]
    val word    = StringBuilder()
    var quoted  = false
    var escaped = false
    var started = false
    var error   = Option.empty[String]
    text.foreach { char =>
      if escaped then
        char match
          case 'n'        => word += '\n'
          case 'r'        => word += '\r'
          case 't'        => word += '\t'
          case '\\' | '"' => word += char
          case _          => error = Some("invalid quoted style escape")
        escaped = false
      else if quoted && char == '\\' then escaped = true
      else if char == '"' && (quoted || !started) then
        quoted = !quoted
        started = true
      else if !quoted && char.isWhitespace then
        if started then
          words += word.result()
          word.clear()
          started = false
      else
        word += char
        started = true
    }
    if quoted || escaped then Left("unterminated quoted style word")
    else
      if started then words += word.result()
      error.toLeft(words.result())

  private def quote(text: String): String =
    "\"" + text.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char => char.toString
    } + "\""

  @scala.annotation.tailrec
  private def read(words: List[String], style: Style, fgSet: Boolean): Either[String, Style] =
    words match
      case Nil          => Right(style)
      case word :: rest =>
        val key = word.toLowerCase(Locale.ROOT)
        attribute(key) match
          case Some(flags) => read(rest, style.patch(Style.mods(flags)), fgSet)
          case None        =>
            if key.startsWith("not-") && attribute(key.drop(4)).nonEmpty then
              read(rest, style.without(attribute(key.drop(4)).getOrElse(Modifiers.None)), fgSet)
            else
              Underlines.find(_._1 == key) match
                case Some((_, underline)) => read(rest, style.withUnderlineStyle(underline), fgSet)
                case None                 =>
                  key match
                    case "on"              =>
                      colour(rest, "on") match
                        case Left(error)           => Left(error)
                        case Right((color, after)) => read(after, style.withBg(color), fgSet)
                    case "underline-color" =>
                      colour(rest, "underline-color") match
                        case Left(error)           => Left(error)
                        case Right((color, after)) => read(after, style.withUnderlineColor(color), fgSet)
                    case "link"            =>
                      rest match
                        case url :: after => read(after, style.withLink(url), fgSet)
                        case Nil          => Left("'link' needs a target after it")
                    case _                 =>
                      if fgSet then Left(s"'$word' is a second foreground colour; write 'on $word' for a background")
                      else
                        colour(words, "") match
                          case Left(_)               => Left(s"'$word' is not a style word or a colour")
                          case Right((color, after)) => read(after, style.withFg(color), fgSet = true)

  private def attribute(key: String): Option[Modifiers] = Attributes.find(_._1 == key).map(_._2)

  /** The colour at the head of `words` — one word, or a prefix and a word — and the words after it. */
  private def colour(words: List[String], after: String): Either[String, (Color, List[String])] =
    words match
      case Nil                                                                                =>
        Left(s"'$after' needs a colour after it")
      case prefix :: name :: rest if ColourPrefixes.contains(prefix.toLowerCase(Locale.ROOT)) =>
        Color.parse(s"$prefix $name").map(_ -> rest).orElse(Color.parse(prefix).map(_ -> (name :: rest)))
      case word :: rest                                                                       =>
        Color.parse(word).map(_ -> rest)

  def render(style: Style): String =
    val words =
      Attributes.collect { case (word, flag) if style.modifiers.hasAny(flag) => word } ++
        Attributes.collect { case (word, flag) if style.clearedModifiers.hasAny(flag) => s"not-$word" } ++
        Underlines.collect { case (word, underline) if style.underlineStyle == underline => word } ++
        style.fg.map(_.render).toSeq ++
        style.bg.map(color => s"on ${color.render}").toSeq ++
        style.underlineColor.map(color => s"underline-color ${color.render}").toSeq ++
        style.link.map(url => s"link ${quote(url)}").toSeq
    if words.isEmpty then "default" else words.mkString(" ")
