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

package net.sergeych.lyng.tools

import net.sergeych.lyng.*
import net.sergeych.lyng.binding.Binder
import net.sergeych.lyng.binding.BindingSnapshot
import net.sergeych.lyng.binding.SymbolKind
import net.sergeych.lyng.bytecode.BytecodeStatement
import net.sergeych.lyng.bytecode.CmdDisassembler
import net.sergeych.lyng.format.LyngFormatConfig
import net.sergeych.lyng.format.LyngFormatter
import net.sergeych.lyng.highlight.HighlightSpan
import net.sergeych.lyng.highlight.SimpleLyngHighlighter
import net.sergeych.lyng.highlight.TextRange
import net.sergeych.lyng.highlight.offsetOf
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.pacman.ImportProvider
import net.sergeych.lyng.resolution.ResolutionCollector
import net.sergeych.lyng.resolution.ResolutionReport

data class LyngAnalysisRequest(
    val text: String,
    val fileName: String = "<snippet>",
    val importProvider: ImportProvider = Script.defaultImportManager,
    val seedScope: Scope? = null,
    val allowUnresolvedRefs: Boolean = true
)

enum class LyngDiagnosticSeverity { Error, Warning }

data class LyngDiagnostic(
    val message: String,
    val severity: LyngDiagnosticSeverity,
    val range: TextRange? = null,
    val pos: Pos? = null
)

data class LyngAnalysisResult(
    val source: Source,
    val text: String,
    val mini: MiniScript?,
    val binding: BindingSnapshot?,
    val resolution: ResolutionReport?,
    val importedModules: List<String>,
    val diagnostics: List<LyngDiagnostic>,
    val lexicalHighlights: List<HighlightSpan>
)

data class LyngSymbolTarget(
    val name: String,
    val kind: SymbolKind,
    val range: TextRange,
    val containerName: String? = null
)

data class LyngSymbolInfo(
    val target: LyngSymbolTarget,
    val signature: String? = null,
    val doc: MiniDoc? = null
)

enum class LyngSemanticKind {
    Function,
    Class,
    Enum,
    TypeAlias,
    Value,
    Variable,
    Parameter,
    TypeRef,
    EnumConstant
}

data class LyngSemanticSpan(
    val range: TextRange,
    val kind: LyngSemanticKind
)

object LyngLanguageTools {

    suspend fun analyze(request: LyngAnalysisRequest): LyngAnalysisResult {
        // Ensure stdlib/Obj* docs are initialized and stdlib docs are available before anything else
        StdlibDocsBootstrap.ensure()
        BuiltinDocRegistry.docsForModule("lyng.stdlib")

        val source = Source(request.fileName, request.text)
        val miniSink = MiniAstBuilder()
        val resolutionCollector = ResolutionCollector(source.fileName)
        val diagnostics = ArrayList<LyngDiagnostic>()

        try {
            Compiler.compileWithResolution(
                source,
                request.importProvider,
                miniSink = miniSink,
                resolutionSink = resolutionCollector,
                compileBytecode = false,
                allowUnresolvedRefs = request.allowUnresolvedRefs,
                seedScope = request.seedScope
            )
        } catch (t: Throwable) {
            val pos = (t as? net.sergeych.lyng.ScriptError)?.pos
            diagnostics += LyngDiagnostic(
                message = t.message ?: t.toString(),
                severity = LyngDiagnosticSeverity.Error,
                range = pos?.let { posToRange(source, it) },
                pos = pos
            )
        }

        val mini = miniSink.build()
        val binding = mini?.let { Binder.bind(request.text, it) }
        val report = try { resolutionCollector.buildReport() } catch (_: Throwable) { null }

        report?.errors?.forEach { err ->
            diagnostics += LyngDiagnostic(
                message = err.message,
                severity = LyngDiagnosticSeverity.Error,
                range = posToRange(source, err.pos),
                pos = err.pos
            )
        }
        report?.warnings?.forEach { warn ->
            diagnostics += LyngDiagnostic(
                message = warn.message,
                severity = LyngDiagnosticSeverity.Warning,
                range = posToRange(source, warn.pos),
                pos = warn.pos
            )
        }

        val imports = when {
            mini != null -> DocLookupUtils.canonicalImportedModules(mini, request.text)
            else -> DocLookupUtils.extractImportsFromText(request.text).toMutableList().apply { add("lyng.stdlib") }.distinct()
        }

        val lexical = try { SimpleLyngHighlighter().highlight(request.text) } catch (_: Throwable) { emptyList() }

        return LyngAnalysisResult(
            source = source,
            text = request.text,
            mini = mini,
            binding = binding,
            resolution = report,
            importedModules = imports,
            diagnostics = diagnostics,
            lexicalHighlights = lexical
        )
    }

