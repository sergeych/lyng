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

/*
 * Built-in documentation registry for Kotlin-defined APIs.
 * Stores MiniAst declarations with MiniDoc, no runtime coupling.
 */
package net.sergeych.lyng.miniast

import net.sergeych.lyng.Pos
import net.sergeych.lyng.stdlib_included.rootLyng

// ---------------- Types DSL ----------------

/** Simple param descriptor for docs builder. */
data class ParamDoc(val name: String, val type: TypeDoc? = null)

/** Type documentation model mapped later to MiniTypeRef. */
sealed interface TypeDoc { val nullable: Boolean }

data class TypeNameDoc(val segments: List<String>, override val nullable: Boolean = false) : TypeDoc
data class TypeGenericDoc(val base: TypeNameDoc, val args: List<TypeDoc>, override val nullable: Boolean = false) : TypeDoc
data class TypeFunctionDoc(
    val receiver: TypeDoc? = null,
    val params: List<TypeDoc>,
    val returns: TypeDoc,
    override val nullable: Boolean = false
) : TypeDoc
data class TypeVarDoc(val name: String, override val nullable: Boolean = false) : TypeDoc

// Convenience builders
fun type(name: String, nullable: Boolean = false) = TypeNameDoc(name.split('.'), nullable)
fun typeVar(name: String, nullable: Boolean = false) = TypeVarDoc(name, nullable)
fun funType(params: List<TypeDoc>, returns: TypeDoc, receiver: TypeDoc? = null, nullable: Boolean = false) =
    TypeFunctionDoc(receiver, params, returns, nullable)

// ---------------- Registry ----------------

interface BuiltinDocSource {
    fun docsForModule(moduleName: String): List<MiniDecl>
}

object BuiltinDocRegistry : BuiltinDocSource {
    // Simple storage; populated at init time; reads dominate afterwards.
    private val modules: MutableMap<String, MutableList<MiniDecl>> = mutableMapOf()
    // Optional lazy suppliers to avoid hard init order coupling (e.g., stdlib docs)
    private val lazySuppliers: MutableMap<String, () -> List<MiniDecl>> = mutableMapOf()

    fun module(name: String, init: ModuleDocsBuilder.() -> Unit) {
        val builder = ModuleDocsBuilder(name)
        builder.init()
        val list = modules.getOrPut(name) { mutableListOf() }
        list += builder.build()
    }

    override fun docsForModule(moduleName: String): List<MiniDecl> {
        // If module already present but we also have a lazy supplier for it, merge supplier once
        modules[moduleName]?.let { existing ->
            lazySuppliers.remove(moduleName)?.invoke()?.let { built ->
                existing += built
            }
            return existing
        }
        // Try lazy supplier once when module is not present
        val built = lazySuppliers.remove(moduleName)?.invoke()
        if (built != null) {
            val list = modules.getOrPut(moduleName) { mutableListOf() }
            list += built
            return list
        }
        return emptyList()
    }

    fun clearModule(moduleName: String) { modules.remove(moduleName) }
    fun allModules(): Set<String> = (modules.keys + lazySuppliers.keys).toSet()

    /** Atomically replace a module's docs with freshly built ones. */
    fun moduleReplace(name: String, init: ModuleDocsBuilder.() -> Unit) {
        modules.remove(name)
        module(name, init)
    }

    /** Register a lazy supplier that will be invoked on the first lookup for [name]. */
    fun registerLazy(name: String, supplier: () -> List<MiniDecl>) {
        // do not overwrite if module already present
        if (!modules.containsKey(name)) lazySuppliers[name] = supplier
    }
    // Register built-in lazy seeds
    init {
        registerLazy("lyng.stdlib") { buildStdlibDocs() }
    }

