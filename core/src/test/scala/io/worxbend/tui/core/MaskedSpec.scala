package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

final class MaskedSpec extends AnyFunSuite:

  test("an ASCII secret gets one mask character each"):
    assert(Masked("hunter2", "*").value == "*******")

  test("an empty secret masks to nothing"):
    assert(Masked("", "*").value.isEmpty)
    assert(Masked("", "*").width == 0)

  test("the default mask is used when none is given"):
    assert(Masked("ab").value == "••")

  test("one mask per grapheme cluster, not per code unit"):
    // a family emoji (many code points, one cluster) and "e" plus a combining acute accent (two code units, one
    // cluster): the naive "*" * content.length would emit eight masks for these two characters
    val secret = "👨‍👩‍👧" + "e" + 0x0301.toChar
    val masked = Masked(secret, "*")
    assert(masked.value == "**")
    assert(masked.value != "*" * secret.length)

  test("a wide mask character is measured in columns"):
    assert(Masked("abc", "＊").width == 6)

  test("only the first cluster of a multi-character mask is used"):
    assert(Masked("ab", "xy").value == "xx")

  test("an empty mask falls back to the default rather than drawing nothing"):
    assert(Masked("ab", "").value == Masked.DefaultMaskChar * 2)

  test("the conversions carry the mask, and the styled one carries the style"):
    val masked = Masked("secret", "*")
    assert(masked.toSpan == Span.raw("******"))
    assert(masked.toLine.plainText == "******")
    assert(masked.toText.plainText == "******")
    assert(masked.styled(Style.Default.bold) == Span("******", Style.Default.bold))

  test("toString shows the mask and never the secret"):
    val masked = Masked("hunter2", "*")
    assert(masked.toString == "*******")
    assert(!masked.toString.contains("hunter2"))
    assert(masked.content == "hunter2") // the plaintext is still reachable when explicitly asked for

  test("a zero-width mask glyph falls back to the default mask"):
    // a lone combining mark, a zero-width space, a tab and a zero-width joiner are each one cluster of width zero: as
    // the mask they would draw a zero-width secret, which is exactly what the empty-string guard exists to prevent
    for mask <- Seq("́", "​", "\t", "‍") do
      val masked = Masked("secret", mask)
      assert(masked.value == Masked.DefaultMaskChar * 6, s"mask U+${mask.codePointAt(0).toHexString}")
      assert(masked.width == 6)
