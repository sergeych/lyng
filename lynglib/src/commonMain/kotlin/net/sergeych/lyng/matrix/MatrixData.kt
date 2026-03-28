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

internal data class MatrixData(
    val rows: Int,
    val cols: Int,
    val values: DoubleArray
) {
    init {
        require(rows > 0) { "matrix must have at least one row" }
        require(cols > 0) { "matrix must have at least one column" }
        require(values.size == rows * cols) {
            "matrix data size ${values.size} does not match shape ${rows}x${cols}"
        }
    }

    val isSquare: Boolean get() = rows == cols

    fun at(row: Int, col: Int): Double {
        require(row in 0 until rows) { "row index $row out of bounds for $rows rows" }
        require(col in 0 until cols) { "column index $col out of bounds for $cols columns" }
        return values[row * cols + col]
    }

    fun rowValues(row: Int): DoubleArray {
        require(row in 0 until rows) { "row index $row out of bounds for $rows rows" }
        val start = row * cols
        return values.copyOfRange(start, start + cols)
    }

    fun columnValues(col: Int): DoubleArray {
        require(col in 0 until cols) { "column index $col out of bounds for $cols columns" }
        return DoubleArray(rows) { row -> values[row * cols + col] }
    }

    fun slice(rowIndices: IntArray, colIndices: IntArray): MatrixData {
        require(rowIndices.isNotEmpty()) { "matrix slice must include at least one row" }
        require(colIndices.isNotEmpty()) { "matrix slice must include at least one column" }
        val out = DoubleArray(rowIndices.size * colIndices.size)
        var offset = 0
        for (row in rowIndices) {
            require(row in 0 until rows) { "row index $row out of bounds for $rows rows" }
            val rowOffset = row * cols
            for (col in colIndices) {
                require(col in 0 until cols) { "column index $col out of bounds for $cols columns" }
                out[offset++] = values[rowOffset + col]
            }
        }
        return MatrixData(rowIndices.size, colIndices.size, out)
    }

    fun plus(other: MatrixData): MatrixData {
        requireSameShape(other)
        return MatrixData(rows, cols, DoubleArray(values.size) { index -> values[index] + other.values[index] })
    }

    fun minus(other: MatrixData): MatrixData {
        requireSameShape(other)
        return MatrixData(rows, cols, DoubleArray(values.size) { index -> values[index] - other.values[index] })
    }

    fun scale(factor: Double): MatrixData =
        MatrixData(rows, cols, DoubleArray(values.size) { index -> values[index] * factor })

    fun divide(divisor: Double): MatrixData {
        require(divisor != 0.0) { "matrix division by zero" }
        return MatrixData(rows, cols, DoubleArray(values.size) { index -> values[index] / divisor })
    }

    fun transpose(): MatrixData {
        val out = DoubleArray(values.size)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                out[col * rows + row] = values[row * cols + col]
            }
        }
        return MatrixData(cols, rows, out)
    }

    fun multiply(other: MatrixData): MatrixData = PlatformMatrixBackend.multiply(this, other)

    fun multiply(other: VectorData): VectorData = PureMatrixAlgorithms.multiply(this, other)

    fun solve(other: VectorData): VectorData = PureMatrixAlgorithms.solve(this, other)

    fun solve(other: MatrixData): MatrixData = PureMatrixAlgorithms.solve(this, other)

    fun determinant(): Double = PureMatrixAlgorithms.determinant(this)

    fun inverse(): MatrixData = PlatformMatrixBackend.inverse(this)

    fun trace(): Double = PureMatrixAlgorithms.trace(this)

    fun rank(): Int = PureMatrixAlgorithms.rank(this)

    fun toNestedLists(): List<List<Double>> =
        List(rows) { row ->
            List(cols) { col -> values[row * cols + col] }
        }

    fun render(): String = buildString {
        append("Matrix(")
        append(rows)
        append("x")
        append(cols)
        append(", [")
        for (row in 0 until rows) {
            if (row > 0) append(", ")
            append("[")
            for (col in 0 until cols) {
                if (col > 0) append(", ")
                append(formatMatrixValue(values[row * cols + col]))
            }
            append("]")
        }
        append("])")
    }

    fun compareTo(other: MatrixData): Int {
        val rowCmp = rows.compareTo(other.rows)
        if (rowCmp != 0) return rowCmp
        val colCmp = cols.compareTo(other.cols)
        if (colCmp != 0) return colCmp
        for (index in values.indices) {
            val cmp = values[index].compareTo(other.values[index])
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun requireSameShape(other: MatrixData) {
        require(rows == other.rows && cols == other.cols) {
            "matrix shape mismatch: ${rows}x${cols} vs ${other.rows}x${other.cols}"
        }
    }
}
