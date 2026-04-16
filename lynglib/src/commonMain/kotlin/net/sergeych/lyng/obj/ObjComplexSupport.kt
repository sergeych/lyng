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

import net.sergeych.lyng.*
import net.sergeych.lyng.requiredArg

object ObjComplexSupport {
    private object BoundMarker

    suspend fun bindTo(module: ModuleScope) {
        val complexClass = module.requireClass("Complex")
        if (complexClass.kotlinClassData === BoundMarker) return
        complexClass.kotlinClassData = BoundMarker

        val decimalModule = module.currentImportProvider.createModuleScope(module.pos, "lyng.decimal")
        val decimalClass = decimalModule.requireClass("Decimal")
        decimalClass.bindProperty("re", getter = {
            newComplex(
                complexClass,
                decimalToReal(thisObj),
                0.0
            )
        })
        decimalClass.bindProperty("i", getter = {
            newComplex(
                complexClass,
                0.0,
                decimalToReal(thisObj)
            )
        })

        OperatorInteropRegistry.register(
            leftClass = decimalClass,
            rightClass = complexClass,
            commonClass = complexClass,
            operatorNames = listOf(
                InteropOperator.Plus.name,
                InteropOperator.Minus.name,
                InteropOperator.Mul.name,
                InteropOperator.Div.name
            ),
            leftToCommon = ObjExternCallable.fromBridge {
                newComplex(
                    complexClass,
                    decimalToReal(requiredArg(0)),
                    0.0
                )
            },
            rightToCommon = ObjExternCallable.fromBridge {
                requiredArg<Obj>(0)
            }
        )
    }

    internal suspend fun decimalBinary(scope: ScopeFacade, decimal: Obj, other: Obj, operator: InteropOperator): Obj? {
        val left = ObjDecimalSupport.toDoubleOrNull(decimal) ?: return null
        val complex = other as? ObjInstance ?: return null
        if (complex.objClass.className != "Complex") return null
        val otherReal = complex.readField(scope.requireScope(), "real").value.toDouble()
        val otherImag = complex.readField(scope.requireScope(), "imag").value.toDouble()
        return when (operator) {
            InteropOperator.Plus -> instantiateComplex(scope, complex.objClass, left + otherReal, otherImag)
            InteropOperator.Minus -> instantiateComplex(scope, complex.objClass, left - otherReal, -otherImag)
            InteropOperator.Mul -> instantiateComplex(scope, complex.objClass, left * otherReal, left * otherImag)
            InteropOperator.Div -> {
                val denominator = otherReal * otherReal + otherImag * otherImag
                instantiateComplex(scope, complex.objClass, left * otherReal / denominator, -left * otherImag / denominator)
            }
            else -> null
        }
    }

    private suspend fun ScopeFacade.newComplex(complexClass: ObjClass, real: Double, imag: Double): ObjInstance =
        instantiateComplex(this, complexClass, real, imag)

    private suspend fun instantiateComplex(scope: ScopeFacade, complexClass: ObjClass, real: Double, imag: Double): ObjInstance {
        val runtimeScope = scope.requireScope()
        val instance = complexClass.createInstance(runtimeScope)
        complexClass.initializeInstance(
            instance,
            Arguments(ObjReal.of(real), ObjReal.of(imag)),
            runConstructors = false
        )
        return instance
    }

    private fun ScopeFacade.decimalToReal(value: Obj): Double =
        ObjDecimalSupport.toDoubleOrNull(value)
            ?: raiseClassCastError("expected Decimal-compatible value, got ${value.objClass.className}")
}