    /**
     * List names of extension members defined for [className] in the stdlib text (`root.lyng`).
     * We do a lightweight regex scan like: `fun ClassName.methodName(` or `val ClassName.propName`
     * and collect distinct names.
     */
    fun extensionMemberNamesFor(className: String): List<String> {
        val src = try { rootLyng } catch (_: Throwable) { null } ?: return emptyList()
        val out = LinkedHashSet<String>()
        // Match lines like: fun String.trim(...) or val Int.isEven = ... (allowing modifiers)
        val re = Regex("^\\s*(?:(?:abstract|override|closed|private|protected|static|open|extern)\\s+)*(?:fun|val|var)\\s+${className}\\.([A-Za-z_][A-Za-z0-9_]*)\\b", RegexOption.MULTILINE)
        re.findAll(src).forEach { m ->
            val name = m.groupValues.getOrNull(1)?.trim()
            if (!name.isNullOrEmpty()) out.add(name)
        }
        return out.toList()
    }

    @Deprecated("Use extensionMemberNamesFor", ReplaceWith("extensionMemberNamesFor(className)"))
    fun extensionMethodNamesFor(className: String): List<String> = extensionMemberNamesFor(className)
}

// ---------------- Builders ----------------

class ModuleDocsBuilder internal constructor(private val moduleName: String) {
    private val decls = mutableListOf<MiniDecl>()

    fun funDoc(
        name: String,
        doc: String,
        params: List<ParamDoc> = emptyList(),
        returns: TypeDoc? = null,
        tags: Map<String, List<String>> = emptyMap(),
    ) {
        val md = miniDoc(doc, tags)
        val mp = params.map { MiniParam(it.name, it.type?.toMiniTypeRef(), Pos.builtIn) }
        val ret = returns?.toMiniTypeRef()
        decls += MiniFunDecl(
            range = builtinRange(),
            name = name,
            params = mp,
            returnType = ret,
            body = null,
            doc = md,
            nameStart = Pos.builtIn,
            isExtern = false,
            isStatic = false
        )
    }

    fun valDoc(
        name: String,
        doc: String,
        type: TypeDoc? = null,
        mutable: Boolean = false,
        tags: Map<String, List<String>> = emptyMap(),
    ) {
        val md = miniDoc(doc, tags)
        decls += MiniValDecl(
            range = builtinRange(),
            name = name,
            mutable = mutable,
            type = type?.toMiniTypeRef(),
            initRange = null,
            doc = md,
            nameStart = Pos.builtIn,
            isExtern = false,
            isStatic = false
        )
    }

    fun classDoc(
        name: String,
        doc: String,
        bases: List<TypeDoc> = emptyList(),
        tags: Map<String, List<String>> = emptyMap(),
        init: ClassDocsBuilder.() -> Unit = {},
    ) {
        val md = miniDoc(doc, tags)
        val cb = ClassDocsBuilder(name)
        cb.init()
        val baseNames = bases.map { it.toDisplayName() }
        decls += MiniClassDecl(
            range = builtinRange(),
            name = name,
            bases = baseNames,
            bodyRange = null,
            ctorFields = emptyList(),
            classFields = emptyList(),
            doc = md,
            nameStart = Pos.builtIn,
            members = cb.build()
        )
    }

    internal fun build(): List<MiniDecl> = decls.toList()
}

class ClassDocsBuilder internal constructor(private val className: String) {
    private val members = mutableListOf<MiniMemberDecl>()

    fun method(
        name: String,
        doc: String,
        params: List<ParamDoc> = emptyList(),
        returns: TypeDoc? = null,
        isStatic: Boolean = false,
        tags: Map<String, List<String>> = emptyMap(),
    ) {
        val md = miniDoc(doc, tags)
        val mp = params.map { MiniParam(it.name, it.type?.toMiniTypeRef(), Pos.builtIn) }
        val ret = returns?.toMiniTypeRef()
        members += MiniMemberFunDecl(
            range = builtinRange(),
            name = name,
            params = mp,
            returnType = ret,
            doc = md,
            nameStart = Pos.builtIn,
            isStatic = isStatic,
        )
    }

