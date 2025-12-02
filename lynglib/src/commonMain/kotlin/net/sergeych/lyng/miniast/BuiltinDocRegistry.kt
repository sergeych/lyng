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
 * Built-in documentation registry for Kotlin-defined APIs.
 * Stores MiniAst declarations with MiniDoc, no runtime coupling.
 */
package net.sergeych.lyng.miniast

import net.sergeych.lyng.Pos

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
        modules[moduleName]?.let { return it }
        // Try lazy supplier once
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

// Seed docs for lyng.stdlib lazily to avoid init-order coupling.
private fun buildStdlibDocs(): List<MiniDecl> {
    val decls = mutableListOf<MiniDecl>()
    // Use the same DSL builders to construct decls
    val mod = ModuleDocsBuilder("lyng.stdlib")
    // Printing
    mod.funDoc(
        name = "print",
        doc = """
            Print values to the standard output without a trailing newline.
            Accepts any number of arguments and prints them separated by a space.
        """.trimIndent(),
        // We keep signature minimal; variadic in Lyng is not modeled in MiniAst yet
        params = listOf(ParamDoc("values"))
    )
    mod.funDoc(
        name = "println",
        doc = """
            Print values to the standard output and append a newline.
            Accepts any number of arguments and prints them separated by a space.
        """.trimIndent(),
        params = listOf(ParamDoc("values"))
    )
    // Caching helper
    mod.funDoc(
        name = "cached",
        doc = """
            Wrap a `builder` into a zero-argument thunk that computes once and caches the result.
            The first call invokes `builder()` and stores the value; subsequent calls return the cached value.
        """.trimIndent(),
        params = listOf(ParamDoc("builder")),
        returns = funType(params = emptyList(), returns = type("lyng.Any"))
    )
    // Math helpers (scalar versions)
    fun math1(name: String) = mod.funDoc(
        name = name,
        doc = "Compute $name(x).",
        params = listOf(ParamDoc("x", type("lyng.Number")))
    )
    math1("sin"); math1("cos"); math1("tan"); math1("asin"); math1("acos"); math1("atan")
    mod.funDoc(name = "floor", doc = "Round down the number to the nearest integer.", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "ceil", doc = "Round up the number to the nearest integer.", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "round", doc = "Round the number to the nearest integer.", params = listOf(ParamDoc("x", type("lyng.Number"))))

    // Hyperbolic and inverse hyperbolic
    math1("sinh"); math1("cosh"); math1("tanh"); math1("asinh"); math1("acosh"); math1("atanh")

    // Exponentials and logarithms
    mod.funDoc(name = "exp", doc = "Euler's exponential e^x.", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "ln", doc = "Natural logarithm (base e).", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "log10", doc = "Logarithm base 10.", params = listOf(ParamDoc("x", type("lyng.Number"))))
    mod.funDoc(name = "log2", doc = "Logarithm base 2.", params = listOf(ParamDoc("x", type("lyng.Number"))))

    // Power/roots and absolute value
    mod.funDoc(
        name = "pow",
        doc = "Raise `x` to the power `y`.",
        params = listOf(ParamDoc("x", type("lyng.Number")), ParamDoc("y", type("lyng.Number")))
    )
    mod.funDoc(
        name = "sqrt",
        doc = "Square root of `x`.",
        params = listOf(ParamDoc("x", type("lyng.Number")))
    )
    mod.funDoc(
        name = "abs",
        doc = "Absolute value of a number (works for Int and Real).",
        params = listOf(ParamDoc("x", type("lyng.Number")))
    )

    // Assertions and checks
    mod.funDoc(
        name = "assert",
        doc = """
            Assert that `cond` is true, otherwise throw an `AssertionFailedException`.
            Optionally provide a `message`.
        """.trimIndent(),
        params = listOf(ParamDoc("cond", type("lyng.Bool")), ParamDoc("message"))
    )
    mod.funDoc(
        name = "assertEquals",
        doc = "Assert that `a == b`, otherwise throw an assertion error.",
        params = listOf(ParamDoc("a"), ParamDoc("b"))
    )
    mod.funDoc(
        name = "assertNotEquals",
        doc = "Assert that `a != b`, otherwise throw an assertion error.",
        params = listOf(ParamDoc("a"), ParamDoc("b"))
    )
    mod.funDoc(
        name = "assertThrows",
        doc = """
            Execute `code` and return the thrown `Exception` object.
            If nothing is thrown, an assertion error is raised.
        """.trimIndent(),
        params = listOf(ParamDoc("code")),
        returns = type("lyng.Exception", nullable = true)
    )

    // Utilities
    mod.funDoc(
        name = "dynamic",
        doc = "Wrap a value into a dynamic object that defers resolution to runtime.",
        params = listOf(ParamDoc("value"))
    )
    mod.funDoc(
        name = "require",
        doc = "Require `cond` to be true, else throw `IllegalArgumentException` with optional `message`.",
        params = listOf(ParamDoc("cond", type("lyng.Bool")), ParamDoc("message"))
    )
    mod.funDoc(
        name = "check",
        doc = "Check `cond` is true, else throw `IllegalStateException` with optional `message`.",
        params = listOf(ParamDoc("cond", type("lyng.Bool")), ParamDoc("message"))
    )
    mod.funDoc(
        name = "traceScope",
        doc = "Print a debug trace of the current scope chain with an optional label.",
        params = listOf(ParamDoc("label", type("lyng.String")))
    )
    mod.funDoc(
        name = "delay",
        doc = "Suspend for the specified number of milliseconds.",
        params = listOf(ParamDoc("ms", type("lyng.Number")))
    )

    // Concurrency helpers
    mod.funDoc(
        name = "launch",
        doc = "Launch an asynchronous task and return a `Deferred`.",
        params = listOf(ParamDoc("code")),
        returns = type("lyng.Deferred")
    )
    mod.funDoc(
        name = "yield",
        doc = "Yield to the scheduler, allowing other tasks to run."
    )
    mod.funDoc(
        name = "flow",
        doc = "Create a lazy iterable stream using the provided `builder`.",
        params = listOf(ParamDoc("builder")),
        returns = type("lyng.Iterable")
    )

    // Common Iterable helpers (document top-level extension-like APIs as class members)
    mod.classDoc(name = "Iterable", doc = "Helper operations for iterable collections.") {
        method(name = "filter", doc = "Filter elements by predicate.", params = listOf(ParamDoc("predicate")), returns = type("lyng.Iterable"))
        method(name = "drop", doc = "Skip the first N elements.", params = listOf(ParamDoc("n", type("lyng.Int"))), returns = type("lyng.Iterable"))
        method(name = "first", doc = "Return the first element or throw if empty.")
        method(name = "last", doc = "Return the last element or throw if empty.")
        method(name = "dropLast", doc = "Drop the last N elements.", params = listOf(ParamDoc("n", type("lyng.Int"))), returns = type("lyng.Iterable"))
        method(name = "takeLast", doc = "Take the last N elements.", params = listOf(ParamDoc("n", type("lyng.Int"))), returns = type("lyng.List"))
        method(name = "joinToString", doc = "Join elements into a string with an optional separator and transformer.", params = listOf(ParamDoc("prefix", type("lyng.String")), ParamDoc("transformer")), returns = type("lyng.String"))
        method(name = "any", doc = "Return true if any element matches the predicate.", params = listOf(ParamDoc("predicate")), returns = type("lyng.Bool"))
        method(name = "all", doc = "Return true if all elements match the predicate.", params = listOf(ParamDoc("predicate")), returns = type("lyng.Bool"))
        method(name = "sum", doc = "Sum all elements; returns null for empty collections.", returns = type("lyng.Number", nullable = true))
        method(name = "sumOf", doc = "Sum mapped values of elements; returns null for empty collections.", params = listOf(ParamDoc("f")))
        method(name = "minOf", doc = "Minimum of mapped values.", params = listOf(ParamDoc("lambda")))
        method(name = "maxOf", doc = "Maximum of mapped values.", params = listOf(ParamDoc("lambda")))
        method(name = "sorted", doc = "Return elements sorted by natural order.", returns = type("lyng.Iterable"))
        method(name = "sortedBy", doc = "Return elements sorted by the key selector.", params = listOf(ParamDoc("predicate")), returns = type("lyng.Iterable"))
        method(name = "shuffled", doc = "Return a shuffled copy as a list.", returns = type("lyng.List"))
        method(name = "map", doc = "Transform elements by applying `transform`.", params = listOf(ParamDoc("transform")), returns = type("lyng.Iterable"))
        method(name = "toList", doc = "Collect elements of this iterable into a new list.", returns = type("lyng.List"))
    }

    // List helpers
    mod.classDoc(name = "List", doc = "List-specific operations.", bases = listOf(type("Collection"), type("Iterable"))) {
        method(name = "toString", doc = "Return string representation like [a,b,c].", returns = type("lyng.String"))
        method(name = "sortBy", doc = "Sort list in-place by key selector.", params = listOf(ParamDoc("predicate")))
        method(name = "sort", doc = "Sort list in-place by natural order.")
        method(name = "toList", doc = "Return a shallow copy of this list (new list with the same elements).", returns = type("lyng.List"))
    }

    // Collection helpers (supertype for sized collections)
    mod.classDoc(name = "Collection", doc = "Collection operations common to sized collections.", bases = listOf(type("Iterable"))) {
        method(name = "size", doc = "Number of elements in the collection.", returns = type("lyng.Int"))
        method(name = "toList", doc = "Collect elements into a new list.", returns = type("lyng.List"))
    }

    // Iterator helpers
    mod.classDoc(name = "Iterator", doc = "Iterator protocol for sequential access.") {
        method(name = "hasNext", doc = "Whether another element is available.", returns = type("lyng.Bool"))
        method(name = "next", doc = "Return the next element.")
        method(name = "cancelIteration", doc = "Stop the iteration early.")
        method(name = "toList", doc = "Consume this iterator and collect elements into a list.", returns = type("lyng.List"))
    }

    // Exceptions and utilities
    mod.classDoc(name = "Exception", doc = "Exception helpers.") {
        method(name = "printStackTrace", doc = "Print this exception and its stack trace to standard output.")
    }

    mod.classDoc(name = "String", doc = "String helpers.") {
        method(name = "re", doc = "Compile this string into a regular expression.", returns = type("lyng.Regex"))
    }

    // StackTraceEntry structure
    mod.classDoc(name = "StackTraceEntry", doc = "Represents a single stack trace element.") {
        field(name = "sourceName", doc = "Source (file) name.", type = type("lyng.String"))
        field(name = "line", doc = "Line number (1-based).", type = type("lyng.Int"))
        field(name = "column", doc = "Column number (0-based).", type = type("lyng.Int"))
        field(name = "sourceString", doc = "The source line text.", type = type("lyng.String"))
        method(name = "toString", doc = "Formatted representation: source:line:column: text.", returns = type("lyng.String"))
    }

    // Constants and namespaces
    mod.valDoc(
        name = "π",
        doc = "The mathematical constant pi.",
        type = type("lyng.Real"),
        mutable = false
    )
    mod.classDoc(name = "Math", doc = "Mathematical constants and helpers.") {
        field(name = "PI", doc = "The mathematical constant pi.", type = type("lyng.Real"), isStatic = true)
    }

    decls += mod.build()
    return decls
}

// (Registration is triggered from BuiltinDocRegistry.init)
