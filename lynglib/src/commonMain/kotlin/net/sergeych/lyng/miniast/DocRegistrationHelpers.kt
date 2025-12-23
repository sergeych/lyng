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

package net.sergeych.lyng.miniast

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Visibility
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjVoid

/**
 * Helper extensions that mirror `addFn`/`addConst` APIs but also register Markdown docs
 * into the BuiltinDocRegistry (MiniAst-based). This keeps docs co-located with code
 * definitions and avoids any runtime overhead.
 */

// --------- Module-level (Scope) ---------

inline fun <reified T : Obj> Scope.addFnDoc(
    vararg names: String,
    doc: String,
    params: List<ParamDoc> = emptyList(),
    returns: TypeDoc? = null,
    tags: Map<String, List<String>> = emptyMap(),
    moduleName: String? = null,
    crossinline fn: suspend Scope.() -> T
) {
    // Register runtime function(s)
    addFn(*names) { fn() }
    // Determine module
    val mod = moduleName ?: findModuleNameOrUnknown()
    // Register docs once per name
    if (names.isNotEmpty()) BuiltinDocRegistry.module(mod) {
        for (n in names) funDoc(name = n, doc = doc, params = params, returns = returns, tags = tags)
    }
}

inline fun Scope.addVoidFnDoc(
    vararg names: String,
    doc: String,
    tags: Map<String, List<String>> = emptyMap(),
    moduleName: String? = null,
    crossinline fn: suspend Scope.() -> Unit
) {
    addFnDoc<ObjVoid>(
        *names,
        doc = doc,
        params = emptyList(),
        returns = null,
        tags = tags,
        moduleName = moduleName
    ) {
        fn(this)
        ObjVoid
    }
}

fun Scope.addConstDoc(
    name: String,
    value: Obj,
    doc: String,
    type: TypeDoc? = null,
    mutable: Boolean = false,
    tags: Map<String, List<String>> = emptyMap(),
    moduleName: String? = null
) {
    if (mutable) addItem(name, true, value) else addConst(name, value)
    BuiltinDocRegistry.module(moduleName ?: findModuleNameOrUnknown()) {
        valDoc(name = name, doc = doc, type = type, mutable = mutable, tags = tags)
    }
}

// --------- Class-level (ObjClass) ---------

fun ObjClass.addFnDoc(
    name: String,
    doc: String,
    params: List<ParamDoc> = emptyList(),
    returns: TypeDoc? = null,
    isOpen: Boolean = false,
    visibility: Visibility = Visibility.Public,
    tags: Map<String, List<String>> = emptyMap(),
    moduleName: String? = null,
    code: suspend Scope.() -> Obj
) {
    // Register runtime method
    addFn(name, isOpen, visibility, code = code)
    // Register docs for the member under this class
    BuiltinDocRegistry.module(moduleName ?: ownerModuleNameFromClassOrUnknown()) {
        classDoc(this@addFnDoc.className, doc = "") {
            method(name = name, doc = doc, params = params, returns = returns, tags = tags)
        }
    }
}

fun ObjClass.addConstDoc(
    name: String,
    value: Obj,
    doc: String,
    type: TypeDoc? = null,
    isMutable: Boolean = false,
    visibility: Visibility = Visibility.Public,
    tags: Map<String, List<String>> = emptyMap(),
    moduleName: String? = null
) {
    createField(name, value, isMutable, visibility)
    BuiltinDocRegistry.module(moduleName ?: ownerModuleNameFromClassOrUnknown()) {
        classDoc(this@addConstDoc.className, doc = "") {
            field(name = name, doc = doc, type = type, mutable = isMutable, tags = tags)
        }
    }
}

fun ObjClass.addClassFnDoc(
    name: String,
    doc: String,
    params: List<ParamDoc> = emptyList(),
    returns: TypeDoc? = null,
    isOpen: Boolean = false,
    tags: Map<String, List<String>> = emptyMap(),
    moduleName: String? = null,
    code: suspend Scope.() -> Obj
) {
    addClassFn(name, isOpen, code)
    BuiltinDocRegistry.module(moduleName ?: ownerModuleNameFromClassOrUnknown()) {
        classDoc(this@addClassFnDoc.className, doc = "") {
            method(name = name, doc = doc, params = params, returns = returns, isStatic = true, tags = tags)
        }
    }
}

fun ObjClass.addClassConstDoc(
    name: String,
    value: Obj,
    doc: String,
    type: TypeDoc? = null,
    isMutable: Boolean = false,
    tags: Map<String, List<String>> = emptyMap(),
    moduleName: String? = null
) {
    createClassField(name, value, isMutable)
    BuiltinDocRegistry.module(moduleName ?: ownerModuleNameFromClassOrUnknown()) {
        classDoc(this@addClassConstDoc.className, doc = "") {
            field(name = name, doc = doc, type = type, mutable = isMutable, isStatic = true, tags = tags)
        }
    }
}

// ------------- utils -------------
@PublishedApi
internal tailrec fun Scope.findModuleNameOrNull(): String? = when (this) {
    is ModuleScope -> this.packageName
    else -> this.parent?.findModuleNameOrNull()
}

@PublishedApi
internal fun Scope.findModuleNameOrUnknown(): String = findModuleNameOrNull() ?: "unknown"

@PublishedApi
internal fun ObjClass.ownerModuleNameFromClassOrUnknown(): String =
    // Try to find a ModuleScope in classScope parent chain if available, else unknown
    (classScope?.parent?.findModuleNameOrNull()) ?: "unknown"
