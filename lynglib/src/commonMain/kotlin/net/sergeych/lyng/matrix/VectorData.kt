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

import kotlin.math.sqrt

internal data class VectorData(
    val values: DoubleArray
) {
    init {
        require(values.isNotEmpty()) { "vector must have at least one element" }
    }

    val size: Int get() = values.size

    fun at(index: Int): Double {
        require(index in values.indices) { "vector index $index out of bounds for length $size" }
        return values[index]
    }

    fun plus(other: VectorData): VectorData {
        require(size == other.size) { "vector size mismatch: $size vs ${other.size}" }
        return VectorData(DoubleArray(size) { index -> values[index] + other.values[index] })
    }

    fun minus(other: VectorData): VectorData {
        require(size == other.size) { "vector size mismatch: $size vs ${other.size}" }
        return VectorData(DoubleArray(size) { index -> values[index] - other.values[index] })
    }

    fun scale(factor: Double): VectorData =
        VectorData(DoubleArray(size) { index -> values[index] * factor })

    fun divide(divisor: Double): VectorData {
        require(divisor != 0.0) { "vector division by zero" }
        return VectorData(DoubleArray(size) { index -> values[index] / divisor })
    }

    fun dot(other: VectorData): Double {
        require(size == other.size) { "vector size mismatch: $size vs ${other.size}" }
        var sum = 0.0
        for (index in values.indices) {
            sum += values[index] * other.values[index]
        }
        return sum
    }

    fun norm(): Double = sqrt(dot(this))

    fun normalize(): VectorData {
        val length = norm()
        require(length != 0.0) { "cannot normalize a zero vector" }
        return divide(length)
    }

    fun cross(other: VectorData): VectorData {
        require(size == 3 && other.size == 3) { "cross product requires two 3D vectors" }
        return VectorData(
            doubleArrayOf(
                values[1] * other.values[2] - values[2] * other.values[1],
                values[2] * other.values[0] - values[0] * other.values[2],
                values[0] * other.values[1] - values[1] * other.values[0]
            )
        )
    }

    fun outer(other: VectorData): MatrixData {
        val out = DoubleArray(size * other.size)
        for (row in values.indices) {
            val rowOffset = row * other.size
            for (col in other.values.indices) {
                out[rowOffset + col] = values[row] * other.values[col]
            }
        }
        return MatrixData(size, other.size, out)
    }

    fun toList(): List<Double> = values.asList()

    fun render(): String = buildString {
        append("Vector([")
        for (index in values.indices) {
            if (index > 0) append(", ")
            append(formatMatrixValue(values[index]))
        }
        append("])")
    }

    fun compareTo(other: VectorData): Int {
        val sizeCmp = size.compareTo(other.size)
        if (sizeCmp != 0) return sizeCmp
        for (index in values.indices) {
            val cmp = values[index].compareTo(other.values[index])
            if (cmp != 0) return cmp
        }
        return 0
    }
}
