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

@file:Suppress("unused")

package net.sergeych.lyng

// this is highly experimental and subject to complete redesign
// very soon
sealed class TypeDecl(val isNullable:Boolean = false) {
    // ??
//    data class Fn(val argTypes: List<ArgsDeclaration.Item>, val retType: TypeDecl) : TypeDecl()
    object TypeAny : TypeDecl()
    object TypeNullableAny : TypeDecl(true)

    class Simple(val name: String,isNullable: Boolean) : TypeDecl(isNullable)
}