    suspend fun analyze(text: String, fileName: String = "<snippet>"): LyngAnalysisResult =
        analyze(LyngAnalysisRequest(text = text, fileName = fileName))

    fun format(text: String, config: LyngFormatConfig = LyngFormatConfig()): String =
        LyngFormatter.format(text, config)

    fun lexicalHighlights(text: String): List<HighlightSpan> =
        SimpleLyngHighlighter().highlight(text)

    fun semanticHighlights(analysis: LyngAnalysisResult): List<LyngSemanticSpan> {
        val mini = analysis.mini ?: return emptyList()
        val source = analysis.source
        val out = ArrayList<LyngSemanticSpan>(128)
        val covered = HashSet<Pair<Int, Int>>()
        fun isCurrentSource(pos: Pos): Boolean = pos.source === source

        fun addRange(start: Int, end: Int, kind: LyngSemanticKind) {
            if (start < 0 || end <= start || end > analysis.text.length) return
            val key = start to end
            if (covered.add(key)) out += LyngSemanticSpan(TextRange(start, end), kind)
        }

        fun addName(pos: Pos, name: String, kind: LyngSemanticKind) {
            if (!isCurrentSource(pos)) return
            val s = source.offsetOf(pos)
            addRange(s, s + name.length, kind)
        }

        fun addTypeSegments(t: MiniTypeRef?) {
            when (t) {
                is MiniTypeName -> t.segments.forEach { seg ->
                    addName(seg.range.start, seg.name, LyngSemanticKind.TypeRef)
                }
                is MiniGenericType -> {
                    addTypeSegments(t.base)
                    t.args.forEach { addTypeSegments(it) }
                }
                is MiniFunctionType -> {
                    t.receiver?.let { addTypeSegments(it) }
                    t.params.forEach { addTypeSegments(it) }
                    addTypeSegments(t.returnType)
                }
                is MiniTypeVar -> {
                    if (isCurrentSource(t.range.start) && isCurrentSource(t.range.end)) {
                        addRange(source.offsetOf(t.range.start), source.offsetOf(t.range.end), LyngSemanticKind.TypeRef)
                    }
                }
                is MiniTypeUnion -> {
                    t.options.forEach { addTypeSegments(it) }
                }
                is MiniTypeIntersection -> {
                    t.options.forEach { addTypeSegments(it) }
                }
                null -> {}
            }
        }

        fun addDeclTypeSegments(d: MiniDecl) {
            when (d) {
                is MiniFunDecl -> {
                    addTypeSegments(d.returnType)
                    d.params.forEach { addTypeSegments(it.type) }
                    addTypeSegments(d.receiver)
                }
                is MiniValDecl -> {
                    addTypeSegments(d.type)
                    addTypeSegments(d.receiver)
                }
                is MiniClassDecl -> {
                    d.ctorFields.forEach { addTypeSegments(it.type) }
                    d.classFields.forEach { addTypeSegments(it.type) }
                    d.members.forEach { m ->
                        when (m) {
                            is MiniMemberFunDecl -> {
                                addTypeSegments(m.returnType)
                                m.params.forEach { addTypeSegments(it.type) }
                            }
                            is MiniMemberValDecl -> addTypeSegments(m.type)
                            is MiniMemberTypeAliasDecl -> addTypeSegments(m.target)
                            is MiniInitDecl -> {}
                        }
                    }
                }
                is MiniEnumDecl -> {}
                is MiniTypeAliasDecl -> addTypeSegments(d.target)
            }
        }

        for (d in mini.declarations) {
            when (d) {
                is MiniFunDecl -> addName(d.nameStart, d.name, LyngSemanticKind.Function)
                is MiniClassDecl -> addName(d.nameStart, d.name, LyngSemanticKind.Class)
                is MiniEnumDecl -> addName(d.nameStart, d.name, LyngSemanticKind.Enum)
                is MiniValDecl -> addName(d.nameStart, d.name, if (d.mutable) LyngSemanticKind.Variable else LyngSemanticKind.Value)
                is MiniTypeAliasDecl -> addName(d.nameStart, d.name, LyngSemanticKind.TypeAlias)
            }
            addDeclTypeSegments(d)
        }

        mini.imports.forEach { imp ->
            imp.segments.forEach { seg ->
                if (isCurrentSource(seg.range.start) && isCurrentSource(seg.range.end)) {
                    addRange(source.offsetOf(seg.range.start), source.offsetOf(seg.range.end), LyngSemanticKind.TypeRef)
                }
            }
        }

        fun addParams(params: List<MiniParam>) {
            params.forEach { p -> addName(p.nameStart, p.name, LyngSemanticKind.Parameter) }
        }
        mini.declarations.forEach { d ->
            when (d) {
                is MiniFunDecl -> addParams(d.params)
                is MiniClassDecl -> d.members.filterIsInstance<MiniMemberFunDecl>().forEach { addParams(it.params) }
                else -> {}
            }
        }

        mini.declarations.filterIsInstance<MiniEnumDecl>().forEach { en ->
            en.entries.zip(en.entryPositions).forEach { (name, pos) ->
                addName(pos, name, LyngSemanticKind.EnumConstant)
            }
        }

        analysis.binding?.let { binding ->
            val declKeys = binding.symbols.map { it.declStart to it.declEnd }.toSet()
            for (ref in binding.references) {
                if (declKeys.contains(ref.start to ref.end)) continue
                val sym = binding.symbols.firstOrNull { it.id == ref.symbolId } ?: continue
                val kind = when (sym.kind) {
                    SymbolKind.Function -> LyngSemanticKind.Function
                    SymbolKind.Class -> LyngSemanticKind.Class
                    SymbolKind.Enum -> LyngSemanticKind.Enum
                    SymbolKind.TypeAlias -> LyngSemanticKind.TypeAlias
                    SymbolKind.Value -> LyngSemanticKind.Value
                    SymbolKind.Variable -> LyngSemanticKind.Variable
                    SymbolKind.Parameter -> LyngSemanticKind.Parameter
                }
                addRange(ref.start, ref.end, kind)
            }
        }

        return out.sortedBy { it.range.start }
    }

