## Changelog

### Unreleased

- Multiple Inheritance (MI) completed and enabled by default:
  - Active C3 Method Resolution Order (MRO) for deterministic, monotonic lookup across complex hierarchies and diamonds.
  - Qualified dispatch:
    - `this@Type.member(...)` inside class bodies starts lookup at the specified ancestor.
    - Cast-based disambiguation: `(expr as Type).member(...)`, `(expr as? Type)?.member(...)` (works with existing safe-call `?.`).
  - Field inheritance (`val`/`var`) under MI:
    - Instance storage is disambiguated per declaring class; unqualified read/write resolves to the first match in MRO.
    - Qualified read/write targets the chosen ancestor’s storage.
  - Constructors and initialization:
    - Direct bases are initialized left-to-right; each ancestor is initialized at most once (diamond-safe de-duplication).
    - Header-specified constructor arguments are passed to direct bases.
  - Visibility enforcement under MI:
    - `private` visible only inside the declaring class body.
    - `protected` visible inside the declaring class and any of its transitive subclasses; unrelated contexts cannot access it (qualification/casts do not bypass).
  - Diagnostics improvements:
    - Missing member/field messages include receiver class and linearization order; hints for `this@Type` or casts when helpful.
    - Invalid `this@Type` reports that the qualifier is not an ancestor and shows the receiver lineage.
    - `as`/`as?` cast errors include actual and target type names.

- Documentation updated (docs/OOP.md and tutorial quick-start) to reflect MI with active C3 MRO.

Notes:
- Existing single-inheritance code continues to work; resolution reduces to the single base.
- If code previously relied on non-deterministic parent set iteration, C3 MRO provides a predictable order; disambiguate explicitly if needed using `this@Type`/casts.
