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

internal object PureMatrixAlgorithms {
    private const val singularEpsilon = 1e-12

    fun multiply(left: MatrixData, right: MatrixData): MatrixData {
        require(left.cols == right.rows) {
            "matrix multiplication shape mismatch: ${left.rows}x${left.cols} cannot multiply ${right.rows}x${right.cols}"
        }
        val out = DoubleArray(left.rows * right.cols)
        for (row in 0 until left.rows) {
            val leftRowOffset = row * left.cols
            val outRowOffset = row * right.cols
            for (pivot in 0 until left.cols) {
                val leftValue = left.values[leftRowOffset + pivot]
                val rightRowOffset = pivot * right.cols
                for (col in 0 until right.cols) {
                    out[outRowOffset + col] += leftValue * right.values[rightRowOffset + col]
                }
            }
        }
        return MatrixData(left.rows, right.cols, out)
    }

    fun multiply(left: MatrixData, right: VectorData): VectorData {
        require(left.cols == right.size) {
            "matrix-vector multiplication shape mismatch: ${left.rows}x${left.cols} cannot multiply length ${right.size}"
        }
        val out = DoubleArray(left.rows)
        for (row in 0 until left.rows) {
            var sum = 0.0
            val rowOffset = row * left.cols
            for (col in 0 until left.cols) {
                sum += left.values[rowOffset + col] * right.values[col]
            }
            out[row] = sum
        }
        return VectorData(out)
    }

    fun inverse(matrix: MatrixData): MatrixData {
        require(matrix.isSquare) { "matrix inverse requires a square matrix, got ${matrix.rows}x${matrix.cols}" }
        val n = matrix.rows
        val width = n * 2
        val augmented = DoubleArray(n * width)

        for (row in 0 until n) {
            for (col in 0 until n) {
                augmented[row * width + col] = matrix.values[row * n + col]
            }
            augmented[row * width + (n + row)] = 1.0
        }

        for (pivotCol in 0 until n) {
            var pivotRow = pivotCol
            var pivotAbs = kotlin.math.abs(augmented[pivotRow * width + pivotCol])
            for (candidate in pivotCol + 1 until n) {
                val candidateAbs = kotlin.math.abs(augmented[candidate * width + pivotCol])
                if (candidateAbs > pivotAbs) {
                    pivotAbs = candidateAbs
                    pivotRow = candidate
                }
            }
            require(pivotAbs > singularEpsilon) { "matrix is singular and cannot be inverted" }
            if (pivotRow != pivotCol) {
                swapRows(augmented, width, pivotRow, pivotCol)
            }

            val pivotValue = augmented[pivotCol * width + pivotCol]
            val pivotOffset = pivotCol * width
            for (col in 0 until width) {
                augmented[pivotOffset + col] /= pivotValue
            }

            for (row in 0 until n) {
                if (row == pivotCol) continue
                val factor = augmented[row * width + pivotCol]
                if (factor == 0.0) continue
                val rowOffset = row * width
                for (col in 0 until width) {
                    augmented[rowOffset + col] -= factor * augmented[pivotOffset + col]
                }
            }
        }

        val inverse = DoubleArray(n * n)
        for (row in 0 until n) {
            for (col in 0 until n) {
                inverse[row * n + col] = augmented[row * width + n + col]
            }
        }
        return MatrixData(n, n, inverse)
    }

    fun determinant(matrix: MatrixData): Double {
        require(matrix.isSquare) { "matrix determinant requires a square matrix, got ${matrix.rows}x${matrix.cols}" }
        val n = matrix.rows
        val work = matrix.values.copyOf()
        var sign = 1.0
        var determinant = 1.0

        for (pivotCol in 0 until n) {
            var pivotRow = pivotCol
            var pivotAbs = kotlin.math.abs(work[pivotRow * n + pivotCol])
            for (candidate in pivotCol + 1 until n) {
                val candidateAbs = kotlin.math.abs(work[candidate * n + pivotCol])
                if (candidateAbs > pivotAbs) {
                    pivotAbs = candidateAbs
                    pivotRow = candidate
                }
            }
            if (pivotAbs <= singularEpsilon) {
                return 0.0
            }
            if (pivotRow != pivotCol) {
                swapRows(work, n, pivotRow, pivotCol)
                sign = -sign
            }

            val pivotValue = work[pivotCol * n + pivotCol]
            determinant *= pivotValue

            for (row in pivotCol + 1 until n) {
                val rowOffset = row * n
                val factor = work[rowOffset + pivotCol] / pivotValue
                if (factor == 0.0) continue
                for (col in pivotCol + 1 until n) {
                    work[rowOffset + col] -= factor * work[pivotCol * n + col]
                }
            }
        }

        return determinant * sign
    }

    fun trace(matrix: MatrixData): Double {
        require(matrix.isSquare) { "matrix trace requires a square matrix, got ${matrix.rows}x${matrix.cols}" }
        var sum = 0.0
        for (index in 0 until matrix.rows) {
            sum += matrix.values[index * matrix.cols + index]
        }
        return sum
    }

