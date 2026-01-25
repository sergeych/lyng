/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

import net.sergeych.lyng.obj.*

data class ParsedArgument(
     val value: Statement,
     val pos: Pos,
     val isSplat: Boolean = false,
     val name: String? = null,
 )
 
 suspend fun Collection<ParsedArgument>.toArguments(scope: Scope, tailBlockMode: Boolean): Arguments {
     // Detect if we can use the fast path: no splats and no named args
     if (PerfFlags.ARG_BUILDER) {
         val limit = if (PerfFlags.ARG_SMALL_ARITY_12) 12 else 8
         var hasSplatOrNamed = false
         var count = 0
         for (pa in this) {
             if (pa.isSplat || pa.name != null) { hasSplatOrNamed = true; break }
             count++
             if (count > limit) break
         }
        if (!hasSplatOrNamed && count == this.size) {
             val quick = when (count) {
                 0 -> Arguments.EMPTY
                 1 -> Arguments(listOf(this.elementAt(0).value.execute(scope)), tailBlockMode)
                 2 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     Arguments(listOf(a0, a1), tailBlockMode)
                 }
                 3 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     Arguments(listOf(a0, a1, a2), tailBlockMode)
                 }
                 4 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3), tailBlockMode)
                 }
                 5 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4), tailBlockMode)
                 }
                 6 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5), tailBlockMode)
                 }
                 7 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     val a6 = this.elementAt(6).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5, a6), tailBlockMode)
                 }
                 8 -> {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     val a6 = this.elementAt(6).value.execute(scope)
                     val a7 = this.elementAt(7).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7), tailBlockMode)
                 }
                 9 -> if (PerfFlags.ARG_SMALL_ARITY_12) {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     val a6 = this.elementAt(6).value.execute(scope)
                     val a7 = this.elementAt(7).value.execute(scope)
                     val a8 = this.elementAt(8).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7, a8), tailBlockMode)
                 } else null
                 10 -> if (PerfFlags.ARG_SMALL_ARITY_12) {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     val a6 = this.elementAt(6).value.execute(scope)
                     val a7 = this.elementAt(7).value.execute(scope)
                     val a8 = this.elementAt(8).value.execute(scope)
                     val a9 = this.elementAt(9).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9), tailBlockMode)
                 } else null
                 11 -> if (PerfFlags.ARG_SMALL_ARITY_12) {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     val a6 = this.elementAt(6).value.execute(scope)
                     val a7 = this.elementAt(7).value.execute(scope)
                     val a8 = this.elementAt(8).value.execute(scope)
                     val a9 = this.elementAt(9).value.execute(scope)
                     val a10 = this.elementAt(10).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10), tailBlockMode)
                 } else null
                 12 -> if (PerfFlags.ARG_SMALL_ARITY_12) {
                     val a0 = this.elementAt(0).value.execute(scope)
                     val a1 = this.elementAt(1).value.execute(scope)
                     val a2 = this.elementAt(2).value.execute(scope)
                     val a3 = this.elementAt(3).value.execute(scope)
                     val a4 = this.elementAt(4).value.execute(scope)
                     val a5 = this.elementAt(5).value.execute(scope)
                     val a6 = this.elementAt(6).value.execute(scope)
                     val a7 = this.elementAt(7).value.execute(scope)
                     val a8 = this.elementAt(8).value.execute(scope)
                     val a9 = this.elementAt(9).value.execute(scope)
                     val a10 = this.elementAt(10).value.execute(scope)
                     val a11 = this.elementAt(11).value.execute(scope)
                     Arguments(listOf(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11), tailBlockMode)
                 } else null
                 else -> null
             }
             if (quick != null) return quick
        }
     }

     // General path: build positional list and named map, enforcing ordering rules
    val positional: MutableList<Obj> = mutableListOf()
     var named: MutableMap<String, Obj>? = null
     var namedSeen = false
     for ((idx, x) in this.withIndex()) {
         if (x.name != null) {
             // Named argument
             if (named == null) named = linkedMapOf()
             if (named.containsKey(x.name)) scope.raiseIllegalArgument("argument '${x.name}' is already set")
             val v = x.value.execute(scope)
             named[x.name] = v
             namedSeen = true
             continue
         }
         val value = x.value.execute(scope)
         if (x.isSplat) {
             when {
                 // IMPORTANT: handle ObjMap BEFORE generic Iterable to ensure map splats
                 // are treated as named splats, not as positional iteration over entries
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
                     if (namedSeen) {
                         // allow only if this is the very last positional which will be the trailing block; but
                         // splat can never be a trailing block, so it's always illegal here
                         scope.raiseIllegalArgument("positional splat cannot follow named arguments")
                     }
                     positional.addAll(value.list)
                 }
                 value.isInstanceOf(ObjIterable) -> {
                     if (namedSeen) scope.raiseIllegalArgument("positional splat cannot follow named arguments")
                     val i = (value.invokeInstanceMethod(scope, "toList") as ObjList).list
                     positional.addAll(i)
                 }
                 else -> scope.raiseClassCastError("expected list of objects for splat argument")
             }
         } else {
             if (namedSeen) {
                 // Allow exactly one positional after named only when it is the very last argument overall
                 // and tailBlockMode is true (syntactic trailing block). Otherwise, forbid it.
                 val isLast = idx == this.size - 1
                 if (!(isLast && tailBlockMode))
                     scope.raiseIllegalArgument("positional argument cannot follow named arguments")
             }
             positional.add(value)
         }
     }
     val namedFinal = named ?: emptyMap()
     return Arguments(positional, tailBlockMode, namedFinal)
  }
 
 data class Arguments(
     val list: List<Obj>,
     val tailBlockMode: Boolean = false,
     val named: Map<String, Obj> = emptyMap(),
 ) : List<Obj> by list {
 
     constructor(vararg values: Obj) : this(values.toList())
 
     fun firstAndOnly(pos: Pos = Pos.UNKNOWN): Obj {
         if (list.size != 1) throw ScriptError(pos, "expected one argument, got ${list.size}")
         // Tiny micro-alloc win: avoid byValueCopy for immutable singletons
         return when (val v = list.first()) {
             ObjNull,
             ObjTrue,
             ObjFalse,
            // Immutable scalars: safe to return directly
            is ObjInt,
            is ObjReal,
            is ObjChar,
            is ObjString -> v
             else -> v.byValueCopy()
         }
     }
 
     /**
      * Convert to list of kotlin objects, see [Obj.toKotlin].
      */
     suspend fun toKotlinList(scope: Scope): List<Any?> {
         return list.map { it.toKotlin(scope) }
     }
 
     suspend fun inspect(scope: Scope): String = list.map{ it.inspect(scope)}.joinToString(",")
 
     companion object {
         val EMPTY = Arguments(emptyList())
         fun from(values: Collection<Obj>) = Arguments(values.toList())
     }
 }
