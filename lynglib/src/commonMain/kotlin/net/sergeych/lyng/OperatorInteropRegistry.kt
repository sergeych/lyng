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

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjVoid

internal enum class InteropOperator(val memberName: String?) {
    Plus("plus"),
    Minus("minus"),
    Mul("mul"),
    Div("div"),
    Mod("mod"),
    Compare("compareTo"),
    Equals("equals");

    companion object {
        fun fromName(name: String): InteropOperator =
            entries.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("unknown interop operator: $name")
    }
}

private data class InteropRule(
    val commonClass: ObjClass,
    val operators: Set<InteropOperator>,
    val leftToCommon: Obj,
    val rightToCommon: Obj
)

internal data class PromotedOperands(val left: Obj, val right: Obj)

internal object OperatorInteropRegistry {
    private val rules = mutableMapOf<Pair<ObjClass, ObjClass>, InteropRule>()

    fun register(
        leftClass: ObjClass,
        rightClass: ObjClass,
        commonClass: ObjClass,
        operatorNames: List<String>,
        leftToCommon: Obj,
        rightToCommon: Obj
    ) {
        val operators = operatorNames.mapTo(linkedSetOf(), InteropOperator::fromName)
        rules[leftClass to rightClass] = InteropRule(commonClass, operators, leftToCommon, rightToCommon)
        if (leftClass !== rightClass) {
            rules[rightClass to leftClass] = InteropRule(commonClass, operators, rightToCommon, leftToCommon)
        }
    }

    suspend fun promote(scope: Scope, left: Obj, right: Obj, operator: InteropOperator): PromotedOperands? {
        val leftValue = unwrap(left)
        val rightValue = unwrap(right)
        val rule = rules[leftValue.objClass to rightValue.objClass] ?: return null
        if (operator !in rule.operators) return null
        val promotedLeft = rule.leftToCommon.invoke(scope, ObjVoid, Arguments(leftValue))
        val promotedRight = rule.rightToCommon.invoke(scope, ObjVoid, Arguments(rightValue))
        if (promotedLeft.objClass !== rule.commonClass || promotedRight.objClass !== rule.commonClass) {
            scope.raiseIllegalState(
                "Operator interop promotion must return ${rule.commonClass.className}, " +
                    "got ${promotedLeft.objClass.className} and ${promotedRight.objClass.className}"
            )
        }
        return PromotedOperands(promotedLeft, promotedRight)
    }

    suspend fun invokeBinary(scope: Scope, left: Obj, right: Obj, operator: InteropOperator): Obj? {
        val promoted = promote(scope, left, right, operator) ?: return null
        val memberName = operator.memberName ?: return null
        return promoted.left.invokeInstanceMethod(scope, memberName, Arguments(promoted.right))
    }

    suspend fun invokeCompare(scope: Scope, left: Obj, right: Obj): Int? {
        val promoted = promote(scope, left, right, InteropOperator.Compare) ?: return null
        return promoted.left.invokeInstanceMethod(scope, "compareTo", Arguments(promoted.right))
            .cast<ObjInt>(scope)
            .value
            .toInt()
    }

    fun commonClassFor(leftClass: ObjClass, rightClass: ObjClass, operator: InteropOperator): ObjClass? {
        val rule = rules[leftClass to rightClass] ?: return null
        if (operator !in rule.operators) return null
        return rule.commonClass
    }

    private suspend fun unwrap(obj: Obj): Obj = when (obj) {
        is FrameSlotRef -> obj.read()
        is RecordSlotRef -> obj.read()
        else -> obj
    }
}
