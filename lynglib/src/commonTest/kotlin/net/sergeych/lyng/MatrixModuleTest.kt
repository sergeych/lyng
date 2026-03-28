/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MatrixModuleTest {
    @Test
    fun testMatrixConstructionAndShape() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.matrix

            val a: Matrix = matrix([[1, 2, 3], [4, 5, 6]])
            val v: Vector = vector([1, 2, 3])
            assertEquals(2, a.rows)
            assertEquals(3, a.cols)
            assertEquals([2, 3], a.shape)
            assertEquals(false, a.isSquare)
            assertEquals([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]], a.toList())
            assertEquals("Matrix(2x3, [[1, 2, 3], [4, 5, 6]])", a.toString())
            assertEquals(3, v.size)
            assertEquals([1.0, 2.0, 3.0], v.toList())
            assertEquals("Vector([1, 2, 3])", v.toString())
            assertEquals([0.2672612419124244, 0.5345224838248488, 0.8017837257372732], v.normalize().toList())
            """.trimIndent()
        )
    }

    @Test
    fun testMatrixArithmeticAndTranspose() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.matrix

            val a: Matrix = matrix([[1, 2, 3], [4, 5, 6]])
            val b: Matrix = matrix([[7, 8], [9, 10], [11, 12]])
            val product: Matrix = a * b
            assertEquals([[58.0, 64.0], [139.0, 154.0]], product.toList())

            val scaled: Matrix = product * 0.5
            assertEquals([[29.0, 32.0], [69.5, 77.0]], scaled.toList())

            val sum: Matrix = matrix([[1, 2], [3, 4]]) + Matrix.identity(2)
            assertEquals([[2.0, 2.0], [3.0, 5.0]], sum.toList())

            val transposed: Matrix = a.transpose()
            assertEquals([[1.0, 4.0], [2.0, 5.0], [3.0, 6.0]], transposed.toList())
            assertEquals([4.0, 5.0, 6.0], a.row(1))
            assertEquals([2.0, 5.0], a.column(1))

            val x: Vector = vector([1, 0.5, -1])
            val y: Vector = a * x
            assertEquals([-1.0, 0.5], y.toList())

            val shifted: Vector = x + vector([2, 2, 2])
            assertEquals([3.0, 2.5, 1.0], shifted.toList())
            val d0: Vector = vector([1, 2, 3])
            val d1: Vector = vector([2, 0, 0])
            assertEquals(2.0, d0.dot(d1))
            val cx0: Vector = vector([1, 0, 0])
            val cx1: Vector = vector([0, 1, 0])
            assertEquals([0.0, 0.0, 1.0], cx0.cross(cx1).toList())
            val o0: Vector = vector([1.5, 3.0])
            val o1: Vector = vector([2, 2.6666666666666665])
            assertEquals([[3.0, 4.0], [6.0, 8.0]], o0.outer(o1).toList())
            """.trimIndent()
        )
    }

    @Test
    fun testMatrixDeterminantAndInverse() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.matrix

            val eps = 1e-9
            val a: Matrix = matrix([[4, 7], [2, 6]])
            assert(abs(a.determinant() - 10.0) < eps)
            assertEquals(10.0, a.trace())
            assertEquals(2, a.rank())

            val inv: Matrix = a.inverse()
            assert(abs(inv.get(0, 0) - 0.6) < eps)
            assert(abs(inv.get(0, 1) + 0.7) < eps)
            assert(abs(inv.get(1, 0) + 0.2) < eps)
            assert(abs(inv.get(1, 1) - 0.4) < eps)

            val identity: Matrix = a * inv
            assert(abs(identity.get(0, 0) - 1.0) < eps)
            assert(abs(identity.get(0, 1)) < eps)
            assert(abs(identity.get(1, 0)) < eps)
            assert(abs(identity.get(1, 1) - 1.0) < eps)

            val rhs: Vector = vector([1, 0])
            val solution: Vector = a.solve(rhs)
            assert(abs(solution.get(0) - 0.6) < eps)
            assert(abs(solution.get(1) + 0.2) < eps)

            val rhsMatrix: Matrix = Matrix.identity(2)
            val solvedMatrix: Matrix = a.solve(rhsMatrix)
            assert(abs(solvedMatrix.get(0, 0) - 0.6) < eps)
            assert(abs(solvedMatrix.get(1, 1) - 0.4) < eps)

            val lowRank: Matrix = matrix([[1, 2], [2, 4]])
            assertEquals(1, lowRank.rank())
            """.trimIndent()
        )
    }

    @Test
    fun testMatrixBracketIndexingAndSlices() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            import lyng.matrix

            val m: Matrix = matrix([[1, 2, 3, 4], [5, 6, 7, 8], [9, 10, 11, 12]])

            assertEquals(7.0, m[1, 2])

            val columnSlice: Matrix = m[0..2, 2]
            assertEquals([[3.0], [7.0], [11.0]], columnSlice.toList())

            val topLeft: Matrix = m[0..1, 0..1]
            assertEquals([[1.0, 2.0], [5.0, 6.0]], topLeft.toList())

            val tail: Matrix = m[1.., 1..]
            assertEquals([[6.0, 7.0, 8.0], [10.0, 11.0, 12.0]], tail.toList())

            val rowSlice: Matrix = m[1, 1..2]
            assertEquals([[6.0, 7.0]], rowSlice.toList())
            """.trimIndent()
        )
    }
}
