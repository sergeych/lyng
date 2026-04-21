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

import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ionspin.kotlin.bignum.integer.BigInteger
import net.sergeych.lyng.*
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.lyng.requiredArg
import com.ionspin.kotlin.bignum.decimal.BigDecimal as IonBigDecimal

object ObjDecimalSupport {
    private const val decimalContextVar = "__lyng_decimal_context__"
    // For Real -> Decimal, preserve the actual IEEE-754 Double value using a
    // round-trip-safe precision. This intentionally does not try to recover source text.
    private val realConversionMode = DecimalMode(17L, RoundingMode.ROUND_HALF_TO_EVEN)
    // Division needs an explicit stopping rule for non-terminating results. Use a
    // decimal128-like default context until Lyng exposes per-operation contexts.
    private val defaultDivisionMode = DecimalMode(34L, RoundingMode.ROUND_HALF_TO_EVEN)
    private val zero: IonBigDecimal = IonBigDecimal.ZERO
    private val decimalTypeDecl = TypeDecl.Simple("lyng.decimal.Decimal", false)
    private object BoundMarker
    private data class DecimalRuntimeContext(
        val precision: Long,
        val rounding: RoundingMode
    ) : Obj()

    suspend fun bindTo(module: ModuleScope) {
        val decimalClass = module.requireClass("Decimal")
        if (decimalClass.kotlinClassData === BoundMarker) return
        decimalClass.kotlinClassData = BoundMarker
        decimalClass.isAbstract = false
        val hooks = decimalClass.bridgeInitHooks ?: mutableListOf<suspend (ScopeFacade, ObjInstance) -> Unit>().also {
            decimalClass.bridgeInitHooks = it
        }
        hooks += { _, instance ->
            instance.kotlinInstanceData = zero
        }
        decimalClass.addFn("plus") {
            mixedRealDecimalArithmeticFallback(thisObj, args.firstAndOnly(), InteropOperator.Plus)
                ?: ObjComplexSupport.decimalBinary(this, thisObj, args.firstAndOnly(), InteropOperator.Plus)
                ?: OperatorInteropRegistry.invokeBinary(requireScope(), thisObj, args.firstAndOnly(), InteropOperator.Plus)
                ?: newInstance(decimalClass, valueOf(thisObj).plus(coerceArg(requireScope(), args.firstAndOnly())))
        }
        decimalClass.addFn("minus") {
            mixedRealDecimalArithmeticFallback(thisObj, args.firstAndOnly(), InteropOperator.Minus)
                ?: ObjComplexSupport.decimalBinary(this, thisObj, args.firstAndOnly(), InteropOperator.Minus)
                ?: OperatorInteropRegistry.invokeBinary(requireScope(), thisObj, args.firstAndOnly(), InteropOperator.Minus)
                ?: newInstance(decimalClass, valueOf(thisObj).minus(coerceArg(requireScope(), args.firstAndOnly())))
        }
        decimalClass.addFn("mul") {
            mixedRealDecimalArithmeticFallback(thisObj, args.firstAndOnly(), InteropOperator.Mul)
                ?: ObjComplexSupport.decimalBinary(this, thisObj, args.firstAndOnly(), InteropOperator.Mul)
                ?: OperatorInteropRegistry.invokeBinary(requireScope(), thisObj, args.firstAndOnly(), InteropOperator.Mul)
                ?: newInstance(decimalClass, valueOf(thisObj).times(coerceArg(requireScope(), args.firstAndOnly())))
        }
        decimalClass.addFn("div") {
            mixedRealDecimalArithmeticFallback(thisObj, args.firstAndOnly(), InteropOperator.Div)
                ?: ObjComplexSupport.decimalBinary(this, thisObj, args.firstAndOnly(), InteropOperator.Div)
                ?: OperatorInteropRegistry.invokeBinary(requireScope(), thisObj, args.firstAndOnly(), InteropOperator.Div)
                ?: newInstance(decimalClass, divideWithContext(valueOf(thisObj), coerceArg(requireScope(), args.firstAndOnly()), currentDivisionMode(requireScope())))
        }
        decimalClass.addFn("mod") {
            mixedRealDecimalArithmeticFallback(thisObj, args.firstAndOnly(), InteropOperator.Mod)
                ?: newInstance(decimalClass, valueOf(thisObj).rem(coerceArg(requireScope(), args.firstAndOnly())))
        }
        decimalClass.addFn("compareTo") {
            mixedRealDecimalCompareFallback(thisObj, args.firstAndOnly())?.toObj()
                ?: ObjInt.of(valueOf(thisObj).compareTo(coerceArg(requireScope(), args.firstAndOnly())).toLong())
        }
        decimalClass.addFn("negate") {
            newInstance(decimalClass, valueOf(thisObj).unaryMinus())
        }
        decimalClass.addFn("toInt") {
            ObjInt.of(valueOf(thisObj).longValue(false))
        }
        decimalClass.addFn("toReal") {
            ObjReal.of(valueOf(thisObj).doubleValue(false))
        }
        decimalClass.addFn("isInfinite") {
            ObjFalse
        }
        decimalClass.addFn("isNaN") {
            ObjFalse
        }
        decimalClass.addFn("toString") {
            ObjString(valueOf(thisObj).toStringExpanded())
        }
        decimalClass.addFn("toStringExpanded") {
            ObjString(valueOf(thisObj).toStringExpanded())
        }
        decimalClass.bindClassFn("fromInt") {
            val value = requiredArg<ObjInt>(0).value
            newInstance(decimalClass, IonBigDecimal.fromLong(value))
        }
        decimalClass.bindClassFn("fromReal") {
            val value = requiredArg<ObjReal>(0).value
            newInstanceFromFiniteReal(decimalClass, value)
        }
        decimalClass.bindClassFn("fromString") {
            val value = requiredArg<ObjString>(0).value
            try {
                newInstance(decimalClass, IonBigDecimal.parseStringWithMode(value))
            } catch (e: Throwable) {
                requireScope().raiseIllegalArgument("invalid Decimal string: $value")
            }
        }
        module.addFn("withDecimalContext") {
            val (context, block) = when (args.list.size) {
                2 -> {
                    val first = args[0]
                    val block = args[1]
                    if (first is ObjInt) {
                        DecimalRuntimeContext(first.value, RoundingMode.ROUND_HALF_TO_EVEN) to block
                    } else {
                        normalizeContext(requireScope(), first) to block
                    }
                }
                3 -> {
                    val precision = requiredArg<ObjInt>(0).value
                    val rounding = roundingModeFromObj(requireScope(), args[1])
                    DecimalRuntimeContext(precision, rounding) to args[2]
                }
                else -> requireScope().raiseIllegalArgument("withDecimalContext expects (context, block), (precision, block), or (precision, rounding, block)")
            }
            val child = requireScope().createChildScope()
            child.addConst(decimalContextVar, context)
            (block as? net.sergeych.lyng.BytecodeCallable)?.callOnFast(child) ?: block.callOn(child)
        }
        registerBuiltinConversions(decimalClass)
        registerInterop(decimalClass)
    }

