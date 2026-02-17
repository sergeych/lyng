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
 *
 */

package net.sergeych.lyng.resolution

import net.sergeych.lyng.Pos

enum class ScopeKind {
    MODULE,
    FUNCTION,
    BLOCK,
    CLASS
}

enum class SymbolKind {
    LOCAL,
    PARAM,
    FUNCTION,
    CLASS,
    ENUM,
    MEMBER
}

interface ResolutionSink {
    fun enterScope(kind: ScopeKind, pos: Pos, className: String? = null, bases: List<String> = emptyList()) {}
    fun exitScope(pos: Pos) {}
    fun declareClass(name: String, bases: List<String>, pos: Pos) {}
    fun declareSymbol(
        name: String,
        kind: SymbolKind,
        isMutable: Boolean,
        pos: Pos,
        isOverride: Boolean = false
    ) {}
    fun reference(name: String, pos: Pos) {}
    fun referenceMember(name: String, pos: Pos, qualifier: String? = null) {}
    fun referenceReflection(name: String, pos: Pos) {}
}
