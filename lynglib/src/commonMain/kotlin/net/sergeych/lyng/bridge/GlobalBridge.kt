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

package net.sergeych.lyng.bridge

import net.sergeych.lyng.*
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjExternCallable
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjProperty
import net.sergeych.lyng.obj.ObjReal
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.toBool
import net.sergeych.lyng.obj.toDouble
import net.sergeych.lyng.obj.toInt
import net.sergeych.lyng.obj.toLong
import net.sergeych.lyng.obj.toObj
import net.sergeych.lyng.requiredArg

/**
 * Global/module-level binding API for Lyng-first extern declarations.
 *
 * Typical flow:
 * 1) declare `extern fun` / `extern val` / `extern var` in Lyng module;
 * 2) bind Kotlin implementation using this API.
 */
interface LyngGlobalBinder {
    fun bindGlobalFunRaw(
        name: String,
        fn: suspend (scope: ScopeFacade, args: Arguments) -> Obj
    )

    fun bindGlobalFun(
        name: String,
        fn: suspend GlobalArgReader.() -> Obj
    )

    fun bindGlobalVarRaw(
        name: String,
        get: suspend (scope: ScopeFacade) -> Obj,
        set: (suspend (scope: ScopeFacade, value: Obj) -> Unit)? = null
    )
}

/**
 * Reader helper for Kotlin-typed argument access.
 */
interface GlobalArgReader {
    val scope: ScopeFacade
    val args: Arguments
    val size: Int

    fun requireExactCount(count: Int)
    fun obj(index: Int): Obj
    fun objOrNull(index: Int): Obj?
    fun int(index: Int): Int
    fun long(index: Int): Long
    fun double(index: Int): Double
    fun bool(index: Int): Boolean
    fun string(index: Int): String
}

private class ModuleGlobalBinder(
    private val module: ModuleScope
) : LyngGlobalBinder {

    override fun bindGlobalFunRaw(
        name: String,
        fn: suspend (scope: ScopeFacade, args: Arguments) -> Obj
    ) {
        val existing = module[name]
        val callable = ObjExternCallable.fromBridge {
            fn(this, args)
        }
        module.addItem(
            name = name,
            isMutable = false,
            value = callable,
            visibility = existing?.visibility ?: Visibility.Public,
            writeVisibility = existing?.writeVisibility,
            recordType = ObjRecord.Type.Fun,
            callSignature = existing?.callSignature,
            typeDecl = existing?.typeDecl
        )
    }

    override fun bindGlobalFun(
        name: String,
        fn: suspend GlobalArgReader.() -> Obj
    ) {
        bindGlobalFunRaw(name) { scope, args ->
            val reader = GlobalArgReaderImpl(scope, args)
            fn(reader)
        }
    }

    override fun bindGlobalVarRaw(
        name: String,
        get: suspend (scope: ScopeFacade) -> Obj,
        set: (suspend (scope: ScopeFacade, value: Obj) -> Unit)?
    ) {
        val existing = module[name]
        if (existing != null) {
            if (existing.isMutable && set == null) {
                throw net.sergeych.lyng.ScriptError(Pos.builtIn, "extern var $name requires a setter")
            }
            if (!existing.isMutable && set != null) {
                throw net.sergeych.lyng.ScriptError(Pos.builtIn, "extern val $name does not allow a setter")
            }
        }
        val mutable = existing?.isMutable ?: (set != null)
        val getter = ObjExternCallable.fromBridge {
            get(this)
        }
        val setter = set?.let { setterImpl ->
            ObjExternCallable.fromBridge {
                setterImpl(this, requiredArg(0))
                ObjVoid
            }
        }
        module.addItem(
            name = name,
            isMutable = mutable,
            value = ObjProperty(name, getter, setter),
            visibility = existing?.visibility ?: Visibility.Public,
            writeVisibility = existing?.writeVisibility,
            recordType = ObjRecord.Type.Property,
            callSignature = existing?.callSignature,
            typeDecl = existing?.typeDecl
        )
    }
}