    fun isDecimalValue(value: Obj): Boolean =
        value is ObjInstance && value.objClass.className == "Decimal"

    suspend fun exactAbs(scope: ScopeFacade, value: Obj): Obj? =
        decimalValueOrNull(value)?.let { scope.newInstanceLikeDecimal(value, it.abs()) }

    suspend fun exactFloor(scope: ScopeFacade, value: Obj): Obj? =
        decimalValueOrNull(value)?.let { scope.newInstanceLikeDecimal(value, it.floor()) }

    suspend fun exactCeil(scope: ScopeFacade, value: Obj): Obj? =
        decimalValueOrNull(value)?.let { scope.newInstanceLikeDecimal(value, it.ceil()) }

    suspend fun exactRound(scope: ScopeFacade, value: Obj): Obj? =
        decimalValueOrNull(value)?.let {
            scope.newInstanceLikeDecimal(value, it.roundToDigitPositionAfterDecimalPoint(0, RoundingMode.ROUND_HALF_CEILING))
        }

    suspend fun exactPow(scope: ScopeFacade, base: Obj, exponent: Obj): Obj? {
        val decimal = decimalValueOrNull(base) ?: return null
        val intExponent = exponent as? ObjInt ?: return null
        return scope.newInstanceLikeDecimal(base, decimal.pow(intExponent.value))
    }

