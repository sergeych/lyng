# Legacy Digest Functions (`lyng.legacy_digest`)

> ⚠️ **Security warning:** The functions in this module use cryptographically broken
> algorithms.  Do **not** use them for passwords, digital signatures, integrity
> verification against adversarial tampering, or any other security-sensitive
> purpose.  They exist solely for compatibility with legacy protocols and file
> formats that require specific hash values.

Import when you need to produce a SHA-1 digest for an existing protocol or format:

```lyng
import lyng.legacy_digest
```

## `LegacyDigest` Object

### `sha1(data): String`

Computes the SHA-1 digest of `data` and returns it as a 40-character lowercase
hex string.

`data` can be:

| Type     | Behaviour                              |
|----------|----------------------------------------|
| `String` | Encoded as UTF-8, then hashed          |
| `Buffer` | Raw bytes hashed directly              |
| anything | Falls back to `toString()` then UTF-8  |

```lyng
import lyng.legacy_digest

// String input
val h = LegacyDigest.sha1("abc")
assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", h)

// Empty string
assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", LegacyDigest.sha1(""))
```

```lyng
import lyng.legacy_digest
import lyng.buffer

// Buffer input (raw bytes)
val buf = Buffer.decodeHex("616263")   // 0x61 0x62 0x63 = "abc"
assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", LegacyDigest.sha1(buf))
```

## Implementation Notes

- Pure Kotlin/KMP — no native libraries or extra dependencies.
- Follows FIPS 180-4.
- The output is always lowercase hex, never uppercase or binary.

## When to Use

Use `lyng.legacy_digest` only when an external system you cannot change requires
a SHA-1 value, for example:

- old git-style content addresses
- some OAuth 1.0 / HMAC-SHA1 signature schemes
- legacy file checksums defined in published specs

For any new design choose a current hash function (SHA-256 or better) once
Lyng adds a `lyng.digest` module.
