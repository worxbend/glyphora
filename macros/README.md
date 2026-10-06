# tui-macros

Compile-time codegen: everywhere the framework bridges *user-defined*
code, the bridge is generated at compile time — never runtime reflection. This is the
constraint that keeps GraalVM native-image builds free of reflect-config JSON.

- **`deriveForm[A]`** — derives a `FormSpec[A]` from a case class via
  `Mirror.ProductOf` (`inline`, stdlib-only): field names become `FieldSpec`s, and each
  field's type contributes its control and its parser through the `FormFieldType`
  summoned for it. A field type with no instance in scope is a compile error.
- **`FormFieldType[A]`** — the one thing a type must supply to be derivable: which
  control to render and how to parse the text back. `String`, `Int`, `Double`,
  `Boolean` and `Option` of those come with the module; a type of your own joins by
  declaring `given FormFieldType[YourType]` in its companion.
- **`FormSpec` / `FieldSpec` / `FieldInput`** — owned here so `tui-dsl` can consume
  them without a circular dependency.
- **`DerivedField[A, V]`** — `spec.field(_.age)` selects a direct case-class field at
  compile time with its exact declared value type, including opaque domain types.
  `.validate(_ >= 18, "must be 18+")` produces a spec-owned `FieldValidation[A]`.
  Checks can reject values but never transform them; compose checks with `.and`.
- **`Field[A]`** — standalone lazily-composed parsers. `map` and `mapValidated` may
  change the parsed type, but these parsers cannot replace a derived form's defaults.
  Customize domain parsing through `FormFieldType` instead.

Derived form submission parses every control, then assembles a typed candidate and
runs its field checks. Parser failures take precedence; no checks run until the
candidate can be assembled, and only a fully valid candidate is published. The
existential parsers and untyped product-assembly seam are internal to the library.
See [forms and migration](../website/docs/forms-and-validation.md).

CI enforces the zero-reflection rule with a grep over all main sources
(`.github/workflows/ci.yml`).
