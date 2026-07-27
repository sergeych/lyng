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
 */

package net.sergeych.lyng

/**
 * Compiler-resolved metadata for a function parameter.
 *
 * [defaultSource] preserves the source expression for tools that generate declarations. It is
 * null for parameters without defaults and may also be null for declarations assembled by a host
 * rather than parsed from source; use [hasDefault] to distinguish those cases.
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
    val returnType: TypeDecl get() = functionType.returnType
}