    fun field(
        name: String,
        doc: String,
        type: TypeDoc? = null,
        mutable: Boolean = false,
        isStatic: Boolean = false,
        tags: Map<String, List<String>> = emptyMap(),
    ) {
        val md = miniDoc(doc, tags)
        members += MiniMemberValDecl(
            range = builtinRange(),
            name = name,
            mutable = mutable,
            type = type?.toMiniTypeRef(),
            initRange = null,
            doc = md,
            nameStart = Pos.builtIn,
            isStatic = isStatic,
        )
    }

    internal fun build(): List<MiniMemberDecl> = members.toList()
}

// ---------------- Helpers ----------------

private fun builtinRange() = MiniRange(Pos.builtIn, Pos.builtIn)

private fun miniDoc(text: String, tags: Map<String, List<String>>): MiniDoc {
    val summary = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    return MiniDoc(range = builtinRange(), raw = text, summary = summary, tags = tags)
}

private fun TypeDoc.toDisplayName(): String = when (this) {
    is TypeNameDoc -> segments.joinToString(".")
    is TypeGenericDoc -> base.segments.joinToString(".")
    is TypeFunctionDoc -> "(function)"
    is TypeVarDoc -> name
}

internal fun TypeDoc.toMiniTypeRef(): MiniTypeRef = when (this) {
    is TypeNameDoc -> MiniTypeName(
        range = builtinRange(),
        segments = this.segments.map { seg -> MiniTypeName.Segment(seg, builtinRange()) },
        nullable = this.nullable
    )
    is TypeGenericDoc -> MiniGenericType(
        range = builtinRange(),
        base = this.base.toMiniTypeRef(),
        args = this.args.map { it.toMiniTypeRef() },
        nullable = this.nullable
    )
    is TypeFunctionDoc -> MiniFunctionType(
        range = builtinRange(),
        receiver = this.receiver?.toMiniTypeRef(),
        params = this.params.map { it.toMiniTypeRef() },
        returnType = this.returns.toMiniTypeRef(),
        nullable = this.nullable
    )
    is TypeVarDoc -> MiniTypeVar(
        range = builtinRange(),
        name = this.name,
        nullable = this.nullable
    )
}

// ---------------- Built-in module doc seeds ----------------

// ---------------- Inline docs support (.lyng source) ----------------

/**
 * Lightweight, single-pass scanner that extracts inline doc comments from the stdlib .lyng source
 * and associates them with declarations (top-level functions, classes, and class methods).
 * It is intentionally conservative and only recognizes simple patterns present in stdlib sources.
 *
 * The scan is cached; performed at most once per process.
 */
private object StdlibInlineDocIndex {
    private var built = false

    // Keys for matching declaration docs
    private sealed interface Key {
        data class TopFun(val name: String) : Key
        data class Clazz(val name: String) : Key
        data class Method(val className: String, val name: String) : Key
    }

    private val docs: MutableMap<Key, String> = mutableMapOf()

    private fun putIfAbsent(k: Key, doc: String) {
        if (doc.isBlank()) return
        if (!docs.containsKey(k)) docs[k] = doc.trim()
    }

