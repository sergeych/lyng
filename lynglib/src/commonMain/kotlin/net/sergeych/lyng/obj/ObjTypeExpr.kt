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

import net.sergeych.lyng.Scope
import net.sergeych.lyng.TypeDecl

/**
 * Runtime wrapper for a type expression (including unions/intersections) used by `is` checks.
 */
class ObjTypeExpr(val typeDecl: TypeDecl) : Obj() {
    override suspend fun equals(scope: Scope, other: Obj): Boolean {
        val otherDecl = typeDeclFromObj(scope, other) ?: return false
        val leftKey = typeDeclKey(normalizeTypeDecl(scope, typeDecl))
        val rightKey = typeDeclKey(normalizeTypeDecl(scope, otherDecl))
        return leftKey == rightKey
    }

    override suspend fun contains(scope: Scope, other: Obj): Boolean {
        val leftDecl = typeDeclFromObj(scope, other) ?: return false
        val rightDecl = normalizeTypeDecl(scope, typeDecl)
        return typeDeclIsSubtype(scope, leftDecl, rightDecl)
    }
}

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
        is TypeDecl.Ellipsis -> matchesTypeDecl(scope, value, typeDecl.elementType)
        is TypeDecl.Union -> typeDecl.options.any { matchesTypeDecl(scope, value, it) }
        is TypeDecl.Intersection -> typeDecl.options.all { matchesTypeDecl(scope, value, it) }
    }
}

internal fun typeDeclFromObj(scope: Scope, value: Obj): TypeDecl? {
    return when (value) {
        is ObjTypeExpr -> normalizeTypeDecl(scope, value.typeDecl)
        is ObjClass -> TypeDecl.Simple(value.className, false)
        else -> null
    }
}

internal fun typeDeclIsSubtype(scope: Scope, left: TypeDecl, right: TypeDecl): Boolean {
    val lNorm = normalizeTypeDecl(scope, left)
    val rNorm = normalizeTypeDecl(scope, right)
    val lNullable = lNorm.isNullable || lNorm is TypeDecl.TypeNullableAny
    val rNullable = rNorm.isNullable || rNorm is TypeDecl.TypeNullableAny
    if (lNullable && !rNullable) return false
    val l = stripNullable(lNorm)
    val r = stripNullable(rNorm)
    if (r == TypeDecl.TypeAny || r == TypeDecl.TypeNullableAny) return true
    if (l == TypeDecl.TypeAny) return r == TypeDecl.TypeAny || r == TypeDecl.TypeNullableAny
    if (l == TypeDecl.TypeNullableAny) return r == TypeDecl.TypeNullableAny
    return when (l) {
        is TypeDecl.Union -> l.options.all { typeDeclIsSubtype(scope, it, r) }
        is TypeDecl.Intersection -> l.options.any { typeDeclIsSubtype(scope, it, r) }
        is TypeDecl.Ellipsis -> typeDeclIsSubtype(scope, l.elementType, r)
        else -> when (r) {
            is TypeDecl.Union -> r.options.any { typeDeclIsSubtype(scope, l, it) }
            is TypeDecl.Intersection -> r.options.all { typeDeclIsSubtype(scope, l, it) }
            is TypeDecl.Simple, is TypeDecl.Generic, is TypeDecl.Function, is TypeDecl.Ellipsis -> {
                val leftClass = resolveTypeDeclClass(scope, l) ?: return false
                val rightClass = resolveTypeDeclClass(scope, r) ?: return false
                leftClass == rightClass || leftClass.allParentsSet.contains(rightClass)
            }
            else -> false
        }
    }
}

private fun normalizeTypeDecl(scope: Scope, decl: TypeDecl): TypeDecl {
    val resolved = if (decl is TypeDecl.TypeVar) {
        val bound = scope[decl.name]?.value
        when (bound) {
            is ObjTypeExpr -> bound.typeDecl
            is ObjClass -> TypeDecl.Simple(bound.className, decl.isNullable)
            else -> decl
        }
    } else decl
    return when (resolved) {
        is TypeDecl.Union -> normalizeUnion(scope, resolved)
        is TypeDecl.Intersection -> normalizeIntersection(scope, resolved)
        else -> resolved
    }
}

private fun normalizeUnion(scope: Scope, decl: TypeDecl.Union): TypeDecl {
    val options = mutableListOf<TypeDecl>()
    var nullable = decl.isNullable
    for (opt in decl.options) {
        val norm = normalizeTypeDecl(scope, opt)
        if (norm is TypeDecl.TypeNullableAny) nullable = true
        val base = stripNullable(norm)
        if (base == TypeDecl.TypeAny) return if (nullable) TypeDecl.TypeNullableAny else TypeDecl.TypeAny
        if (base is TypeDecl.Union) {
            options.addAll(base.options)
        } else {
            options += base
        }
        nullable = nullable || norm.isNullable
    }
    val unique = options.distinctBy { typeDeclKey(it) }.sortedBy { typeDeclKey(it) }
    val base = if (unique.size == 1) unique[0] else TypeDecl.Union(unique, nullable = false)
    return if (nullable) makeNullable(base) else base
}

