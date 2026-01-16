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

import net.sergeych.lyng.miniast.MiniTypeRef
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjRecord

/**
 * List of argument declarations in the __definition__ of the lambda, class constructor,
 * function, etc. It is created by [Compiler.parseArgsDeclaration]
 */
data class ArgsDeclaration(val params: List<Item>, val endTokenType: Token.Type) {
    init {
        val i = params.count { it.isEllipsis }
        if (i > 1) throw ScriptError(params[i].pos, "there can be only one argument")
        val start = params.indexOfFirst { it.defaultValue != null }
        if (start >= 0)
            for (j in start + 1 until params.size)
                // last non-default could be lambda:
                if (params[j].defaultValue == null && j != params.size - 1) throw ScriptError(
                    params[j].pos,
                    "required argument can't follow default one"
                )
    }

    /**
     * parse args and create local vars in a given context properly interpreting
     * ellipsis args and default values
     */
    suspend fun assignToContext(
        scope: Scope,
        arguments: Arguments = scope.args,
        defaultAccessType: AccessType = AccessType.Var,
        defaultVisibility: Visibility = Visibility.Public,
        declaringClass: net.sergeych.lyng.obj.ObjClass? = scope.currentClassCtx
    ) {
        // Fast path for simple positional-only calls with no ellipsis and no defaults
        if (arguments.named.isEmpty() && !arguments.tailBlockMode) {
            var hasComplex = false
            for (p in params) {
                if (p.isEllipsis || p.defaultValue != null) {
                    hasComplex = true
                    break
                }
            }
            if (!hasComplex) {
                if (arguments.list.size != params.size)
                    scope.raiseIllegalArgument("expected ${params.size} arguments, got ${arguments.list.size}")
                
                for (i in params.indices) {
                    val a = params[i]
                    val value = arguments.list[i]
                    scope.addItem(a.name, (a.accessType ?: defaultAccessType).isMutable,
                        value.byValueCopy(),
                        a.visibility ?: defaultVisibility,
                        recordType = ObjRecord.Type.Argument,
                        declaringClass = declaringClass,
                        isTransient = a.isTransient)
                }
                return
            }
        }

        fun assign(a: Item, value: Obj) {
            scope.addItem(a.name, (a.accessType ?: defaultAccessType).isMutable,
                value.byValueCopy(),
                a.visibility ?: defaultVisibility,
                recordType = ObjRecord.Type.Argument,
                declaringClass = declaringClass,
                isTransient = a.isTransient)
        }

        // Prepare positional args and parameter count, handle tail-block binding
        val callArgs: List<Obj>
        val paramsSize: Int
        if (arguments.tailBlockMode) {
            // If last parameter is already assigned by a named argument, it's an error
            val lastParam = params.last()
            if (arguments.named.containsKey(lastParam.name))
                scope.raiseIllegalArgument("trailing block cannot be used when the last parameter is already assigned by a named argument")
            paramsSize = params.size - 1
            assign(lastParam, arguments.list.last())
            callArgs = arguments.list.dropLast(1)
        } else {
            paramsSize = params.size
            callArgs = arguments.list
        }

        // Compute which parameter indexes are inevitably covered by positional arguments
        // based on the number of supplied positionals, defaults and ellipsis placement.
        val coveredByPositional = BooleanArray(paramsSize)
        run {
            // Count required (non-default, non-ellipsis) params in head and in tail
            var headRequired = 0
            var tailRequired = 0
            val ellipsisIdx = params.subList(0, paramsSize).indexOfFirst { it.isEllipsis }
            if (ellipsisIdx >= 0) {
                for (i in 0 until ellipsisIdx) if (!params[i].isEllipsis && params[i].defaultValue == null) headRequired++
                for (i in paramsSize - 1 downTo ellipsisIdx + 1) if (params[i].defaultValue == null) tailRequired++
            } else {
                for (i in 0 until paramsSize) if (params[i].defaultValue == null) headRequired++
            }
            val P = callArgs.size
            if (ellipsisIdx < 0) {
                // No ellipsis: all positionals go to head until exhausted
                val k = minOf(P, paramsSize)
                for (i in 0 until k) coveredByPositional[i] = true
            } else {
                // With ellipsis: head takes min(P, headRequired) first
                val headTake = minOf(P, headRequired)
                for (i in 0 until headTake) coveredByPositional[i] = true
                val remaining = P - headTake
                // tail takes min(remaining, tailRequired) from the end
                val tailTake = minOf(remaining, tailRequired)
                var j = paramsSize - 1
                var taken = 0
                while (j > ellipsisIdx && taken < tailTake) {
                    coveredByPositional[j] = true
                    j--
                    taken++
                }
            }
        }

        // Prepare arrays for named assignments
        val assignedByName = BooleanArray(paramsSize)
        val namedValues = arrayOfNulls<Obj>(paramsSize)
        if (arguments.named.isNotEmpty()) {
            for ((k, v) in arguments.named) {
                val idx = params.subList(0, paramsSize).indexOfFirst { it.name == k }
                if (idx < 0) scope.raiseIllegalArgument("unknown parameter '$k'")
                if (params[idx].isEllipsis) scope.raiseIllegalArgument("ellipsis (variadic) parameter cannot be assigned by name: '$k'")
                if (coveredByPositional[idx]) scope.raiseIllegalArgument("argument '$k' is already set by positional argument")
                if (assignedByName[idx]) scope.raiseIllegalArgument("argument '$k' is already set")
                assignedByName[idx] = true
                namedValues[idx] = v
            }
        }

        // Helper: assign head part, consuming from headPos; stop at ellipsis
        suspend fun processHead(index: Int, headPos: Int): Pair<Int, Int> {
            var i = index
            var hp = headPos
            while (i < paramsSize) {
                val a = params[i]
                if (a.isEllipsis) break
                if (assignedByName[i]) {
                    assign(a, namedValues[i]!!)
                } else {
                    val value = if (hp < callArgs.size) callArgs[hp++]
                    else a.defaultValue?.execute(scope)
                        ?: scope.raiseIllegalArgument("too few arguments for the call (missing ${a.name})")
                    assign(a, value)
                }
                i++
            }
            return i to hp
        }

        // Helper: assign tail part from the end, consuming from tailPos; stop before ellipsis index
        // Do not consume elements below headPosBound to avoid overlap with head consumption
        suspend fun processTail(startExclusive: Int, tailStart: Int, headPosBound: Int): Int {
            var i = paramsSize - 1
            var tp = tailStart
            while (i > startExclusive) {
                val a = params[i]
                if (a.isEllipsis) break
                if (i < assignedByName.size && assignedByName[i]) {
                    assign(a, namedValues[i]!!)
                } else {
                    val value = if (tp >= headPosBound) callArgs[tp--]
                    else a.defaultValue?.execute(scope)
                        ?: scope.raiseIllegalArgument("too few arguments for the call")
                    assign(a, value)
                }
                i--
            }
            return tp
        }

        fun processEllipsis(index: Int, headPos: Int, tailPos: Int) {
            val a = params[index]
            val from = headPos
            val to = tailPos
            val l = if (from > to) ObjList()
            else ObjList(callArgs.subList(from, to + 1).toMutableList())
            assign(a, l)
        }

        // Locate ellipsis index within considered parameters
        val ellipsisIndex = params.subList(0, paramsSize).indexOfFirst { it.isEllipsis }

        if (ellipsisIndex >= 0) {
            // Assign head first to know how many positionals are consumed from the start
            val (afterHead, headConsumedTo) = processHead(0, 0)
            // Then assign tail consuming from the end down to headConsumedTo boundary
            val tailConsumedFrom = processTail(ellipsisIndex, callArgs.size - 1, headConsumedTo)
            // Assign ellipsis list from remaining positionals between headConsumedTo..tailConsumedFrom
            processEllipsis(ellipsisIndex, headConsumedTo, tailConsumedFrom)
        } else {
            // No ellipsis: assign head only; any leftover positionals → error
            val (_, headConsumedTo) = processHead(0, 0)
            if (headConsumedTo != callArgs.size)
                scope.raiseIllegalArgument("too many arguments for the call")
        }
    }

    /**
     * Single argument declaration descriptor.
     *
     * @param defaultValue default value, if set, can't be an [Obj] as it can depend on the call site, call args, etc.
     *      If not null, could be executed on __caller context__ only.
     */
    data class Item(
        val name: String,
        val type: TypeDecl = TypeDecl.TypeAny,
        val miniType: MiniTypeRef? = null,
        val pos: Pos = Pos.builtIn,
        val isEllipsis: Boolean = false,
        /**
         * Default value, if set, can't be an [Obj] as it can depend on the call site, call args, etc.
         * So it is a [Statement] that must be executed on __caller context__.
         */
        val defaultValue: Statement? = null,
        val accessType: AccessType? = null,
        val visibility: Visibility? = null,
        val isTransient: Boolean = false,
    )
}