    fun rank(matrix: MatrixData): Int {
        val work = matrix.values.copyOf()
        var rank = 0
        var pivotRow = 0
        val rows = matrix.rows
        val cols = matrix.cols

        for (pivotCol in 0 until cols) {
            var bestRow = -1
            var bestAbs = singularEpsilon
            for (candidate in pivotRow until rows) {
                val absValue = kotlin.math.abs(work[candidate * cols + pivotCol])
                if (absValue > bestAbs) {
                    bestAbs = absValue
                    bestRow = candidate
                }
            }
            if (bestRow == -1) continue
            if (bestRow != pivotRow) {
                swapRows(work, cols, bestRow, pivotRow)
            }
            val pivotValue = work[pivotRow * cols + pivotCol]
            for (row in pivotRow + 1 until rows) {
                val factor = work[row * cols + pivotCol] / pivotValue
                if (kotlin.math.abs(factor) <= singularEpsilon) continue
                val rowOffset = row * cols
                val pivotOffset = pivotRow * cols
                for (col in pivotCol until cols) {
                    work[rowOffset + col] -= factor * work[pivotOffset + col]
                }
            }
            rank += 1
            pivotRow += 1
            if (pivotRow == rows) break
        }
        return rank
    }

    fun solve(matrix: MatrixData, rhs: VectorData): VectorData {
        require(matrix.isSquare) { "matrix solve requires a square matrix, got ${matrix.rows}x${matrix.cols}" }
        require(matrix.rows == rhs.size) {
            "matrix solve shape mismatch: ${matrix.rows}x${matrix.cols} cannot solve length ${rhs.size}"
        }
        val solution = solveAugmented(matrix.rows, matrix.cols, 1, matrix.values, rhs.values)
        return VectorData(solution)
    }

    fun solve(matrix: MatrixData, rhs: MatrixData): MatrixData {
        require(matrix.isSquare) { "matrix solve requires a square matrix, got ${matrix.rows}x${matrix.cols}" }
        require(matrix.rows == rhs.rows) {
            "matrix solve shape mismatch: ${matrix.rows}x${matrix.cols} cannot solve ${rhs.rows}x${rhs.cols}"
        }
        val solution = solveAugmented(matrix.rows, matrix.cols, rhs.cols, matrix.values, rhs.values)
        return MatrixData(matrix.rows, rhs.cols, solution)
    }

    private fun solveAugmented(rows: Int, cols: Int, rhsCols: Int, matrixValues: DoubleArray, rhsValues: DoubleArray): DoubleArray {
        val width = cols + rhsCols
        val augmented = DoubleArray(rows * width)

        for (row in 0 until rows) {
            val matrixOffset = row * cols
            val augmentedOffset = row * width
            for (col in 0 until cols) {
                augmented[augmentedOffset + col] = matrixValues[matrixOffset + col]
            }
            val rhsOffset = row * rhsCols
            for (col in 0 until rhsCols) {
                augmented[augmentedOffset + cols + col] = rhsValues[rhsOffset + col]
            }
        }

        for (pivotCol in 0 until cols) {
            var pivotRow = pivotCol
            var pivotAbs = kotlin.math.abs(augmented[pivotRow * width + pivotCol])
            for (candidate in pivotCol + 1 until rows) {
                val candidateAbs = kotlin.math.abs(augmented[candidate * width + pivotCol])
                if (candidateAbs > pivotAbs) {
                    pivotAbs = candidateAbs
                    pivotRow = candidate
                }
            }
            require(pivotAbs > singularEpsilon) { "matrix is singular and cannot be solved" }
            if (pivotRow != pivotCol) {
                swapRows(augmented, width, pivotRow, pivotCol)
            }

            val pivotOffset = pivotCol * width
            val pivotValue = augmented[pivotOffset + pivotCol]
            for (col in pivotCol until width) {
                augmented[pivotOffset + col] /= pivotValue
            }

            for (row in 0 until rows) {
                if (row == pivotCol) continue
                val factor = augmented[row * width + pivotCol]
                if (kotlin.math.abs(factor) <= singularEpsilon) continue
                val rowOffset = row * width
                for (col in pivotCol until width) {
                    augmented[rowOffset + col] -= factor * augmented[pivotOffset + col]
                }
            }
        }

        val solution = DoubleArray(rows * rhsCols)
        for (row in 0 until rows) {
            val augmentedOffset = row * width + cols
            val solutionOffset = row * rhsCols
            for (col in 0 until rhsCols) {
                solution[solutionOffset + col] = augmented[augmentedOffset + col]
            }
        }
        return solution
    }

    private fun swapRows(data: DoubleArray, stride: Int, firstRow: Int, secondRow: Int) {
        val firstOffset = firstRow * stride
        val secondOffset = secondRow * stride
        for (col in 0 until stride) {
            val tmp = data[firstOffset + col]
            data[firstOffset + col] = data[secondOffset + col]
            data[secondOffset + col] = tmp
        }
    }
}
