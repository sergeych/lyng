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

import net.sergeych.lyng.bytecode.BytecodeStatement
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjIterable
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjMap
import net.sergeych.lyng.obj.ObjString

/**
 * Preserved declaration annotation evaluated at declaration-creation time.
 */
data class DeclAnnotation(
    val name: String,
    val positional: List<Obj> = emptyList(),
    val named: Map<String, Obj> = emptyMap(),
)

/**
 * Parsed declaration annotation awaiting declaration-time evaluation.
 */
data class ParsedDeclAnnotation(
    val name: String,
    val args: List<ParsedArgument> = emptyList(),
    val tailBlockMode: Boolean = false,
    val pos: Pos = Pos.builtIn,
) {
    suspend fun evaluate(scope: Scope): DeclAnnotation {
        val resolved = evaluateDeclAnnotationArguments(scope, args, tailBlockMode)
        return DeclAnnotation(name, resolved.list, resolved.named)
    }

    fun toStatementAnnotation(): suspend (Scope, ObjString, Statement) -> Statement = { scope, declName, body ->
        val extras = args.toArguments(scope, tailBlockMode).list
        val required = listOf(declName, body)
        val callArgs = if (extras.isEmpty()) required else required + extras
        val fn = scope.get(name)?.value ?: scope.raiseSymbolNotFound("annotation not found: $name")
        if (fn !is Statement) scope.raiseIllegalArgument("annotation must be callable, got ${fn.objClass}")
        (fn.execute(scope.createChildScope(Arguments(callArgs))) as? Statement)
            ?: scope.raiseClassCastError("function annotation must return callable")
    }
}

suspend fun Iterable<ParsedDeclAnnotation>.evaluateDeclAnnotations(scope: Scope): List<DeclAnnotation> {
    val result = mutableListOf<DeclAnnotation>()
    for (spec in this) {
        result += spec.evaluate(scope)
    }
    return result
}

private suspend fun evaluateDeclAnnotationArguments(
    scope: Scope,
    args: List<ParsedArgument>,
    tailBlockMode: Boolean,
): Arguments {
    suspend fun eval(value: Obj): Obj = when (value) {
        is BytecodeBodyProvider -> (value.bytecodeBody() ?: scope.raiseIllegalState("annotation argument requires bytecode body")).execute(scope)
        is Statement -> BytecodeStatement.wrap(value, "@annotation", allowLocalSlots = true).execute(scope)
        else -> value.callOn(scope)
    }

    val resolved = ArrayList<ParsedArgument>(args.size)
    for (arg in args) {
        resolved += arg.copy(value = eval(arg.value))
    }

    val positional: MutableList<Obj> = mutableListOf()
    var named: MutableMap<String, Obj>? = null
    var namedSeen = false
    for ((idx, x) in resolved.withIndex()) {
        if (x.name != null) {
            if (named == null) named = linkedMapOf()
            if (named.containsKey(x.name)) scope.raiseIllegalArgument("argument '${x.name}' is already set")
            named[x.name] = x.value
            namedSeen = true
            continue
        }
        val value = x.value
        if (x.isSplat) {
            when {
                value is ObjMap -> {
                    if (named == null) named = linkedMapOf()
                    for ((k, v) in value.map) {
                        if (k !is ObjString) scope.raiseIllegalArgument("named splat expects a Map with string keys")
                        val key = k.value
                        if (named.containsKey(key)) scope.raiseIllegalArgument("argument '$key' is already set")
                        named[key] = v
                    }
                    namedSeen = true
                }
                value is ObjList -> {
                    if (namedSeen) scope.raiseIllegalArgument("positional splat cannot follow named arguments")
                    positional.addAll(value.list)
                }
                value.isInstanceOf(ObjIterable) -> {
                    if (namedSeen) scope.raiseIllegalArgument("positional splat cannot follow named arguments")
                    val iterable = value.invokeInstanceMethod(scope, "toList") as ObjList
                    positional.addAll(iterable.list)
                }
                else -> scope.raiseClassCastError("expected list of objects for splat argument")
            }
        } else {
            val isLast = idx == resolved.size - 1
            if (namedSeen && !(isLast && tailBlockMode)) {
                scope.raiseIllegalArgument("positional argument cannot follow named arguments")
            }
            positional.add(value)
        }
    }
    return Arguments(positional, tailBlockMode, named ?: emptyMap())
}
