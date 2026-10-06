---
title: Forms & validation
description: Derive reflection-free forms from Scala case classes, add validators, submit values, and improve accessible output.
---

# Forms & validation

glyphora can derive a live form from a Scala 3 case class at compile time. Each field's
type becomes a terminal control; submission parses every value, publishes inline errors,
and publishes the case class only when all fields are valid.

There is no runtime reflection, annotation scanner, or `reflect-config.json`.

## Derive a form

```scala
import io.worxbend.tui.dsl.*
import io.worxbend.tui.macros.deriveForm

final case class Signup(username: String, age: Int, subscribe: Boolean)

private val spec = deriveForm[Signup]
private val signup = FormState.of(
  spec,
  spec.field(_.username).validate(_.trim.nonEmpty, "required"),
  spec.field(_.age).validate(_ >= 18, "must be 18 or older"),
)
```

`deriveForm[Signup]` produces metadata, the original per-type parsers, and a direct
constructor call during compilation. `spec.field(_.age)` returns a
`DerivedField[Signup, Int]`: the compiler checks that the selector names a direct
case-class field and retains its **exact declared type**. Generic substitutions and
opaque domain types are preserved; sharing an input control does not make `Int` and
`Long`, `Double` and `BigDecimal`, or `String` and `Email` interchangeable.

A typo or computed selection is a compile error:

```scala
spec.field(_.userName)             // no such field
spec.field(_.age.toString)         // not a direct field selection
spec.field[Any](_.age)             // must retain the exact declared Int type
spec.field(_.age).map(_.toString)  // handles have no parser-mapping API
```

Validation only accepts or rejects an existing value. It cannot return a replacement:

```scala
spec.field(_.subscribe).validate(identity, "you must accept the terms")
spec.field(_.age).validate { age =>
  if age >= 18 then Right(()) else Left("must be 18 or older")
}
```

Use the same `spec` instance for the state and its handles. A validator from a different
spec is rejected at construction, even when both derive the same case class. Two rules
for the same field must be explicitly composed with `.and` rather than passed separately.

### Deliberate pre-1.0 API change

`FormState.of(spec, Field.int("age").mapValidated(...))` is no longer accepted.
Standalone `Field` values are parsers, not derived-field validators. Replace them with
`spec.field(_.age).validate(...)`. Validators now return `Right(())` rather than a
replacement value. `FormSpec` no longer exposes construction, `copy`, default-parser
replacement, or untyped product assembly to consumers.

Submission has two phases: **parse all controls**, then **check the typed candidate**.
If any parser fails, only parser errors are published and no typed checks run. Once all
values parse, all field checks run and their errors are collected by field name. The
case-class constructor therefore runs before these checks, but the candidate is published
to `result` only when every check accepts it. Keep constructors and checks pure and quick.
This replaces the old mixed parse-and-replacement pipeline; a blank numeric field can
show its parse error before a required-text check is evaluated.

## Which field types derive

| Field type | Control | Blank input |
| --- | --- | --- |
| `String` | text input | the empty string |
| `Int` | number input — non-digits are refused as you type | a parse error |
| `Double` | number input that also takes one decimal point | a parse error |
| `Long` | the same whole-number input as `Int`, without `Int`'s range limit | a parse error |
| `BigDecimal` | the same decimal input as `Double`, kept exact rather than rounded to binary | a parse error |
| `Boolean` | checkbox | — |
| `java.util.UUID` | text input; the usual hyphenated form, `123e4567-e89b-12d3-a456-426614174000` | a parse error |
| `java.time.LocalDate` | text input; ISO-8601 `YYYY-MM-DD`, for example `2026-09-01` | a parse error |
| `java.time.LocalTime` | text input; ISO-8601 `HH:MM` or `HH:MM:SS` | a parse error |
| `java.time.LocalDateTime` | text input; the two joined by a literal `T`, `2026-09-01T14:30` | a parse error |
| `java.time.Duration` | text input; ISO-8601 duration, `PT5M30S` is five minutes thirty seconds | a parse error |
| `Option[A]` | the same control `A` would get | `None` |
| an enum of parameterless cases | a picklist, once it opts in (below) | — |

The date, time and identifier types render as a plain text field because this library has
no calendar picker or masked entry yet. Their text is trimmed before parsing, so a stray
leading space is not an error, and a value the parser cannot read comes back as the
message shown next to the field — never as an exception, which on the render thread would
end the application rather than let the user correct the typo.

Anything else — a `java.net.URI`, a nested case class, a domain type of your own — is a
compile error rather than a runtime surprise:

```
deriveForm: no form control is defined for a field of type java.net.URI. Out of the box
String, Int, Long, Double, BigDecimal, Boolean, java.util.UUID,
java.time.LocalDate/LocalTime/LocalDateTime/Duration and Option of those are supported;
define a `given FormFieldType[java.net.URI]` next to your own type to teach the
derivation about it.
```

Typed handles do not compare control kinds to establish value identity: the compiler
uses the declared Scala field type, so same-control and opaque-domain mismatches are
compile errors rather than submission-time casts.

## Teach the derivation about your own type

The list above is not a fixed set the library owns; it is just the instances that ship
with it. A `FormFieldType[A]` says two things — which control the field is edited with,
and how the typed text becomes an `A` (or a message to show the user). Put one in your
type's companion object and it derives like any built-in:

```scala
import io.worxbend.tui.macros.{FieldInput, FormFieldType}

opaque type Email = String

object Email:
  def from(raw: String): Either[String, Email] =
    if raw.contains("@") then Right(raw.trim) else Left(s"'$raw' is not an email address")

  given FormFieldType[Email] = FormFieldType(FieldInput.TextField)(Email.from)

final case class Contact(email: Email, cc: Option[Email])
// deriveForm[Contact] now compiles; `cc` accepts blank as None
```

`parse` runs on the render thread when the user submits, so keep it pure and quick — no
network calls, no locks.

### An enum becomes a picklist

An enum whose cases all take no parameters is the most natural choice field there is, and
one line in its companion turns it into one:

```scala
import io.worxbend.tui.macros.FormFieldType

enum Environment:
  case Development, Staging, Production

object Environment:
  given FormFieldType[Environment] = FormFieldType.ofEnum[Environment]

final case class Deployment(service: String, environment: Environment)
// deriveForm[Deployment] now compiles; `environment` renders as a one-row cycler
```

`FormFieldType.ofEnum` reads the case names out of the type and collects the case values
by implicit search, both at compile time, so nothing about this reads a class at runtime
and a native image needs no reflection configuration for it. Every case's name becomes one
option; the label the user chose is matched back to the case, ignoring surrounding
whitespace and letter case. A case that takes parameters has no singleton value, so it
fails to compile naming that case rather than producing a picklist that cannot represent
it.

It is opt-in rather than automatic on purpose. An unconditional given for every
`Mirror.SumOf` would collide with the one for `Option`, which is a sum type too, and would
also quietly claim every sealed hierarchy whose cases happen to take no parameters —
including ones that are not a choice a user should be offered.

`FormFieldType.ofLabels` is the same thing with labels you choose, for when the case names
are not what the user should read:

```scala
given FormFieldType[Tier] = FormFieldType.ofLabels(Seq("no charge" -> Tier.Free, "billed" -> Tier.Paid))
```

A picklist always has something showing, so unlike a text field there is no "nothing
entered" state: an untouched form submits the first option. Validate it like any other
field, with its typed handle:

```scala
val deploymentSpec = deriveForm[Deployment]
FormState.of(
  deploymentSpec,
  deploymentSpec.field(_.environment).validate {
    case Environment.Production => Left("production needs an approval")
    case _                      => Right(())
  },
)
```

## Render and submit

```scala
def view(using ReactiveScope, Theme): Element =
  panel("Create account")(
    Form(signup),
    spacer(1),
    signup.result.get match
      case Some(value) => text(s"Welcome, ${value.username}!").fg(Color.Green)
      case None        => text("Tab next · Space toggle · Ctrl+S submit").dim,
  ).rounded.onKey(Key.CtrlS) {
    signup.submit()
  }
```

`FormState` exposes two signals:

- `errors: Signal[Map[String, String]]` contains validation failures by field name;
- `result: Signal[Option[A]]` contains the assembled value after a valid submit.

Calling `submit()` validates the entire form. On failure it replaces `errors` and
clears `result`; on success it clears errors and publishes the case class.

## Compose validators and separate parser mapping

Compose checks on one handle; the first failed check wins for that field:

```scala
val name = spec.field(_.username)
val required = name.validate(_.trim.nonEmpty, "required")
val short = name.validate(_.length <= 40, "at most 40 characters")
val signup = FormState.of(spec, required.and(short))
```

These checks never trim or replace the submitted name. Normalize through a domain
parser before validation, using the open `FormFieldType` extension point. For example,
the `Email` parser above trims input and returns an actual `Email`, and its handle
validates that domain value rather than an erased `String`.

Standalone parsers still support type-changing `map` and `mapValidated`:

```scala
import io.worxbend.tui.macros.Field
val countAsText = Field.int("count").map(_.toString)
val parsed: Either[String, String] = countAsText.parse("42")
```

This parser is useful for a manual form but cannot be passed to `FormState.of`.
A derived form obtains all its parsers from the `FormFieldType` instances selected by
`deriveForm`; validation does not replace or re-summon them.

## Accessible form output

Color should never be the only signal. `Form.accessible` renders the same state with
explicit position and status text:

```scala
val formView =
  if accessibleMode.get then Form.accessible(signup)
  else Form(signup)
```

The accessible variant announces `Field 1 of 3`, spells out checkbox state, and
prefixes failures with `Error:`. Pair it with a straightforward `Tab`/`Enter` key
flow and a high-contrast theme.

## Build a manual form when needed

A `FormFieldType` covers a field the user types into. A layout the derivation cannot
express — a nested case class, a radio group over an enum, a suggestion list that queries
a service as you type — is still an ordinary column of controls:

```scala
import io.worxbend.tui.widgets.TextInputState

private val environment = Signal(0)
private val replicas = TextInputState("2")

column(
  radioGroup(Seq("staging", "production"), environment),
  numberInput(replicas),
  button("Deploy") { submitDeployment() },
).gap(1)
```

Manual forms follow the same ownership model: the application owns control state,
the widget renders it, and focused built-in handlers mutate it.

## Complete example

Run the repository example:

```bash
./mill examples.form-demo.run
```

Its source and headless test live under
[`examples/form-demo`](https://github.com/oleksandr-balyshyn/glyphora/tree/main/examples/form-demo).
For modals and multi-step flows, continue with [The app shell](./app-shell).
