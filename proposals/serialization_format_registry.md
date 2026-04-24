# Serialization Format Registry

Current status:

- no global serialization-format registry
- formats are exported explicitly from modules and used explicitly, e.g. `Lynon.encode(...)`, `Json.decode(...)`, or `MyFormat.encode(...)`

Why no registry now:

- explicit module exports already solve the current use case
- a registry adds global mutable state and naming semantics we do not currently need
- there is no current runtime feature that needs format discovery by string name

When a registry may become worth adding:

- config-driven format selection, e.g. `"format": "json"`
- host-side introspection such as "list installed serialization formats"
- collision detection across independently loaded modules
- admin or tooling APIs that need to resolve a format without importing its module explicitly

If added later, the registry should be:

- optional, not required for normal explicit usage
- based on stable fully qualified ids, not just short export names
- designed as a host/tooling facility first, not as part of ordinary script-level serialization