    suspend fun completions(text: String, offset: Int, analysis: LyngAnalysisResult? = null): List<CompletionItem> {
        val mini = analysis?.mini
        val binding = analysis?.binding
        StdlibDocsBootstrap.ensure()
        return CompletionEngineLight.completeSuspend(text, offset, mini, binding)
    }

    fun definitionAt(analysis: LyngAnalysisResult, offset: Int): LyngSymbolTarget? {
        val binding = analysis.binding ?: return null
        val sym = binding.symbols.firstOrNull { offset in it.declStart until it.declEnd }
            ?: binding.references.firstOrNull { offset in it.start until it.end }
                ?.let { ref -> binding.symbols.firstOrNull { it.id == ref.symbolId } }
            ?: return null
        val containerName = sym.containerId?.let { cid -> binding.symbols.firstOrNull { it.id == cid }?.name }
        return LyngSymbolTarget(
            name = sym.name,
            kind = sym.kind,
            range = TextRange(sym.declStart, sym.declEnd),
            containerName = containerName
        )
    }

    fun usagesAt(analysis: LyngAnalysisResult, offset: Int, includeDeclaration: Boolean = false): List<TextRange> {
        val binding = analysis.binding ?: return emptyList()
        val sym = binding.symbols.firstOrNull { offset in it.declStart until it.declEnd }
            ?: binding.references.firstOrNull { offset in it.start until it.end }
                ?.let { ref -> binding.symbols.firstOrNull { it.id == ref.symbolId } }
            ?: return emptyList()
        val ranges = binding.references.filter { it.symbolId == sym.id }.map { TextRange(it.start, it.end) }.toMutableList()
        if (includeDeclaration) ranges.add(TextRange(sym.declStart, sym.declEnd))
        return ranges
    }

    fun docAt(analysis: LyngAnalysisResult, offset: Int): LyngSymbolInfo? {
        StdlibDocsBootstrap.ensure()
        val target = definitionAt(analysis, offset) ?: return null
        val mini = analysis.mini
        val imported = analysis.importedModules
        val name = target.name

        val local = mini?.let { findLocalDecl(it, analysis.text, name, target.range.start) }
        if (local != null) {
            val signature = signatureOf(local.first, local.second)
            return LyngSymbolInfo(target, signature = signature, doc = local.first.doc)
        }

        if (target.containerName != null) {
            val member = DocLookupUtils.resolveMemberWithInheritance(imported, target.containerName, name, mini)
            if (member != null) {
                val signature = signatureOf(member.second, member.first)
                return LyngSymbolInfo(target, signature = signature, doc = member.second.doc)
            }
        }

        for (mod in imported) {
            val decl = BuiltinDocRegistry.docsForModule(mod).firstOrNull { it.name == name }
            if (decl != null) {
                val signature = signatureOf(decl, null)
                return LyngSymbolInfo(target, signature = signature, doc = decl.doc)
            }
        }

        return LyngSymbolInfo(target, signature = null, doc = null)
    }