    suspend fun fromRealLike(scope: ScopeFacade, sample: Obj, value: Double): Obj? {
        if (!isDecimalValue(sample)) return null
        if (!value.isFinite()) return ObjReal.of(value)
        return scope.newInstanceLikeDecimal(sample, IonBigDecimal.fromDouble(value, realConversionMode))
    }

    fun toDoubleOrNull(value: Obj): Double? =
        decimalValueOrNull(value)?.doubleValue(false)

    internal fun mixedRealDecimalArithmeticFallback(left: Obj, right: Obj, operator: InteropOperator): Obj? {
        if (!isMixedRealDecimal(left, right)) return null
        val leftDouble = numericDoubleOrNull(left) ?: return null
        val rightDouble = numericDoubleOrNull(right) ?: return null
        val result = when (operator) {
            InteropOperator.Plus -> leftDouble + rightDouble
            InteropOperator.Minus -> leftDouble - rightDouble
            InteropOperator.Mul -> leftDouble * rightDouble
            InteropOperator.Div -> leftDouble / rightDouble
            InteropOperator.Mod -> leftDouble % rightDouble
            else -> return null
        }
        return if (result.isFinite()) null else ObjReal.of(result)
    }

    internal fun mixedRealDecimalCompareFallback(left: Obj, right: Obj): Int? {
        if (!isMixedRealDecimal(left, right)) return null
        val leftDouble = numericDoubleOrNull(left) ?: return null
        val rightDouble = numericDoubleOrNull(right) ?: return null
        return if (leftDouble.isFinite() && rightDouble.isFinite()) null else leftDouble.compareTo(rightDouble)
    }

    suspend fun newDecimal(scope: ScopeFacade, value: IonBigDecimal): ObjInstance {
        val decimalModule = scope.requireScope().currentImportProvider.createModuleScope(scope.pos, "lyng.decimal")
        val decimalClass = decimalModule.requireClass("Decimal")
        return scope.newInstance(decimalClass, value)
    }

    private fun valueOf(obj: Obj): IonBigDecimal {
        val instance = obj as? ObjInstance ?: error("Decimal receiver must be an object instance")
        return instance.kotlinInstanceData as? IonBigDecimal ?: zero
    }

    private suspend fun currentDivisionMode(scope: Scope): DecimalMode {
        val context = findContextObject(scope) ?: return defaultDivisionMode
        return DecimalMode(context.precision, context.rounding)
    }

    private fun divideWithContext(left: IonBigDecimal, right: IonBigDecimal, mode: DecimalMode): IonBigDecimal {
        if (mode.decimalPrecision <= 0L) {
            return stripMode(left.divide(right, mode))
        }
        val exactLeft = stripMode(left)
        val exactRight = stripMode(right)
        val guardMode = DecimalMode(mode.decimalPrecision + 2, RoundingMode.TOWARDS_ZERO)
        var guarded = stripMode(exactLeft.divide(exactRight, guardMode))
        val hasMoreTail = !stripMode(exactLeft - stripMode(guarded * exactRight)).isZero()
        if (hasMoreTail && isHalfRounding(mode.roundingMode) && looksLikeExactHalf(guarded, mode.decimalPrecision)) {
            guarded = nudgeLastDigitAwayFromZero(guarded)
        }
        return stripMode(guarded.roundSignificand(mode))
    }

    private suspend fun ScopeFacade.newInstance(decimalClass: ObjClass, value: IonBigDecimal): ObjInstance {
        val instance = call(decimalClass) as? ObjInstance
            ?: raiseIllegalState("Decimal() did not return an object instance")
        instance.kotlinInstanceData = value
        return instance
    }

    private suspend fun ScopeFacade.newInstanceFromFiniteReal(decimalClass: ObjClass, value: Double): ObjInstance {
        if (!value.isFinite()) {
            requireScope().raiseIllegalArgument("cannot convert non-finite Real to Decimal: $value")
        }
        return newInstance(decimalClass, IonBigDecimal.fromDouble(value, realConversionMode))
    }