    private fun buildOnce() {
        if (built) return
        built = true
        val text = try { rootLyng } catch (_: Throwable) { null } ?: return

        // Simple line-based scan. Collect a doc block immediately preceding a declaration.
        val lines = text.lines()
        val buf = mutableListOf<String>()
        var inBlock = false
        var prevWasComment = false

        fun flushTo(key: Key) {
            if (buf.isNotEmpty()) {
                val raw = buf.joinToString("\n").trimEnd()
                putIfAbsent(key, raw)
                buf.clear()
            }
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                // Multiline block comment begin/end
                line.startsWith("/*") && !inBlock -> {
                    inBlock = true
                    val inner = line.removePrefix("/*").let { l -> if (l.endsWith("*/")) l.removeSuffix("*/") else l }
                    buf += inner
                    prevWasComment = true
                    if (line.endsWith("*/")) inBlock = false
                    continue
                }
                inBlock -> {
                    val content = if (line.endsWith("*/")) {
                        inBlock = false
                        line.removeSuffix("*/")
                    } else line
                    // Trim leading '*' like Javadoc style
                    val t = content.trimStart()
                    buf += if (t.startsWith("*")) t.removePrefix("*").trimStart() else content
                    prevWasComment = true
                    continue
                }
                line.startsWith("//") -> {
                    buf += line.removePrefix("//")
                    prevWasComment = true
                    continue
                }
                line.isBlank() -> {
                    // Blank line breaks doc association unless it immediately follows a comment
                    if (!prevWasComment) buf.clear()
                    prevWasComment = false
                    continue
                }
                else -> {
                    // Non-comment, non-blank: try to match a declaration just after comments
                    if (buf.isNotEmpty()) {
                        // fun/val/var Class.name( ... ) (allowing modifiers)
                        val mExt = Regex("^(?:(?:abstract|override|closed|private|protected|static|open|extern)\\s+)*(?:fun|val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\b").find(line)
                        if (mExt != null) {
                            val (cls, name) = mExt.destructured
                            flushTo(Key.Method(cls, name))
                        } else {
                            // fun name( ... ) (allowing modifiers)
                            val mTop = Regex("^(?:(?:abstract|override|closed|private|protected|static|open|extern)\\s+)*fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").find(line)
                            if (mTop != null) {
                                val (name) = mTop.destructured
                                flushTo(Key.TopFun(name))
                            } else {
                                // class/interface Name (allowing modifiers)
                                val mClass = Regex("^(?:(?:abstract|private|protected|static|open|extern)\\s+)*(?:class|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b").find(line)
                                if (mClass != null) {
                                    val (name) = mClass.destructured
                                    flushTo(Key.Clazz(name))
                                } else {
                                    // Unrecognized line – drop buffer to avoid leaking to unrelated code
                                    buf.clear()
                                }
                            }
                        }
                    }
                    prevWasComment = false
                }
            }
        }
    }

    // Public API: fetch doc text if present
    fun topFunDoc(name: String): String? { buildOnce(); return docs[Key.TopFun(name)] }
    fun classDoc(name: String): String? { buildOnce(); return docs[Key.Clazz(name)] }
    fun methodDoc(className: String, name: String): String? { buildOnce(); return docs[Key.Method(className, name)] }
}

