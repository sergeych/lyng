/*
 * Copyright 2026 Sergey S. Chernov
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
 */

package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.TypeDecl

/**
 * Runtime wrapper for a type expression (including unions/intersections) used by `is` checks.
 */
class ObjTypeExpr(val typeDecl: TypeDecl) : Obj()

internal fun matchesTypeDecl(scope: Scope, value: Obj, typeDecl: TypeDecl): Boolean {
    if (value === ObjNull) {
        return typeDecl.isNullable || typeDecl is TypeDecl.TypeNullableAny
    }
    fun resolveClassFromScope(typeName: String): ObjClass? {
        val direct = scope[typeName]?.value as? ObjClass
        if (direct != null) return direct
        val simple = typeName.substringAfterLast('.')
        return scope[simple]?.value as? ObjClass
    }
    return when (typeDecl) {
        TypeDecl.TypeAny -> true
        TypeDecl.TypeNullableAny -> true
        is TypeDecl.TypeVar -> {
            val cls = (scope[typeDecl.name]?.value as? ObjClass)
            if (cls != null) value.isInstanceOf(cls) else value.isInstanceOf(typeDecl.name)
        }
        is TypeDecl.Simple -> {
            val cls = resolveClassFromScope(typeDecl.name)
            if (cls != null) value.isInstanceOf(cls) else value.isInstanceOf(typeDecl.name.substringAfterLast('.'))
        }
        is TypeDecl.Generic -> {
            val cls = resolveClassFromScope(typeDecl.name)
            if (cls != null) value.isInstanceOf(cls) else value.isInstanceOf(typeDecl.name.substringAfterLast('.'))
        }
        is TypeDecl.Function -> value.isInstanceOf("Callable")
        is TypeDecl.Union -> typeDecl.options.any { matchesTypeDecl(scope, value, it) }
        is TypeDecl.Intersection -> typeDecl.options.all { matchesTypeDecl(scope, value, it) }
    }
}