    private suspend fun ScopeFacade.newInstanceLikeDecimal(sample: Obj, value: IonBigDecimal): ObjInstance {
        val decimalClass = (sample as? ObjInstance)?.objClass
            ?: raiseIllegalState("Decimal sample must be an object instance")
        return newInstance(decimalClass, value)
    }

    private fun coerceArg(scope: Scope, value: Obj): IonBigDecimal = when (value) {
        is ObjInt -> IonBigDecimal.fromLong(value.value)
        is ObjReal -> {
            if (!value.value.isFinite()) {
                scope.raiseIllegalArgument("cannot convert non-finite Real to Decimal: ${value.value}")
            }
            IonBigDecimal.fromDouble(value.value, realConversionMode)
        }
        is ObjInstance -> {
            if (value.objClass.className != "Decimal") {
                scope.raiseIllegalArgument("expected Decimal-compatible value, got ${value.objClass.className}")
            }
            value.kotlinInstanceData as? IonBigDecimal ?: zero
        }
        else -> scope.raiseIllegalArgument("expected Decimal-compatible value, got ${value.objClass.className}")
    }

    private suspend fun normalizeContext(scope: Scope, value: Obj): DecimalRuntimeContext {
        val instance = value as? ObjInstance
            ?: scope.raiseClassCastError("withDecimalContext expects DecimalContext as the first argument")
        if (instance.objClass.className != "DecimalContext") {
            scope.raiseClassCastError("withDecimalContext expects DecimalContext as the first argument")
        }
        return decimalRuntimeContextFromInstance(scope, instance)
    }

    private fun findContextObject(scope: Scope): DecimalRuntimeContext? {
        var current: Scope? = scope
        while (current != null) {
            val record = current.objects[decimalContextVar] ?: current.localBindings[decimalContextVar]
            val value = when (val raw = record?.value) {
                is FrameSlotRef -> raw.peekValue()
                is RecordSlotRef -> raw.peekValue()
                else -> raw
            }
            when (value) {
                is DecimalRuntimeContext -> return value
            }
            current = current.parent
        }
        return null
    }

    private suspend fun decimalRuntimeContextFromInstance(scope: Scope, context: ObjInstance): DecimalRuntimeContext {
        val precision = context.readField(scope, "precision").value as? ObjInt
            ?: scope.raiseClassCastError("DecimalContext.precision must be Int")
        if (precision.value <= 0L) {
            scope.raiseIllegalArgument("DecimalContext precision must be positive")
        }
        val rounding = roundingModeFromObj(scope, context.readField(scope, "rounding").value)
        return DecimalRuntimeContext(precision.value, rounding)
    }

    private fun stripMode(value: IonBigDecimal): IonBigDecimal =
        IonBigDecimal.fromBigIntegerWithExponent(value.significand, value.exponent)

    private fun isHalfRounding(mode: RoundingMode): Boolean = when (mode) {
        RoundingMode.ROUND_HALF_TO_EVEN,
        RoundingMode.ROUND_HALF_AWAY_FROM_ZERO,
        RoundingMode.ROUND_HALF_TOWARDS_ZERO,
        RoundingMode.ROUND_HALF_CEILING,
        RoundingMode.ROUND_HALF_FLOOR,
        RoundingMode.ROUND_HALF_TO_ODD -> true
        else -> false
    }

    private fun looksLikeExactHalf(value: IonBigDecimal, targetPrecision: Long): Boolean {
        val digits = value.significand.abs().toString(10)
        if (digits.length <= targetPrecision) return false
        val discarded = digits.substring(targetPrecision.toInt())
        return discarded[0] == '5' && discarded.drop(1).all { it == '0' }
    }

    private fun nudgeLastDigitAwayFromZero(value: IonBigDecimal): IonBigDecimal {
        val ulpExponent = value.exponent - value.precision + 1
        val ulp = IonBigDecimal.fromBigIntegerWithExponent(BigInteger.ONE, ulpExponent)
        return if (value.significand.signum() < 0) value - ulp else value + ulp
    }

