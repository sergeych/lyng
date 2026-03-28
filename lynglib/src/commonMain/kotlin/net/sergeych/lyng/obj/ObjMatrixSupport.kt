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

package net.sergeych.lyng.obj

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.matrix.MatrixData
import net.sergeych.lyng.matrix.VectorData
import net.sergeych.lyng.requiredArg

object ObjMatrixSupport {
    private sealed interface MatrixAxisIndex {
        data class Single(val value: Int) : MatrixAxisIndex
        data class Slice(val values: IntArray) : MatrixAxisIndex
    }

    private object BoundMarker
    private val defaultMatrix = MatrixData(1, 1, doubleArrayOf(0.0))
    private val defaultVector = VectorData(doubleArrayOf(0.0))

    suspend fun bindTo(module: ModuleScope) {
        val matrixClass = module.requireClass("Matrix")
        val vectorClass = module.requireClass("Vector")
        if (matrixClass.kotlinClassData === BoundMarker && vectorClass.kotlinClassData === BoundMarker) return

        bindVectorClass(vectorClass, matrixClass)
        bindMatrixClass(matrixClass, vectorClass)
    }

    private fun bindVectorClass(vectorClass: ObjClass, matrixClass: ObjClass) {
        if (vectorClass.kotlinClassData === BoundMarker) return
        vectorClass.kotlinClassData = BoundMarker
        vectorClass.isAbstract = false

        val hooks = vectorClass.bridgeInitHooks ?: mutableListOf<suspend (ScopeFacade, ObjInstance) -> Unit>().also {
            vectorClass.bridgeInitHooks = it
        }
        hooks += { _, instance -> instance.kotlinInstanceData = defaultVector }

        vectorClass.addProperty("size", getter = {
            ObjInt.of(vectorOf(thisObj).size.toLong())
        })
        vectorClass.addProperty("length", getter = {
            ObjInt.of(vectorOf(thisObj).size.toLong())
        })
        vectorClass.addFn("toList") {
            Obj.from(vectorOf(thisObj).toList())
        }
        vectorClass.addFn("get") {
            ObjReal.of(vectorOf(thisObj).at(requiredArg<ObjInt>(0).value.toInt()))
        }
        vectorClass.addFn("plus") {
            newVector(vectorClass, vectorOf(thisObj).plus(coerceVectorArg(requireScope(), args.firstAndOnly())))
        }
        vectorClass.addFn("minus") {
            newVector(vectorClass, vectorOf(thisObj).minus(coerceVectorArg(requireScope(), args.firstAndOnly())))
        }
        vectorClass.addFn("mul") {
            newVector(vectorClass, vectorOf(thisObj).scale(coerceScalarArg(requireScope(), args.firstAndOnly())))
        }
        vectorClass.addFn("div") {
            newVector(vectorClass, vectorOf(thisObj).divide(coerceScalarArg(requireScope(), args.firstAndOnly())))
        }
        vectorClass.addFn("dot") {
            ObjReal.of(vectorOf(thisObj).dot(coerceVectorArg(requireScope(), args.firstAndOnly())))
        }
        vectorClass.addFn("norm") {
            ObjReal.of(vectorOf(thisObj).norm())
        }
        vectorClass.addFn("normalize") {
            newVector(vectorClass, vectorOf(thisObj).normalize())
        }
        vectorClass.addFn("cross") {
            newVector(vectorClass, vectorOf(thisObj).cross(coerceVectorArg(requireScope(), args.firstAndOnly())))
        }
        vectorClass.addFn("outer") {
            newMatrix(matrixClass, vectorOf(thisObj).outer(coerceVectorArg(requireScope(), args.firstAndOnly())))
        }
        vectorClass.addFn("toString") {
            ObjString(vectorOf(thisObj).render())
        }
        vectorClass.addFn("compareTo") {
            ObjInt.of(vectorOf(thisObj).compareTo(coerceVectorArg(requireScope(), args.firstAndOnly())).toLong())
        }

        vectorClass.addClassFn("fromList") {
            newVector(vectorClass, parseVector(requireScope(), requiredArg(0)))
        }
        vectorClass.addClassFn("zeros") {
            val size = requiredArg<ObjInt>(0).value.toInt()
            if (size <= 0) requireScope().raiseIllegalArgument("vector size must be positive")
            newVector(vectorClass, VectorData(DoubleArray(size)))
        }
    }

