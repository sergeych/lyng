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

package net.sergeych.lyng.matrix

import org.jetbrains.kotlinx.multik.api.mk
import org.jetbrains.kotlinx.multik.api.ndarray
import org.jetbrains.kotlinx.multik.api.linalg.dot
import org.jetbrains.kotlinx.multik.api.linalg.inv
import org.jetbrains.kotlinx.multik.ndarray.data.get

internal actual object PlatformMatrixBackend {
    actual fun multiply(left: MatrixData, right: MatrixData): MatrixData {
        require(left.cols == right.rows) {
            "matrix multiplication shape mismatch: ${left.rows}x${left.cols} cannot multiply ${right.rows}x${right.cols}"
        }
        val leftArray = mk.ndarray(left.values, left.rows, left.cols)
        val rightArray = mk.ndarray(right.values, right.rows, right.cols)
        val product = leftArray dot rightArray
        return MatrixData(left.rows, right.cols, extract(product, left.rows, right.cols))
    }

    actual fun inverse(matrix: MatrixData): MatrixData {
        require(matrix.isSquare) { "matrix inverse requires a square matrix, got ${matrix.rows}x${matrix.cols}" }
        val source = mk.ndarray(matrix.values, matrix.rows, matrix.cols)
        val inverse = mk.linalg.inv(source)
        return MatrixData(matrix.rows, matrix.cols, extract(inverse, matrix.rows, matrix.cols))
    }

    private fun extract(array: org.jetbrains.kotlinx.multik.ndarray.data.D2Array<Double>, rows: Int, cols: Int): DoubleArray {
        val out = DoubleArray(rows * cols)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                out[row * cols + col] = array[row, col]
            }
        }
        return out
    }
}
