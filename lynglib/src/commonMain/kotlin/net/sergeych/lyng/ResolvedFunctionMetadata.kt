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

/**
 * Compiler-resolved metadata for a function parameter.
 *
 * [type] is the declared element type for a variadic parameter, not its collected local type.
 * For example, `values: Int...` has a [type] of `Int`, [isEllipsis] is `true`, and the
 * corresponding entry in [ResolvedFunctionMetadata.functionType] is `Int...`.
 *
 * [defaultSource] preserves the source expression for tools that generate declarations. It is
 * null for parameters without defaults and may also be null for declarations assembled by a host
 * rather than parsed from source; use [hasDefault] to distinguish those cases.
 *
 * @property name parameter name as written in the declaration
 * @property type compiler-resolved declared type, or the variadic element type when [isEllipsis]
 * is `true`
 * @property isEllipsis whether this parameter collects repeated arguments
 * @property hasDefault whether the declaration supplies a default expression
 * @property defaultSource exact source text of the default expression, when available
 * @property pos source position of the parameter declaration
 */
data class ResolvedFunctionParameter(
    val name: String,
    val type: TypeDecl,
    val isEllipsis: Boolean,
    val hasDefault: Boolean,
    val defaultSource: String?,
    val pos: Pos,
)

/**
 * Semantic function declaration information produced by the compiler without evaluating source.
 *
 * Unlike the editor Mini-AST, this descriptor contains compiler-resolved parameter and return
 * types, including an inferred return type when inference succeeds. Annotation names describe the
 * declaration syntax; annotation functions are not executed while this metadata is collected.
 * The descriptor is intended for declaration generators and other embedding tools that need
 * semantic signatures without running the script.
 *
 * @property name declared function name
 * @property namePos source position of [name]
 * @property declarationPos source position at the start of the function declaration
 * @property visibility resolved declaration visibility
 * @property annotations annotation names in declaration order, without evaluating annotations
 * @property typeParams resolved generic type parameters, including bounds and defaults
 * @property parameters resolved parameters in declaration order
 * @property functionType complete callable type, including receiver, context receivers, variadic
 * markers, and the declared or inferred return type
 */
data class ResolvedFunctionMetadata(
    val name: String,
    val namePos: Pos,
    val declarationPos: Pos,
    val visibility: Visibility,
    val annotations: List<String>,
    val typeParams: List<TypeDecl.TypeParam>,
    val parameters: List<ResolvedFunctionParameter>,
    val functionType: TypeDecl.Function,
) {
    /** Declared or compiler-inferred return type of this function. */
    val returnType: TypeDecl get() = functionType.returnType
}