    private fun bindMatrixClass(matrixClass: ObjClass, vectorClass: ObjClass) {
        if (matrixClass.kotlinClassData === BoundMarker) return
        matrixClass.kotlinClassData = BoundMarker
        matrixClass.isAbstract = false

        val hooks = matrixClass.bridgeInitHooks ?: mutableListOf<suspend (ScopeFacade, ObjInstance) -> Unit>().also {
            matrixClass.bridgeInitHooks = it
        }
        hooks += { _, instance -> instance.kotlinInstanceData = defaultMatrix }

        matrixClass.addProperty("rows", getter = {
            ObjInt.of(matrixOf(thisObj).rows.toLong())
        })
        matrixClass.addProperty("cols", getter = {
            ObjInt.of(matrixOf(thisObj).cols.toLong())
        })
        matrixClass.addProperty("shape", getter = {
            ObjList(
                mutableListOf(
                    ObjInt.of(matrixOf(thisObj).rows.toLong()),
                    ObjInt.of(matrixOf(thisObj).cols.toLong())
                )
            )
        })
        matrixClass.addProperty("isSquare", getter = {
            matrixOf(thisObj).isSquare.toObj()
        })

        matrixClass.addFn("plus") {
            newMatrix(matrixClass, matrixOf(thisObj).plus(coerceMatrixArg(requireScope(), args.firstAndOnly())))
        }
        matrixClass.addFn("minus") {
            newMatrix(matrixClass, matrixOf(thisObj).minus(coerceMatrixArg(requireScope(), args.firstAndOnly())))
        }
        matrixClass.addFn("mul") {
            when (val other = args.firstAndOnly()) {
                is ObjInstance -> when (other.objClass.className) {
                    "Matrix" -> newMatrix(matrixClass, matrixOf(thisObj).multiply(matrixOf(other)))
                    "Vector" -> newVector(vectorClass, matrixOf(thisObj).multiply(vectorOf(other)))
                    else -> newMatrix(matrixClass, matrixOf(thisObj).scale(coerceScalarArg(requireScope(), other)))
                }
                else -> newMatrix(matrixClass, matrixOf(thisObj).scale(coerceScalarArg(requireScope(), other)))
            }
        }
        matrixClass.addFn("div") {
            newMatrix(matrixClass, matrixOf(thisObj).divide(coerceScalarArg(requireScope(), args.firstAndOnly())))
        }
        matrixClass.addFn("transpose") {
            newMatrix(matrixClass, matrixOf(thisObj).transpose())
        }
        matrixClass.addFn("trace") {
            ObjReal.of(matrixOf(thisObj).trace())
        }
        matrixClass.addFn("rank") {
            ObjInt.of(matrixOf(thisObj).rank().toLong())
        }
        matrixClass.addFn("determinant") {
            ObjReal.of(matrixOf(thisObj).determinant())
        }
        matrixClass.addFn("inverse") {
            newMatrix(matrixClass, matrixOf(thisObj).inverse())
        }
        matrixClass.addFn("solve") {
            when (val rhs = args.firstAndOnly()) {
                is ObjInstance -> when (rhs.objClass.className) {
                    "Vector" -> newVector(vectorClass, matrixOf(thisObj).solve(vectorOf(rhs)))
                    "Matrix" -> newMatrix(matrixClass, matrixOf(thisObj).solve(matrixOf(rhs)))
                    else -> requireScope().raiseClassCastError("Matrix.solve expects Vector or Matrix")
                }
                else -> requireScope().raiseClassCastError("Matrix.solve expects Vector or Matrix")
            }
        }
        matrixClass.addFn("get") {
            ObjReal.of(
                matrixOf(thisObj).at(
                    requiredArg<ObjInt>(0).value.toInt(),
                    requiredArg<ObjInt>(1).value.toInt()
                )
            )
        }
        matrixClass.addFn("getAt") {
            resolveMatrixIndex(matrixClass, matrixOf(thisObj), args.firstAndOnly(), thisObj)
        }
        matrixClass.addFn("row") {
            doubleArrayToObjList(matrixOf(thisObj).rowValues(requiredArg<ObjInt>(0).value.toInt()))
        }
        matrixClass.addFn("column") {
            doubleArrayToObjList(matrixOf(thisObj).columnValues(requiredArg<ObjInt>(0).value.toInt()))
        }
        matrixClass.addFn("toList") {
            Obj.from(matrixOf(thisObj).toNestedLists())
        }
        matrixClass.addFn("toString") {
            ObjString(matrixOf(thisObj).render())
        }
        matrixClass.addFn("compareTo") {
            ObjInt.of(matrixOf(thisObj).compareTo(coerceMatrixArg(requireScope(), args.firstAndOnly())).toLong())
        }

        matrixClass.addClassFn("fromRows") {
            newMatrix(matrixClass, parseRows(requireScope(), requiredArg(0)))
        }
        matrixClass.addClassFn("zeros") {
            val rows = requiredArg<ObjInt>(0).value.toInt()
            val cols = requiredArg<ObjInt>(1).value.toInt()
            if (rows <= 0) requireScope().raiseIllegalArgument("matrix must have at least one row")
            if (cols <= 0) requireScope().raiseIllegalArgument("matrix must have at least one column")
            newMatrix(matrixClass, MatrixData(rows, cols, DoubleArray(rows * cols)))
        }
        matrixClass.addClassFn("identity") {
            val size = requiredArg<ObjInt>(0).value.toInt()
            if (size <= 0) requireScope().raiseIllegalArgument("identity matrix size must be positive")
            val values = DoubleArray(size * size)
            for (index in 0 until size) {
                values[index * size + index] = 1.0
            }
            newMatrix(matrixClass, MatrixData(size, size, values))
        }
    }