    suspend fun disassembleSymbol(
        code: String,
        symbol: String,
        importProvider: ImportProvider = Script.defaultImportManager
    ): String {
        val script = Compiler.compile(code.toSource(), importProvider)
        val scope = importProvider.newStdScope(Pos.builtIn)
        script.execute(scope)
        val (container, member) = splitMember(symbol)
        if (member == null) return disassembleFromScope(scope, container)
        val rec = scope.get(container) ?: return "$symbol is not found"
        val cls = rec.value as? ObjClass ?: return "$container is not a class"
        val classScope = cls.classScope ?: return "$container has no class scope"
        return disassembleFromScope(classScope, member)
    }

    private fun posToRange(source: Source, pos: Pos): TextRange {
        val s = source.offsetOf(pos)
        return TextRange(s, (s + 1).coerceAtMost(source.text.length))
    }

    private fun findLocalDecl(mini: MiniScript, text: String, name: String, declStart: Int): Pair<MiniNamedDecl, String?>? {
        val src = mini.range.start.source
        fun matches(p: Pos, len: Int) = src.offsetOf(p).let { s -> s == declStart && len > 0 }

        for (d in mini.declarations) {
            if (d.name == name && matches(d.nameStart, d.name.length)) return d to null
            if (d is MiniClassDecl) {
                d.members.forEach { m ->
                    if (m.name == name && matches(m.nameStart, m.name.length)) return m to d.name
                }
                d.ctorFields.firstOrNull { it.name == name && matches(it.nameStart, it.name.length) }?.let {
                    return DocLookupUtils.toMemberVal(it) to d.name
                }
                d.classFields.firstOrNull { it.name == name && matches(it.nameStart, it.name.length) }?.let {
                    return DocLookupUtils.toMemberVal(it) to d.name
                }
            }
        }
        return null
    }

    private fun signatureOf(decl: MiniNamedDecl, ownerClass: String?): String? {
        val owner = ownerClass?.let { "$it." } ?: ""
        return when (decl) {
            is MiniFunDecl -> {
                val params = decl.params.joinToString(", ") { it.name + typeSuffix(it.type) }
                val ret = typeSuffix(decl.returnType)
                "fun $owner${decl.name}($params)$ret"
            }
            is MiniMemberFunDecl -> {
                val params = decl.params.joinToString(", ") { it.name + typeSuffix(it.type) }
                val ret = typeSuffix(decl.returnType)
                "fun $owner${decl.name}($params)$ret"
            }
            is MiniValDecl -> {
                val kw = if (decl.mutable) "var" else "val"
                "$kw $owner${decl.name}${typeSuffix(decl.type)}"
            }
            is MiniMemberValDecl -> {
                val kw = if (decl.mutable) "var" else "val"
                "$kw $owner${decl.name}${typeSuffix(decl.type)}"
            }
            is MiniClassDecl -> {
                val bases = if (decl.bases.isEmpty()) "" else ": " + decl.bases.joinToString(", ")
                "class ${decl.name}$bases"
            }
            is MiniEnumDecl -> "enum ${decl.name}"
            is MiniTypeAliasDecl -> {
                val tp = if (decl.typeParams.isEmpty()) "" else "<" + decl.typeParams.joinToString(", ") + ">"
                "type ${decl.name}$tp = ${DocLookupUtils.typeOf(decl.target)}"
            }
            is MiniMemberTypeAliasDecl -> {
                val tp = if (decl.typeParams.isEmpty()) "" else "<" + decl.typeParams.joinToString(", ") + ">"
                "type $owner${decl.name}$tp = ${DocLookupUtils.typeOf(decl.target)}"
            }
            else -> null
        }
    }

    private fun typeSuffix(type: MiniTypeRef?): String =
        type?.let { ": ${DocLookupUtils.typeOf(it)}" } ?: ""

    private fun splitMember(symbol: String): Pair<String, String?> {
        val idx = symbol.lastIndexOf('.')
        return if (idx >= 0 && idx + 1 < symbol.length) {
            symbol.substring(0, idx) to symbol.substring(idx + 1)
        } else {
            symbol to null
        }
    }

    private fun disassembleFromScope(scope: Scope, name: String): String {
        val record = scope.get(name) ?: return "$name is not found"
        val stmt = record.value as? net.sergeych.lyng.Statement ?: return "$name is not a compiled body"
        val bytecode = (stmt as? BytecodeStatement)?.bytecodeFunction()
            ?: (stmt as? BytecodeBodyProvider)?.bytecodeBody()?.bytecodeFunction()
            ?: return "$name is not a compiled body"
        return CmdDisassembler.disassemble(bytecode)
    }
}
