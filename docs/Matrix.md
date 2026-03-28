# Matrix (`lyng.matrix`)

`lyng.matrix` adds dense immutable `Matrix` and `Vector` types for linear algebra.

Import it when you need matrix or vector arithmetic:

```lyng
import lyng.matrix
```

## Construction

Create vectors from a flat list and matrices from nested row lists:

```lyng
import lyng.matrix

val v: Vector = vector([1, 2, 3])
val m: Matrix = matrix([[1, 2, 3], [4, 5, 6]])

assertEquals([1.0, 2.0, 3.0], v.toList())
assertEquals([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]], m.toList())
```

Factory methods are also available:

```lyng
import lyng.matrix

val z: Vector = Vector.zeros(3)
val i: Matrix = Matrix.identity(3)
val m: Matrix = Matrix.zeros(2, 4)
```

All elements are standard double-precision numeric values internally.

## Shapes

Matrices may have any rectangular geometry:

```lyng
import lyng.matrix

val a: Matrix = matrix([[1, 2, 3], [4, 5, 6]])

assertEquals(2, a.rows)
assertEquals(3, a.cols)
assertEquals([2, 3], a.shape)
assertEquals(false, a.isSquare)
```

Vectors expose:

- `size`
- `length` as an alias of `size`

## Matrix Operations

Supported matrix operations:

- `+` and `-` for element-wise matrix arithmetic
- `*` for matrix-matrix product
- `*` and `/` by a scalar
- `transpose()`
- `trace()`
- `rank()`
- `determinant()`
- `inverse()`
- `solve(rhs)` for `Vector` or `Matrix` right-hand sides

Example:

```lyng
import lyng.matrix

val a: Matrix = matrix([[1, 2, 3], [4, 5, 6]])
val b: Matrix = matrix([[7, 8], [9, 10], [11, 12]])
val product: Matrix = a * b
assertEquals([[58.0, 64.0], [139.0, 154.0]], product.toList())
assertEquals([[1.0, 4.0], [2.0, 5.0], [3.0, 6.0]], a.transpose().toList())
```

Inverse and solve:

```lyng
import lyng.matrix

val a: Matrix = matrix([[4, 7], [2, 6]])
val rhs: Vector = vector([1, 0])

val inv: Matrix = a.inverse()
val x: Vector = a.solve(rhs)

assert(abs(a.determinant() - 10.0) < 1e-9)
assert(abs(inv.get(0, 0) - 0.6) < 1e-9)
assert(abs(x.get(0) - 0.6) < 1e-9)
```

## Vector Operations

Supported vector operations:

- `+` and `-`
- scalar `*` and `/`
- `dot(other)`
- `norm()`
- `normalize()`
- `cross(other)` for 3D vectors
- `outer(other)` producing a matrix

```lyng
import lyng.matrix

val a: Vector = vector([1, 2, 3])
val b: Vector = vector([2, 0, 0])

assertEquals(2.0, a.dot(b))
assertEquals([0.2672612419124244, 0.5345224838248488, 0.8017837257372732], a.normalize().toList())
```

## Indexing and Slicing

`Matrix` supports both method-style indexing and bracket syntax.

Scalar access:

```lyng
import lyng.matrix

val m: Matrix = matrix([[1, 2, 3], [4, 5, 6]])

assertEquals(6.0, m.get(1, 2))
assertEquals(6.0, m[1, 2])
```

Bracket indexing accepts two selectors: `[row, col]`.
Each selector may be either:

- an `Int`
- a `Range`

Examples:

```lyng
import lyng.matrix

val m: Matrix = matrix([[1, 2, 3, 4], [5, 6, 7, 8], [9, 10, 11, 12]])

assertEquals(7.0, m[1, 2])
val columnSlice: Matrix = m[0..2, 2]
val topLeft: Matrix = m[0..1, 0..1]
val tail: Matrix = m[1.., 1..]
assertEquals([[3.0], [7.0], [11.0]], columnSlice.toList())
assertEquals([[1.0, 2.0], [5.0, 6.0]], topLeft.toList())
assertEquals([[6.0, 7.0, 8.0], [10.0, 11.0, 12.0]], tail.toList())
```

Shape rules:

- `m[Int, Int]` returns a `Real`
- `m[Range, Int]` returns an `Nx1` `Matrix`
- `m[Int, Range]` returns a `1xM` `Matrix`
- `m[Range, Range]` returns a submatrix

Open-ended ranges are supported:

- `m[..1, ..1]`
- `m[1.., 1..]`
- `m[.., 2]`

Stepped ranges are not supported in matrix slicing.

Slices currently return new matrices, not views.

## Rows and Columns

If you want plain lists instead of a sliced matrix:

```lyng
import lyng.matrix

val a: Matrix = matrix([[1, 2, 3], [4, 5, 6]])

assertEquals([4.0, 5.0, 6.0], a.row(1))
assertEquals([2.0, 5.0], a.column(1))
```

## Backend Notes

The matrix module uses a platform-specific backend where available and falls back to pure Kotlin where needed.

The public Lyng API stays the same across platforms.