private class GlobalArgReaderImpl(
    override val scope: ScopeFacade,
    override val args: Arguments
) : GlobalArgReader {
    override val size: Int
        get() = args.list.size

    override fun requireExactCount(count: Int) {
        if (size != count) scope.raiseIllegalArgument("Expected exactly $count arguments, got $size")
    }

    override fun obj(index: Int): Obj =
        objOrNull(index) ?: scope.raiseIllegalArgument("Missing required argument at index $index")

    override fun objOrNull(index: Int): Obj? =
        args.list.getOrNull(index)

    override fun int(index: Int): Int = long(index).toInt()

    override fun long(index: Int): Long = obj(index).toLong()

    override fun double(index: Int): Double = obj(index).toDouble()

    override fun bool(index: Int): Boolean = obj(index).toBool()

    override fun string(index: Int): String {
        val value = obj(index)
        return (value as? ObjString)?.value
            ?: scope.raiseClassCastError("Expected String at index $index, got ${value.objClass.className}")
    }
}

fun ModuleScope.globalBinder(): LyngGlobalBinder = ModuleGlobalBinder(this)

inline fun <reified T> GlobalArgReader.required(index: Int): T =
    coerceArg(scope, obj(index), index)

inline fun <reified T> GlobalArgReader.optional(index: Int, default: T): T {
    val value = objOrNull(index) ?: return default
    return coerceArg(scope, value, index)
}

inline fun <reified A1> LyngGlobalBinder.bindGlobalFun1(
    name: String,
    noinline fn: suspend (A1) -> Obj
) {
    bindGlobalFun(name) {
        requireExactCount(1)
        fn(required(0))
    }
}

inline fun <reified A1, reified A2> LyngGlobalBinder.bindGlobalFun2(
    name: String,
    noinline fn: suspend (A1, A2) -> Obj
) {
    bindGlobalFun(name) {
        requireExactCount(2)
        fn(required(0), required(1))
    }
}

inline fun <reified A1, reified A2, reified A3> LyngGlobalBinder.bindGlobalFun3(
    name: String,
    noinline fn: suspend (A1, A2, A3) -> Obj
) {
    bindGlobalFun(name) {
        requireExactCount(3)
        fn(required(0), required(1), required(2))
    }
}

inline fun <reified T> LyngGlobalBinder.bindGlobalVar(
    name: String,
    noinline get: suspend () -> T,
    noinline set: (suspend (T) -> Unit)? = null
) {
    bindGlobalVarRaw(
        name = name,
        get = { get().toObj() },
        set = set?.let { setter ->
            { scope, value ->
                setter(coerceArg<T>(scope = scope, value = value, index = 0))
            }
        }
    )
}

@PublishedApi
internal inline fun <reified T> coerceArg(scope: ScopeFacade, value: Obj, index: Int): T {
    if (value === ObjNull && null is T) return null as T
    (value as? T)?.let { return it }
    @Suppress("UNCHECKED_CAST")
    return when (T::class) {
        Int::class -> value.toInt() as T
        Long::class -> value.toLong() as T
        Double::class -> value.toDouble() as T
        Float::class -> value.toDouble().toFloat() as T
        Boolean::class -> value.toBool() as T
        String::class -> (value as? ObjString)?.value as? T
            ?: scope.raiseClassCastError("Expected String at index $index, got ${value.objClass.className}")
        Obj::class -> value as T
        ObjInt::class -> (value as? ObjInt) as? T
            ?: scope.raiseClassCastError("Expected ObjInt at index $index, got ${value.objClass.className}")
        ObjString::class -> (value as? ObjString) as? T
            ?: scope.raiseClassCastError("Expected ObjString at index $index, got ${value.objClass.className}")
        ObjReal::class -> (value as? ObjReal) as? T
            ?: scope.raiseClassCastError("Expected ObjReal at index $index, got ${value.objClass.className}")
        ObjBool::class -> (value as? ObjBool) as? T
            ?: scope.raiseClassCastError("Expected ObjBool at index $index, got ${value.objClass.className}")
        else -> scope.raiseClassCastError("Unsupported typed argument binding for ${T::class.simpleName}")
    }
}
