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

/*
 * Minimal, optional AST-like structures captured during parsing.
 *
 * These classes are lightweight and focused on editor features:
 * - highlighting (precise ranges for ids/types)
 * - documentation (raw doc text + summary)
 * - basic autocomplete (visible names per scope)
 *
 * They are populated only when an optional MiniAstSink is provided to the compiler.
 */

package net.sergeych.lyng.miniast

import net.sergeych.lyng.Pos

// Ranges reuse existing Pos to stay consistent with compiler diagnostics
data class MiniRange(val start: Pos, val end: Pos)

// Simple documentation payload: raw text and a derived summary (first non-empty line)
data class MiniDoc(
    val range: MiniRange,
    val raw: String,
    val summary: String?,
    val tags: Map<String, List<String>> = emptyMap()
)

sealed interface MiniNode { val range: MiniRange }

// Identifier roles we can confidently assign at parse time without binding
enum class IdRole {
    DeclFun, DeclClass, DeclVal, DeclVar, Param, Label, TypeName, Ref
}

// Type references (syntax-level, position rich)
sealed interface MiniTypeRef : MiniNode

data class MiniTypeName(
    override val range: MiniRange,
    val segments: List<Segment>,
    val nullable: Boolean
) : MiniTypeRef {
    data class Segment(val name: String, val range: MiniRange)
}

data class MiniGenericType(
    override val range: MiniRange,
    val base: MiniTypeRef,
    val args: List<MiniTypeRef>,
    val nullable: Boolean
) : MiniTypeRef

data class MiniFunctionType(
    override val range: MiniRange,
    val receiver: MiniTypeRef?,
    val params: List<MiniTypeRef>,
    val returnType: MiniTypeRef,
    val nullable: Boolean
) : MiniTypeRef

data class MiniTypeVar(
    override val range: MiniRange,
    val name: String,
    val nullable: Boolean
) : MiniTypeRef

// Script and declarations (lean subset; can be extended later)
sealed interface MiniDecl : MiniNode {
    val name: String
    val doc: MiniDoc?
    // Start position of the declaration name identifier in source; end can be derived as start + name.length
    val nameStart: Pos
}

data class MiniScript(
    override val range: MiniRange,
    val declarations: MutableList<MiniDecl> = mutableListOf(),
    val imports: MutableList<MiniImport> = mutableListOf(),
    val statements: MutableList<MiniStmt> = mutableListOf()
) : MiniNode

data class MiniParam(
    val name: String,
    val type: MiniTypeRef?,
    // Start position of parameter name in source
    val nameStart: Pos
)

data class MiniFunDecl(
    override val range: MiniRange,
    override val name: String,
    val params: List<MiniParam>,
    val returnType: MiniTypeRef?,
    val body: MiniBlock?,
    override val doc: MiniDoc?,
    override val nameStart: Pos
) : MiniDecl

data class MiniValDecl(
    override val range: MiniRange,
    override val name: String,
    val mutable: Boolean,
    val type: MiniTypeRef?,
    val initRange: MiniRange?,
    override val doc: MiniDoc?,
    override val nameStart: Pos
) : MiniDecl

data class MiniClassDecl(
    override val range: MiniRange,
    override val name: String,
    val bases: List<String>,
    val bodyRange: MiniRange?,
    val ctorFields: List<MiniCtorField> = emptyList(),
    val classFields: List<MiniCtorField> = emptyList(),
    override val doc: MiniDoc?,
    override val nameStart: Pos
) : MiniDecl

data class MiniCtorField(
    val name: String,
    val mutable: Boolean,
    val type: MiniTypeRef?,
    val nameStart: Pos
)

sealed interface MiniStmt : MiniNode

data class MiniBlock(
    override val range: MiniRange,
    val locals: MutableList<String> = mutableListOf()
) : MiniStmt

data class MiniIdentifier(
    override val range: MiniRange,
    val name: String,
    val role: IdRole
) : MiniNode

// Streaming sink to collect mini-AST during parsing. Implementations may assemble a tree or process events.
interface MiniAstSink {
    fun onScriptStart(start: Pos) {}
    fun onScriptEnd(end: Pos, script: MiniScript) {}

    fun onDocCandidate(doc: MiniDoc) {}

    fun onImport(node: MiniImport) {}
    fun onFunDecl(node: MiniFunDecl) {}
    fun onValDecl(node: MiniValDecl) {}
    fun onClassDecl(node: MiniClassDecl) {}

    fun onBlock(node: MiniBlock) {}
    fun onIdentifier(node: MiniIdentifier) {}
}

// Tracer interface used by the compiler's type parser to simultaneously build
// a MiniTypeRef (syntax-level) while the semantic TypeDecl is produced as usual.
// Keep it extremely small for now: the current parser supports only simple
// identifiers with optional nullable suffix. We can extend callbacks later for
// generics and function types without breaking callers.
interface MiniTypeTrace {
    /**
     * Report a simple (possibly qualified in the future) type name.
     * @param segments ordered list of name parts with precise ranges
     * @param nullable whether the trailing `?` was present
     */
    fun onSimpleName(segments: List<MiniTypeName.Segment>, nullable: Boolean)
}

// A simple builder that assembles a MiniScript tree from sink callbacks.
class MiniAstBuilder : MiniAstSink {
    private var currentScript: MiniScript? = null
    private val blocks = ArrayDeque<MiniBlock>()
    private var lastDoc: MiniDoc? = null
    private var scriptDepth: Int = 0

    fun build(): MiniScript? = currentScript

    override fun onScriptStart(start: Pos) {
        if (scriptDepth == 0) {
            currentScript = MiniScript(MiniRange(start, start))
        }
        scriptDepth++
    }

    override fun onScriptEnd(end: Pos, script: MiniScript) {
        scriptDepth = (scriptDepth - 1).coerceAtLeast(0)
        if (scriptDepth == 0) {
            // finalize root range only when closing the outermost script
            currentScript = currentScript?.copy(range = MiniRange(currentScript!!.range.start, end))
        }
    }

    override fun onDocCandidate(doc: MiniDoc) {
        lastDoc = doc
    }

    override fun onImport(node: MiniImport) {
        currentScript?.imports?.add(node)
    }

    override fun onFunDecl(node: MiniFunDecl) {
        val attach = node.copy(doc = node.doc ?: lastDoc)
        currentScript?.declarations?.add(attach)
        lastDoc = null
    }

    override fun onValDecl(node: MiniValDecl) {
        val attach = node.copy(doc = node.doc ?: lastDoc)
        currentScript?.declarations?.add(attach)
        lastDoc = null
    }

    override fun onClassDecl(node: MiniClassDecl) {
        val attach = node.copy(doc = node.doc ?: lastDoc)
        currentScript?.declarations?.add(attach)
        lastDoc = null
    }

    override fun onBlock(node: MiniBlock) {
        blocks.addLast(node)
    }
}

// Import statement representation for highlighting and docs
data class MiniImport(
    override val range: MiniRange,
    val segments: List<Segment>
) : MiniNode {
    data class Segment(val name: String, val range: MiniRange)
}