private fun normalizeIntersection(scope: Scope, decl: TypeDecl.Intersection): TypeDecl {
    val options = mutableListOf<TypeDecl>()
    var nullable = decl.isNullable
    for (opt in decl.options) {
        val norm = normalizeTypeDecl(scope, opt)
        val base = stripNullable(norm)
        if (base == TypeDecl.TypeAny) {
            nullable = nullable || norm.isNullable
            continue
        }
        if (base is TypeDecl.Intersection) {
            options.addAll(base.options)
        } else {
            options += base
        }
        nullable = nullable || norm.isNullable
    }
    val unique = options.distinctBy { typeDeclKey(it) }.sortedBy { typeDeclKey(it) }
    val base = when {
        unique.isEmpty() -> TypeDecl.TypeAny
        unique.size == 1 -> unique[0]
        else -> TypeDecl.Intersection(unique, nullable = false)
    }
    return if (nullable) makeNullable(base) else base
}

private fun stripNullable(type: TypeDecl): TypeDecl {
    return if (!type.isNullable && type !is TypeDecl.TypeNullableAny) {
        type
    } else {
        when (type) {
            is TypeDecl.Function -> type.copy(nullable = false)
            is TypeDecl.Ellipsis -> type.copy(nullable = false)
            is TypeDecl.TypeVar -> type.copy(nullable = false)
            is TypeDecl.Union -> type.copy(nullable = false)
            is TypeDecl.Intersection -> type.copy(nullable = false)
            is TypeDecl.Simple -> TypeDecl.Simple(type.name, false)
            is TypeDecl.Generic -> TypeDecl.Generic(type.name, type.args, false)
            else -> TypeDecl.TypeAny
        }
    }
}

private fun makeNullable(type: TypeDecl): TypeDecl {
    return when (type) {
        TypeDecl.TypeAny -> TypeDecl.TypeNullableAny
        TypeDecl.TypeNullableAny -> type
        is TypeDecl.Function -> type.copy(nullable = true)
        is TypeDecl.Ellipsis -> type.copy(nullable = true)
        is TypeDecl.TypeVar -> type.copy(nullable = true)
        is TypeDecl.Union -> type.copy(nullable = true)
        is TypeDecl.Intersection -> type.copy(nullable = true)
        is TypeDecl.Simple -> TypeDecl.Simple(type.name, true)
        is TypeDecl.Generic -> TypeDecl.Generic(type.name, type.args, true)
    }
}

private fun typeDeclKey(type: TypeDecl): String = when (type) {
    TypeDecl.TypeAny -> "Any"
    TypeDecl.TypeNullableAny -> "Any?"
    is TypeDecl.Simple -> "S:${type.name}"
    is TypeDecl.Generic -> "G:${type.name}<${type.args.joinToString(",") { typeDeclKey(it) }}>"
    is TypeDecl.Function -> "F:(${type.params.joinToString(",") { typeDeclKey(it) }})->${typeDeclKey(type.returnType)}"
    is TypeDecl.Ellipsis -> "E:${typeDeclKey(type.elementType)}"
    is TypeDecl.TypeVar -> "V:${type.name}"
    is TypeDecl.Union -> "U:${type.options.joinToString("|") { typeDeclKey(it) }}"
    is TypeDecl.Intersection -> "I:${type.options.joinToString("&") { typeDeclKey(it) }}"
}

private fun resolveTypeDeclClass(scope: Scope, type: TypeDecl): ObjClass? {
    return when (type) {
        is TypeDecl.Simple -> {
            val direct = scope[type.name]?.value as? ObjClass
            direct ?: scope[type.name.substringAfterLast('.')]?.value as? ObjClass
        }
        is TypeDecl.Generic -> {
            val direct = scope[type.name]?.value as? ObjClass
            direct ?: scope[type.name.substringAfterLast('.')]?.value as? ObjClass
        }
        is TypeDecl.Function -> scope["Callable"]?.value as? ObjClass
        is TypeDecl.Ellipsis -> resolveTypeDeclClass(scope, type.elementType)
        is TypeDecl.TypeVar -> {
            val bound = scope[type.name]?.value
            when (bound) {
                is ObjClass -> bound
                is ObjTypeExpr -> resolveTypeDeclClass(scope, bound.typeDecl)
                else -> null
            }
        }
        else -> null
    }
}
