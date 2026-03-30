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
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lyng.requiredArg

object ObjComplexSupport {
    private object BoundMarker
    private val complexTypeDecl = TypeDecl.Simple("lyng.complex.Complex", false)

    suspend fun bindTo(module: ModuleScope) {
        val complexClass = module.requireClass("Complex")
        if (complexClass.kotlinClassData === BoundMarker) return
        complexClass.kotlinClassData = BoundMarker

        val decimalModule = module.currentImportProvider.createModuleScope(module.pos, "lyng.decimal")
        val decimalClass = decimalModule.requireClass("Decimal")

        decimalClass.addPropertyDoc(
            name = "re",
            doc = "Convert this Decimal to a Complex with zero imaginary part.",
            type = type("lyng.complex.Complex"),
            moduleName = "lyng.complex",
            getter = {
                newComplex(
                    complexClass,
                    decimalToReal(thisObj),
                    0.0
                )
            }
        )
        decimalClass.members["re"] = decimalClass.members.getValue("re").copy(typeDecl = complexTypeDecl)

        decimalClass.addPropertyDoc(
            name = "i",
            doc = "Convert this Decimal to a pure imaginary Complex after rounding to Real.",
            type = type("lyng.complex.Complex"),
            moduleName = "lyng.complex",
            getter = {
                newComplex(
                    complexClass,
                    0.0,
                    decimalToReal(thisObj)
                )
            }
        )
        decimalClass.members["i"] = decimalClass.members.getValue("i").copy(typeDecl = complexTypeDecl)

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

    private suspend fun ScopeFacade.newComplex(complexClass: ObjClass, real: Double, imag: Double): ObjInstance =
        call(
            complexClass,
            Arguments(ObjReal.of(real), ObjReal.of(imag))
        ) as? ObjInstance ?: raiseIllegalState("Complex() did not return an object instance")

    private fun ScopeFacade.decimalToReal(value: Obj): Double =
        ObjDecimalSupport.toDoubleOrNull(value)
            ?: raiseClassCastError("expected Decimal-compatible value, got ${value.objClass.className}")
}