// Seed docs for lyng.stdlib lazily to avoid init-order coupling and prefer inline docs where present.
private fun buildStdlibDocs(): List<MiniDecl> {
    val decls = mutableListOf<MiniDecl>()
    // Use the same DSL builders to construct decls
    val mod = ModuleDocsBuilder("lyng.stdlib")
    // Printing
    mod.funDoc(
        name = "print",
        doc = StdlibInlineDocIndex.topFunDoc("print") ?: """
            Print values to the standard output without a trailing newline.
            Accepts any number of arguments and prints them separated by a space.
        """.trimIndent(),
        // We keep signature minimal; variadic in Lyng is not modeled in MiniAst yet
        params = listOf(ParamDoc("values"))
    )
    mod.funDoc(
        name = "println",
        doc = StdlibInlineDocIndex.topFunDoc("println") ?: """
            Print values to the standard output and append a newline.
            Accepts any number of arguments and prints them separated by a space.
        """.trimIndent(),
        params = listOf(ParamDoc("values"))
    )
    // Caching helper
    mod.funDoc(
        name = "cached",
        doc = StdlibInlineDocIndex.topFunDoc("cached") ?: """
            Wrap a `builder` into a zero-argument thunk that computes once and caches the result.
            The first call invokes `builder()` and stores the value; subsequent calls return the cached value.
        """.trimIndent(),
        params = listOf(ParamDoc("builder")),
        returns = funType(params = emptyList(), returns = type("lyng.Any"))
    )
    // Math helpers (scalar versions)
    fun math1(name: String) = mod.funDoc(
        name = name,
        doc = StdlibInlineDocIndex.topFunDoc(name) ?: "Compute $name(x).",
        params = listOf(ParamDoc("x", type("lyng.Number")))
    )
    math1("sin"); math1("cos"); math1("tan"); math1("asin"); math1("acos"); math1("atan")
    mod.funDoc(name = "floor", doc = StdlibInlineDocIndex.topFunDoc("floor") ?: "Round down the number to the nearest integer.", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "ceil", doc = StdlibInlineDocIndex.topFunDoc("ceil") ?: "Round up the number to the nearest integer.", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "round", doc = StdlibInlineDocIndex.topFunDoc("round") ?: "Round the number to the nearest integer.", params = listOf(ParamDoc("x", type("lyng.Number"))))

    // Hyperbolic and inverse hyperbolic
    math1("sinh"); math1("cosh"); math1("tanh"); math1("asinh"); math1("acosh"); math1("atanh")

    // Exponentials and logarithms
    mod.funDoc(name = "exp", doc = StdlibInlineDocIndex.topFunDoc("exp") ?: "Euler's exponential e^x.", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "ln", doc = StdlibInlineDocIndex.topFunDoc("ln") ?: "Natural logarithm (base e).", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "log10", doc = StdlibInlineDocIndex.topFunDoc("log10") ?: "Logarithm base 10.", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "log2", doc = StdlibInlineDocIndex.topFunDoc("log2") ?: "Logarithm base 2.", params = listOf(ParamDoc("x", type("lyng.Number"))))

    // Power/roots and absolute value
    mod.funDoc(
        name = "pow",
        doc = StdlibInlineDocIndex.topFunDoc("pow") ?: "Raise `x` to the power `y`.",
        params = listOf(ParamDoc("x", type("lyng.Number")), ParamDoc("y", type("lyng.Number")))
    )
    mod.funDoc(
        name = "sqrt",
        doc = StdlibInlineDocIndex.topFunDoc("sqrt") ?: "Square root of `x`.",
        params = listOf(ParamDoc("x", type("lyng.Number")))
    )
    mod.funDoc(
        name = "abs",
        doc = StdlibInlineDocIndex.topFunDoc("abs") ?: "Absolute value of a number (works for Int and Real).",
        params = listOf(ParamDoc("x", type("lyng.Number")))
    )

    // Assertions and checks
    mod.funDoc(
        name = "assert",
        doc = StdlibInlineDocIndex.topFunDoc("assert") ?: """
            Assert that `cond` is true, otherwise throw an `AssertionFailedException`.
            Optionally provide a `message`.
        """.trimIndent(),
        params = listOf(ParamDoc("cond", type("lyng.Bool")), ParamDoc("message"))
    )
    mod.funDoc(
        name = "assertEquals",
        doc = StdlibInlineDocIndex.topFunDoc("assertEquals") ?: "Assert that `a == b`, otherwise throw an assertion error.",
        params = listOf(ParamDoc("a"), ParamDoc("b"))
    )
    mod.funDoc(
        name = "assertNotEquals",
        doc = StdlibInlineDocIndex.topFunDoc("assertNotEquals") ?: "Assert that `a != b`, otherwise throw an assertion error.",
        params = listOf(ParamDoc("a"), ParamDoc("b"))
    )
    mod.funDoc(
        name = "assertThrows",
        doc = StdlibInlineDocIndex.topFunDoc("assertThrows") ?: """
            Execute `code` and return the thrown `Exception` object.
            If nothing is thrown, an assertion error is raised.
        """.trimIndent(),
        params = listOf(ParamDoc("code")),
        returns = type("lyng.Exception", nullable = true)
    )

    // Utilities
    mod.funDoc(
        name = "dynamic",
        doc = StdlibInlineDocIndex.topFunDoc("dynamic") ?: "Wrap a value into a dynamic object that defers resolution to runtime.",
        params = listOf(ParamDoc("value"))
    )
    mod.funDoc(
        name = "require",
        doc = StdlibInlineDocIndex.topFunDoc("require") ?: "Require `cond` to be true, else throw `IllegalArgumentException` with optional `message`.",
        params = listOf(ParamDoc("cond", type("lyng.Bool")), ParamDoc("message"))
    )
    mod.funDoc(
        name = "check",
        doc = StdlibInlineDocIndex.topFunDoc("check") ?: "Check `cond` is true, else throw `IllegalStateException` with optional `message`.",
        params = listOf(ParamDoc("cond", type("lyng.Bool")), ParamDoc("message"))
    )
    mod.funDoc(
        name = "traceScope",
        doc = StdlibInlineDocIndex.topFunDoc("traceScope") ?: "Print a debug trace of the current scope chain with an optional label.",
        params = listOf(ParamDoc("label", type("lyng.String")))
    )
    mod.funDoc(
        name = "delay",
        doc = StdlibInlineDocIndex.topFunDoc("delay") ?: "Suspend for the specified number of milliseconds.",
        params = listOf(ParamDoc("ms", type("lyng.Number")))
    )

    // Concurrency helpers
    mod.classDoc(name = "Deferred", doc = "Represents a value that will be available in the future.", bases = listOf(type("Obj"))) {
        method(name = "await", doc = "Suspend until the value is available and return it.")
    }
    mod.funDoc(
        name = "launch",
        doc = StdlibInlineDocIndex.topFunDoc("launch") ?: "Launch an asynchronous task and return a `Deferred`.",
        params = listOf(ParamDoc("code")),
        returns = type("lyng.Deferred")
    )
    mod.funDoc(
        name = "yield",
        doc = StdlibInlineDocIndex.topFunDoc("yield") ?: "Yield to the scheduler, allowing other tasks to run."
    )
    mod.funDoc(
        name = "flow",
        doc = StdlibInlineDocIndex.topFunDoc("flow") ?: "Create a lazy iterable stream using the provided `builder`.",
        params = listOf(ParamDoc("builder")),
        returns = type("lyng.Iterable")
    )

    // Common types
    mod.classDoc(name = "Int", doc = "64-bit signed integer.", bases = listOf(type("Obj")))
    mod.classDoc(name = "Real", doc = "64-bit floating point number.", bases = listOf(type("Obj")))
    mod.classDoc(name = "Bool", doc = "Boolean value (true or false).", bases = listOf(type("Obj")))
    mod.classDoc(name = "Char", doc = "Single character (UTF-16 code unit).", bases = listOf(type("Obj")))
    mod.classDoc(name = "Buffer", doc = "Mutable byte array.", bases = listOf(type("Obj")))
    mod.classDoc(name = "Regex", doc = "Regular expression.", bases = listOf(type("Obj")))
    mod.classDoc(name = "Range", doc = "Arithmetic progression.", bases = listOf(type("Obj")))

    // Common Iterable helpers (document top-level extension-like APIs as class members)
    mod.classDoc(name = "Iterable", doc = StdlibInlineDocIndex.classDoc("Iterable") ?: "Helper operations for iterable collections.", bases = listOf(type("Obj"))) {
        fun md(name: String, fallback: String) = StdlibInlineDocIndex.methodDoc("Iterable", name) ?: fallback
        method(name = "filter", doc = md("filter", "Filter elements by predicate."), params = listOf(ParamDoc("predicate")), returns = type("lyng.Iterable"))
        method(name = "filterFlow", doc = md("filterFlow", "Filter elements by predicate and return a Flow."), params = listOf(ParamDoc("predicate")), returns = type("lyng.Flow"))
        method(name = "filterNotNull", doc = md("filterNotNull", "Filter non-null elements."), returns = type("lyng.List"))
        method(name = "drop", doc = md("drop", "Skip the first N elements."), params = listOf(ParamDoc("n", type("lyng.Int"))), returns = type("lyng.Iterable"))
        field(name = "first", doc = md("first", "Return the first element or throw if empty."))
        field(name = "last", doc = md("last", "Return the last element or throw if empty."))
        method(name = "findFirst", doc = md("findFirst", "Return the first matching element or throw."), params = listOf(ParamDoc("predicate")))
        method(name = "findFirstOrNull", doc = md("findFirstOrNull", "Return the first matching element or null."), params = listOf(ParamDoc("predicate")))
        method(name = "dropLast", doc = md("dropLast", "Drop the last N elements."), params = listOf(ParamDoc("n", type("lyng.Int"))), returns = type("lyng.Iterable"))
        method(name = "takeLast", doc = md("takeLast", "Take the last N elements."), params = listOf(ParamDoc("n", type("lyng.Int"))), returns = type("lyng.List"))
        method(name = "joinToString", doc = md("joinToString", "Join elements into a string with an optional separator and transformer."), params = listOf(ParamDoc("separator", type("lyng.String")), ParamDoc("transformer")), returns = type("lyng.String"))
        method(name = "any", doc = md("any", "Return true if any element matches the predicate."), params = listOf(ParamDoc("predicate")), returns = type("lyng.Bool"))
        method(name = "all", doc = md("all", "Return true if all elements match the predicate."), params = listOf(ParamDoc("predicate")), returns = type("lyng.Bool"))
        method(name = "forEach", doc = md("forEach", "Execute `action` for each element."), params = listOf(ParamDoc("action")))
        method(name = "count", doc = md("count", "Count elements matching the predicate."), params = listOf(ParamDoc("predicate")), returns = type("lyng.Int"))
        method(name = "sum", doc = md("sum", "Sum all elements; returns null for empty collections."), returns = type("lyng.Number", nullable = true))
        method(name = "sumOf", doc = md("sumOf", "Sum mapped values of elements; returns null for empty collections."), params = listOf(ParamDoc("f")))
        method(name = "minOf", doc = md("minOf", "Minimum of mapped values."), params = listOf(ParamDoc("lambda")))
        method(name = "maxOf", doc = md("maxOf", "Maximum of mapped values."), params = listOf(ParamDoc("lambda")))
        method(name = "sorted", doc = md("sorted", "Return elements sorted by natural order."), returns = type("lyng.Iterable"))
        method(name = "sortedBy", doc = md("sortedBy", "Return elements sorted by the key selector."), params = listOf(ParamDoc("predicate")), returns = type("lyng.Iterable"))
        method(name = "shuffled", doc = md("shuffled", "Return a shuffled copy as a list."), returns = type("lyng.List"))
        method(name = "map", doc = md("map", "Transform elements by applying `transform`."), params = listOf(ParamDoc("transform")), returns = type("lyng.Iterable"))
        method(name = "toList", doc = md("toList", "Collect elements of this iterable into a new list."), returns = type("lyng.List"))
    }

    // List helpers
    mod.classDoc(name = "List", doc = StdlibInlineDocIndex.classDoc("List") ?: "List-specific operations.", bases = listOf(type("Collection"), type("Iterable"))) {
        fun md(name: String, fallback: String) = StdlibInlineDocIndex.methodDoc("List", name) ?: fallback
        method(name = "toString", doc = md("toString", "Return string representation like [a,b,c]."), returns = type("lyng.String"))
        method(name = "sortBy", doc = md("sortBy", "Sort list in-place by key selector."), params = listOf(ParamDoc("predicate")))
        method(name = "sort", doc = md("sort", "Sort list in-place by natural order."))
        method(name = "toList", doc = md("toList", "Return a shallow copy of this list (new list with the same elements)."), returns = type("lyng.List"))
    }

    // Collection helpers (supertype for sized collections)
    mod.classDoc(name = "Collection", doc = StdlibInlineDocIndex.classDoc("Collection") ?: "Collection operations common to sized collections.", bases = listOf(type("Iterable"))) {
        fun md(name: String, fallback: String) = StdlibInlineDocIndex.methodDoc("Collection", name) ?: fallback
        method(name = "size", doc = md("size", "Number of elements in the collection."), returns = type("lyng.Int"))
        method(name = "toList", doc = md("toList", "Collect elements into a new list."), returns = type("lyng.List"))
    }

    // Iterator helpers
    mod.classDoc(name = "Iterator", doc = StdlibInlineDocIndex.classDoc("Iterator") ?: "Iterator protocol for sequential access.", bases = listOf(type("Obj"))) {
        fun md(name: String, fallback: String) = StdlibInlineDocIndex.methodDoc("Iterator", name) ?: fallback
        method(name = "hasNext", doc = md("hasNext", "Whether another element is available."), returns = type("lyng.Bool"))
        method(name = "next", doc = md("next", "Return the next element."))
        method(name = "cancelIteration", doc = md("cancelIteration", "Stop the iteration early."))
        method(name = "toList", doc = md("toList", "Consume this iterator and collect elements into a list."), returns = type("lyng.List"))
    }

    // Exceptions and utilities
    mod.classDoc(name = "Exception", doc = StdlibInlineDocIndex.classDoc("Exception") ?: "Exception helpers.", bases = listOf(type("Obj"))) {
        method(name = "printStackTrace", doc = StdlibInlineDocIndex.methodDoc("Exception", "printStackTrace") ?: "Print this exception and its stack trace to standard output.")
    }

    mod.classDoc(name = "Enum", doc = StdlibInlineDocIndex.classDoc("Enum") ?: "Base class for all enums.", bases = listOf(type("Obj"))) {
        method(name = "name", doc = "Returns the name of this enum constant.", returns = type("lyng.String"))
        method(name = "ordinal", doc = "Returns the ordinal of this enum constant.", returns = type("lyng.Int"))
    }

    mod.classDoc(name = "String", doc = StdlibInlineDocIndex.classDoc("String") ?: "String helpers.", bases = listOf(type("Obj"))) {
        // Only include inline-source method here; Kotlin-embedded methods are now documented via DocHelpers near definitions.
        method(name = "re", doc = StdlibInlineDocIndex.methodDoc("String", "re") ?: "Compile this string into a regular expression.", params = listOf(ParamDoc("flags", type("lyng.String"))), returns = type("lyng.Regex"))
    }

    // StackTraceEntry structure
    mod.classDoc(name = "StackTraceEntry", doc = StdlibInlineDocIndex.classDoc("StackTraceEntry") ?: "Represents a single stack trace element.", bases = listOf(type("Obj"))) {
        // Fields are not present as declarations in root.lyng's class header docs. Keep seeded defaults.
        field(name = "sourceName", doc = "Source (file) name.", type = type("lyng.String"))
        field(name = "line", doc = "Line number (1-based).", type = type("lyng.Int"))
        field(name = "column", doc = "Column number (0-based).", type = type("lyng.Int"))
        field(name = "sourceString", doc = "The source line text.", type = type("lyng.String"))
        method(name = "toString", doc = StdlibInlineDocIndex.methodDoc("StackTraceEntry", "toString") ?: "Formatted representation: source:line:column: text.", returns = type("lyng.String"))
    }

    // Constants and namespaces
    mod.valDoc(
        name = "π",
        doc = StdlibInlineDocIndex.topFunDoc("π") ?: "The mathematical constant pi.",
        type = type("lyng.Real"),
        mutable = false
    )
    mod.classDoc(name = "Math", doc = "Mathematical constants and helpers.") {
        field(name = "PI", doc = StdlibInlineDocIndex.methodDoc("Math", "PI") ?: "The mathematical constant pi.", type = type("lyng.Real"), isStatic = true)
    }

    decls += mod.build()
    return decls
}

// (Registration for external modules is provided by their own libraries)