    private fun roundingModeFromObj(scope: Scope, value: Obj): RoundingMode {
        val entry = value as? ObjEnumEntry ?: scope.raiseClassCastError("DecimalContext.rounding must be DecimalRounding")
        return when (entry.name.value) {
            "HalfEven" -> RoundingMode.ROUND_HALF_TO_EVEN
            "HalfAwayFromZero" -> RoundingMode.ROUND_HALF_AWAY_FROM_ZERO
            "HalfTowardsZero" -> RoundingMode.ROUND_HALF_TOWARDS_ZERO
            "Ceiling" -> RoundingMode.CEILING
            "Floor" -> RoundingMode.FLOOR
            "AwayFromZero" -> RoundingMode.AWAY_FROM_ZERO
            "TowardsZero" -> RoundingMode.TOWARDS_ZERO
            else -> scope.raiseIllegalArgument("unsupported DecimalRounding: ${entry.name.value}")
        }
    }

    private fun decimalValueOrNull(value: Obj): IonBigDecimal? {
        if (!isDecimalValue(value)) return null
        val instance = value as ObjInstance
        return instance.kotlinInstanceData as? IonBigDecimal ?: zero
    }

    private fun registerBuiltinConversions(decimalClass: ObjClass) {
        ObjInt.type.addPropertyDoc(
            name = "d",
            doc = "Convert this integer to a Decimal.",
            type = type("lyng.decimal.Decimal"),
            moduleName = "lyng.decimal",
            getter = { newInstance(decimalClass, IonBigDecimal.fromLong(thisAs<ObjInt>().value)) }
        )
        ObjInt.type.members["d"] = ObjInt.type.members.getValue("d").copy(typeDecl = decimalTypeDecl)
        ObjReal.type.addPropertyDoc(
            name = "d",
            doc = "Convert this real number to a Decimal by preserving the current IEEE-754 value with 17 significant digits and half-even rounding.",
            type = type("lyng.decimal.Decimal"),
            moduleName = "lyng.decimal",
            getter = { newInstanceFromFiniteReal(decimalClass, thisAs<ObjReal>().value) }
        )
        ObjReal.type.members["d"] = ObjReal.type.members.getValue("d").copy(typeDecl = decimalTypeDecl)
        ObjString.type.addPropertyDoc(
            name = "d",
            doc = "Parse this string as a Decimal.",
            type = type("lyng.decimal.Decimal"),
            moduleName = "lyng.decimal",
            getter = {
                val value = thisAs<ObjString>().value
                try {
                    newInstance(decimalClass, IonBigDecimal.parseStringWithMode(value))
                } catch (e: Throwable) {
                    requireScope().raiseIllegalArgument("invalid Decimal string: $value")
                }
            }
        )
        ObjString.type.members["d"] = ObjString.type.members.getValue("d").copy(typeDecl = decimalTypeDecl)
    }

    private fun registerInterop(decimalClass: ObjClass) {
        val decimalIdentity = ObjExternCallable.fromBridge {
            requiredArg<Obj>(0)
        }
        val numericOperators = listOf(
            InteropOperator.Plus.name,
            InteropOperator.Minus.name,
            InteropOperator.Mul.name,
            InteropOperator.Div.name,
            InteropOperator.Mod.name,
            InteropOperator.Compare.name,
            InteropOperator.Equals.name
        )
        OperatorInteropRegistry.register(
            leftClass = ObjInt.type,
            rightClass = decimalClass,
            commonClass = decimalClass,
            operatorNames = numericOperators,
            leftToCommon = ObjExternCallable.fromBridge {
                val value = requiredArg<ObjInt>(0).value
                newInstance(decimalClass, IonBigDecimal.fromLong(value))
            },
            rightToCommon = decimalIdentity
        )
        OperatorInteropRegistry.register(
            leftClass = ObjReal.type,
            rightClass = decimalClass,
            commonClass = decimalClass,
            operatorNames = numericOperators,
            leftToCommon = ObjExternCallable.fromBridge {
                val value = requiredArg<ObjReal>(0).value
                newInstanceFromFiniteReal(decimalClass, value)
            },
            rightToCommon = decimalIdentity
        )
    }

    private fun isMixedRealDecimal(left: Obj, right: Obj): Boolean =
        (left is ObjReal && isDecimalValue(right)) || (right is ObjReal && isDecimalValue(left))

    private fun numericDoubleOrNull(value: Obj): Double? = when (value) {
        is Numeric -> value.doubleValue
        is ObjInstance -> toDoubleOrNull(value)
        else -> null
    }
}