    private fun matrixOf(obj: Obj): MatrixData {
        val instance = obj as? ObjInstance ?: error("Matrix receiver must be an object instance")
        return instance.kotlinInstanceData as? MatrixData ?: defaultMatrix
    }

    private fun vectorOf(obj: Obj): VectorData {
        val instance = obj as? ObjInstance ?: error("Vector receiver must be an object instance")
        return instance.kotlinInstanceData as? VectorData ?: defaultVector
    }

    private suspend fun ScopeFacade.newMatrix(matrixClass: ObjClass, value: MatrixData): ObjInstance {
        val instance = call(matrixClass) as? ObjInstance
            ?: raiseIllegalState("Matrix() did not return an object instance")
        instance.kotlinInstanceData = value
        return instance
    }

    private suspend fun ScopeFacade.newVector(vectorClass: ObjClass, value: VectorData): ObjInstance {
        val instance = call(vectorClass) as? ObjInstance
            ?: raiseIllegalState("Vector() did not return an object instance")
        instance.kotlinInstanceData = value
        return instance
    }

    private fun coerceMatrixArg(scope: Scope, value: Obj): MatrixData {
        val instance = value as? ObjInstance
            ?: scope.raiseClassCastError("expected Matrix, got ${value.objClass.className}")
        if (instance.objClass.className != "Matrix") {
            scope.raiseClassCastError("expected Matrix, got ${instance.objClass.className}")
        }
        return instance.kotlinInstanceData as? MatrixData ?: defaultMatrix
    }

    private fun coerceVectorArg(scope: Scope, value: Obj): VectorData {
        val instance = value as? ObjInstance
            ?: scope.raiseClassCastError("expected Vector, got ${value.objClass.className}")
        if (instance.objClass.className != "Vector") {
            scope.raiseClassCastError("expected Vector, got ${instance.objClass.className}")
        }
        return instance.kotlinInstanceData as? VectorData ?: defaultVector
    }

    private fun coerceScalarArg(scope: Scope, value: Obj): Double = try {
        value.toDouble()
    } catch (_: IllegalArgumentException) {
        scope.raiseClassCastError("expected matrix scalar (Int or Real), got ${value.objClass.className}")
    }

    private suspend fun parseRows(scope: Scope, rowsObj: Obj): MatrixData {
        val outer = asObjList(scope, rowsObj, "Matrix.fromRows expects a list of rows")
        if (outer.list.isEmpty()) scope.raiseIllegalArgument("matrix must have at least one row")

        val rows = outer.list.size
        var cols = -1
        val values = mutableListOf<Double>()

        for (rowObj in outer.list) {
            val row = asObjList(scope, rowObj, "Matrix rows must be lists")
            if (cols == -1) {
                cols = row.list.size
                if (cols <= 0) scope.raiseIllegalArgument("matrix must have at least one column")
            } else if (row.list.size != cols) {
                scope.raiseIllegalArgument("matrix rows must all have the same length")
            }
            for (cell in row.list) {
                values += coerceScalarArg(scope, cell)
            }
        }
        return MatrixData(rows, cols, values.toDoubleArray())
    }

