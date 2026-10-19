---
title: Text editing
description: Selection, word motion, clipboard, kill ring and coalesced undo in input and textArea; soft wrap, line numbers and search in textArea; and the promptComposer for chat-style prompts.
---

# Text editing

`input` and `textArea` are two views over one editing engine, `EditBuffer`.
`TextInputState` is an `EditBuffer` holding one line, `TextAreaState` one holding many.
Every operation below is a method on both states, so code that drives an editor directly
(a test, a macro, a custom key binding) calls the same thing the keys call.

The text is stored as grapheme clusters, so no operation can split an emoji, a flag or a
combining sequence. Columns count clusters, and every width is measured through
`CharWidth`.

## Keys

| Keys | What they do | State method |
|---|---|---|
| `Shift` + arrows, `Home`, `End` | extend the selection | `moveLeft(select = true)` and so on |
| `Ctrl+Left` / `Ctrl+Right`, `Alt+B` / `Alt+F` | previous / next word | `moveWordLeft`, `moveWordRight` |
| `Ctrl+Up` / `Ctrl+Down` | previous / next blank line | `moveParagraphUp`, `moveParagraphDown` |
| `Ctrl+A` | select everything | `selectAll` |
| `Ctrl+C` / `Ctrl+X` / `Ctrl+V` | copy, cut, paste | `copy(clipboard)`, `cut`, `paste` |
| `Ctrl+K` / `Ctrl+U` | kill to the end / start of the line | `killToEndOfLine`, `killToStartOfLine` |
| `Ctrl+W`, `Alt+Backspace` / `Alt+D` | kill the word before / after the caret | `deleteWordBackward`, `deleteWordForward` |
| `Ctrl+Y` / `Alt+Y` | yank the newest kill / swap it for the one before | `yank`, `yankPop` |
| `Ctrl+Z` / `Ctrl+Shift+Z` | undo / redo | `undo`, `redo` |

Word boundaries come from `java.text.BreakIterator`, so they follow Unicode rules rather
than splitting on spaces. Punctuation between words is skipped.

With nothing selected, `Ctrl+C` and `Ctrl+X` are not consumed and keep bubbling, so
`Ctrl+C` still quits an app whose input is focused.

:::caution A focused text field keeps editing chords for itself
`Ctrl+U`, `Ctrl+W`, `Ctrl+K`, `Ctrl+Y`, `Ctrl+Z`, `Ctrl+A` and the other keys in
the table above are consumed by a focused `input` or `textArea` before an app binding sees
them, so `binding("ctrl+w", "close tab")` does nothing while the user is typing. Pick app
keys outside that set (`Ctrl+Q`, function keys), or a key sequence behind a
leader. A test that drives a binding with `pilot.press(...)` needs the same care: move
focus off the field first, or the field eats the key.
:::

## Clipboard

A widget never touches a system clipboard. It has no terminal to reach one through.
`copy`, `cut` and `paste` take a `Clipboard`, and the caller decides what that is.
Pass `Clipboard.inMemory()` in a test.

In an app the text nodes use `AppServices.clipboard`. A copy goes out to the system
clipboard through OSC 52 and is also kept in an in-app buffer. A terminal never lets an
app read the system clipboard, so `Ctrl+V` pastes what this app last copied or cut. The
terminal's own paste (`Ctrl+Shift+V` in most terminals) still arrives as a bracketed paste.

## Kill ring

Kills go into a `KillRing`, which is kept apart from the clipboard: `Ctrl+K` does not
overwrite what the user copied. Consecutive kills join into one entry, the way they do in
readline, so `Ctrl+K Ctrl+K` yanks back as one piece. Pass the same `KillRing` to several
states, `TextInputState("", killRing = shared)`, and a kill in one field yanks in another.

## Undo

Undo stores diffs, not copies of the document, so a long editing session costs memory in
proportion to what was typed. Consecutive single-grapheme inserts join one undo step, and
so do consecutive backspaces. Typing `hello` and pressing `Ctrl+Z` removes the word. A
space after a word, a caret move or a selection change starts a new step. The history keeps
the newest 100 steps. Call `breakUndoGroup()` to force a boundary from code.

## Soft wrap, line numbers and syntax highlighting

```scala
textArea(notes).wrap.lineNumbers
textArea(query).lexer(Lexer.Sql)
textArea(source, TextAreaOptions(wrap = true, lineNumbers = true, gutterStyle = Style.Default.dim))
```

`.wrap` breaks long lines at the editor's width instead of scrolling sideways. A line
breaks after its last space where there is one, and between graphemes where there is not,
so CJK text wraps between characters and a wide glyph is never cut in half. Up and Down
still move by logical line.

`.lineNumbers` adds a gutter of right-aligned line numbers, one column wider than the
largest number. The gutter grows by a column at line 10 and again at line 100. A wrapped
line's continuation rows leave it blank.

## Search

`Ctrl+F` opens a `/query` prompt on the editor's bottom row. Typing searches as you go,
from where the caret was. `Enter` keeps the match and closes the prompt, and `Esc` puts the
caret back where it started. Then `F3` and `Shift+F3` step to the next and previous match.
Matches are underlined. The search ignores case unless the query has a capital letter.

`.readOnly` turns a `textArea` into a viewer. Motion, selection, copy and search keep
working, edits do not, and `/`, `n` and `N` search the way they do in `less`:

```scala
textArea(logState).readOnly.lineNumbers
```

From code, the same search is `openSearch()`, `updateSearch(query)`, `closeSearch()`,
`nextMatch()` and `previousMatch()` on `TextAreaState`, with every hit in `matches`.

## Prompt composer

`promptComposer` is the input of an LLM command-line tool:

```scala
private val prompt = PromptComposerState(
  completions = (trigger, query) => trigger match
    case '/' => commands.filter(_.startsWith(query))
    case _   => files.filter(_.contains(query)),
)

def view(using ReactiveScope, Theme): Element =
  column(transcript.fill, promptComposer(prompt)(text => send(text)))
```

- `Enter` submits. The callback receives the text, and the composer clears and records it
  in its history. `Shift+Enter` or `Alt+Enter` inserts a newline.
- `Up` on the first line and `Down` on the last walk the history. Going past the newest
  entry brings back the draft you were typing.
- `Ctrl+R` opens a reverse search over the history, shown as
  ``(reverse-i-search)`dep': deploy prod``. `Ctrl+R` again finds an older match, `Enter`
  loads the match for editing and `Esc` cancels.
- While the caret is at the end of the draft, the newest history entry that starts with it
  is shown dim after the caret. `Tab` or `Right` accepts it.
- `/` at the start of the prompt, or `@` at the start of a word, opens a completion popup
  below the caret. The provider runs on a background worker once typing pauses for
  `debounce` (150 ms by default), and a newer query always replaces an older answer. `Up`
  and `Down` move the highlight, `Enter` or `Tab` accept it and `Esc` closes the popup.
- A paste of `pasteLines` lines or more (10 by default) shows as `[Pasted 50 lines]`. The
  submitted text has the whole paste in its place.

The provider must not touch UI state, because it runs off the render thread. Everything
else on `PromptComposerState` is render-thread-only, like every widget state.

Next: bind your own keys around the editors in [Key bindings](./keybindings), validate
what users type in [Forms & validation](./forms-and-validation), and drive an editor
key by key in [Testing](./testing).