    private suspend fun parseVector(scope: Scope, valuesObj: Obj): VectorData {
        val list = asObjList(scope, valuesObj, "Vector.fromList expects a list")
        if (list.list.isEmpty()) scope.raiseIllegalArgument("vector must have at least one element")
        return VectorData(list.list.map { coerceScalarArg(scope, it) }.toDoubleArray())
    }

    private suspend fun ScopeFacade.resolveMatrixIndex(
        matrixClass: ObjClass,
        matrix: MatrixData,
        index: Obj,
        receiver: Obj
    ): Obj {
        val tuple = asObjList(requireScope(), index, "Matrix index must be [row, col]")
        if (tuple.list.size != 2) {
            raiseIllegalArgument("Matrix index must contain exactly two selectors: [row, col]")
        }

        val rowIndex = decodeAxisIndex(requireScope(), tuple.list[0], matrix.rows, "row")
        val colIndex = decodeAxisIndex(requireScope(), tuple.list[1], matrix.cols, "column")

        return when {
            rowIndex is MatrixAxisIndex.Single && colIndex is MatrixAxisIndex.Single ->
                ObjReal.of(matrix.at(rowIndex.value, colIndex.value))

            rowIndex is MatrixAxisIndex.Single && colIndex is MatrixAxisIndex.Slice ->
                newMatrix(matrixClass, matrix.slice(intArrayOf(rowIndex.value), colIndex.values))

            rowIndex is MatrixAxisIndex.Slice && colIndex is MatrixAxisIndex.Single ->
                newMatrix(matrixClass, matrix.slice(rowIndex.values, intArrayOf(colIndex.value)))

            rowIndex is MatrixAxisIndex.Slice && colIndex is MatrixAxisIndex.Slice ->
                newMatrix(matrixClass, matrix.slice(rowIndex.values, colIndex.values))

            else -> requireScope().raiseIllegalState("unreachable matrix index state for ${receiver.objClass.className}")
        }
    }

    private suspend fun decodeAxisIndex(scope: Scope, index: Obj, size: Int, axisName: String): MatrixAxisIndex =
        when (index) {
            is ObjInt -> {
                val value = index.value.toInt()
                if (value !in 0 until size) {
                    scope.raiseIllegalArgument("$axisName index $value out of bounds for length $size")
                }
                MatrixAxisIndex.Single(value)
            }

            is ObjRange -> {
                if (index.hasExplicitStep) {
                    scope.raiseIllegalArgument("Matrix slicing does not support stepped $axisName ranges")
                }
                val start = index.startInt(scope)
                val endExclusive = index.exclusiveIntEnd(scope) ?: size
                if (start !in 0..size) {
                    scope.raiseIllegalArgument("$axisName slice start $start out of bounds for length $size")
                }
                if (endExclusive !in 0..size) {
                    scope.raiseIllegalArgument("$axisName slice end $endExclusive out of bounds for length $size")
                }
                if (start > endExclusive) {
                    scope.raiseIllegalArgument("$axisName slice start $start is after end $endExclusive")
                }
                if (start == endExclusive) {
                    scope.raiseIllegalArgument("Matrix slice must include at least one $axisName")
                }
                MatrixAxisIndex.Slice(IntArray(endExclusive - start) { start + it })
            }

            else -> scope.raiseClassCastError("Matrix $axisName selector must be Int or Range, got ${index.objClass.className}")
        }

    private suspend fun asObjList(scope: Scope, value: Obj, message: String): ObjList = when (value) {
        is ObjList -> value
        else -> if (value.isInstanceOf(ObjIterable)) {
            value.callMethod<ObjList>(scope, "toList")
        } else {
            scope.raiseClassCastError(message)
        }
    }

    private fun doubleArrayToObjList(values: DoubleArray): ObjList =
        ObjList(values.map { ObjReal.of(it) }.toMutableList())
}
