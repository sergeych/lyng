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

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import net.sergeych.lyng.Script.Companion.defaultImportManager
import net.sergeych.lyng.bytecode.CmdFunction
import net.sergeych.lyng.bytecode.CmdVm
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.stdlib_included.rootLyng
import net.sergeych.lynon.ObjLynonClass
import net.sergeych.mp_tools.globalDefer
import kotlin.math.*

@Suppress("TYPE_INTERSECTION_AS_REIFIED_WARNING")
class Script(
    override val pos: Pos,
    private val statements: List<Statement> = emptyList(),
    private val moduleSlotPlan: Map<String, Int> = emptyMap(),
    private val importBindings: Map<String, ImportBinding> = emptyMap(),
    private val importedModules: List<ImportBindingSource.Module> = emptyList(),
    private val moduleBytecode: CmdFunction? = null,
//    private val catchReturn: Boolean = false,
) : Statement() {

    override suspend fun execute(scope: Scope): Obj {
        scope.pos = pos
        val isModuleScope = scope is ModuleScope
        val shouldSeedModule = isModuleScope || scope.thisObj === ObjVoid
        val moduleTarget = scope
        if (moduleSlotPlan.isNotEmpty() && shouldSeedModule) {
            val hasPlanMapping = moduleSlotPlan.keys.any { moduleTarget.getSlotIndexOf(it) != null }
            val needsReset = moduleTarget is ModuleScope ||
                moduleTarget.slotCount() == 0 ||
                moduleTarget.hasSlotPlanConflict(moduleSlotPlan) ||
                (!hasPlanMapping && moduleTarget.slotCount() > 0)
            if (needsReset) {
                val preserved = LinkedHashMap<String, ObjRecord>()
                for (name in moduleSlotPlan.keys) {
                    moduleTarget.getLocalRecordDirect(name)?.let { preserved[name] = it }
                }
                moduleTarget.applySlotPlanReset(moduleSlotPlan, preserved)
                for (name in moduleSlotPlan.keys) {
                    if (preserved.containsKey(name)) continue
                    val inherited = findSeedRecord(moduleTarget.parent, name)
                    if (inherited != null && inherited.value !== ObjUnset) {
                        moduleTarget.updateSlotFor(name, inherited)
                    }
                }
            } else {
                moduleTarget.applySlotPlan(moduleSlotPlan)
                for (name in moduleSlotPlan.keys) {
                    val local = moduleTarget.getLocalRecordDirect(name)
                    if (local != null && local.value !== ObjUnset) {
                        moduleTarget.updateSlotFor(name, local)
                        continue
                    }
                    val inherited = findSeedRecord(moduleTarget.parent, name)
                    if (inherited != null && inherited.value !== ObjUnset) {
                        moduleTarget.updateSlotFor(name, inherited)
                    }
                }
            }
        }
        if (shouldSeedModule) {
            seedModuleSlots(moduleTarget)
        }
        moduleBytecode?.let { fn ->
            return CmdVm().execute(fn, scope, scope.args)
        }
        var lastResult: Obj = ObjVoid
        for (s in statements) {
            lastResult = s.execute(scope)
        }
        return lastResult
    }

    private suspend fun seedModuleSlots(scope: Scope) {
        if (importBindings.isEmpty() && importedModules.isEmpty()) return
        seedImportBindings(scope)
    }

    private suspend fun seedImportBindings(scope: Scope) {
        val provider = scope.currentImportProvider
        val importedModules = LinkedHashSet<ModuleScope>()
        for (moduleRef in this.importedModules) {
            importedModules.add(provider.prepareImport(moduleRef.pos, moduleRef.name, null))
        }
        if (scope is ModuleScope) {
            scope.importedModules = importedModules.toList()
        }
        for ((name, binding) in importBindings) {
            val record = when (val source = binding.source) {
                is ImportBindingSource.Module -> {
                    val module = provider.prepareImport(source.pos, source.name, null)
                    importedModules.add(module)
                    module.objects[binding.symbol]?.takeIf { it.visibility.isPublic }
                        ?: scope.raiseSymbolNotFound("symbol ${source.name}.${binding.symbol} not found")
                }
                ImportBindingSource.Root -> {
                    provider.rootScope.objects[binding.symbol]?.takeIf { it.visibility.isPublic }
                        ?: scope.raiseSymbolNotFound("symbol ${binding.symbol} not found")
                }
                ImportBindingSource.Seed -> {
                    findSeedRecord(scope, binding.symbol)
                        ?: scope.raiseSymbolNotFound("symbol ${binding.symbol} not found")
                }
            }
            if (name == "Exception" && record.value !is ObjClass) {
                scope.updateSlotFor(name, ObjRecord(ObjException.Root, isMutable = false))
            } else {
                scope.updateSlotFor(name, record)
            }
        }
        for (module in importedModules) {
            for ((cls, map) in module.extensions) {
                for ((symbol, record) in map) {
                    if (record.visibility.isPublic) {
                        scope.addExtension(cls, symbol, record)
                    }
                }
            }
        }
    }

    private fun findSeedRecord(scope: Scope?, name: String): ObjRecord? {
        var s = scope
        var hops = 0
        while (s != null && hops++ < 1024) {
            s.objects[name]?.let { return it }
            s.localBindings[name]?.let { return it }
            s.getSlotIndexOf(name)?.let { idx ->
                val rec = s.getSlotRecord(idx)
                if (rec.value !== ObjUnset) return rec
            }
            s = s.parent
        }
        return null
    }

    private fun resolveModuleScope(scope: Scope): ModuleScope? {
        var current: Scope? = scope
        while (current != null) {
            if (current is ModuleScope) return current
            current = current.parent
        }
        return null
    }

    internal fun debugStatements(): List<Statement> = statements

    suspend fun execute() = execute(
        defaultImportManager.newStdScope()
    )

    companion object {

        /**
         * Create new scope using standard safe set of modules, using [defaultImportManager]. It is
         * suspended as first time calls requires compilation of standard library or other
         * asynchronous initialization.
         */
        suspend fun newScope(pos: Pos = Pos.builtIn) = defaultImportManager.newStdScope(pos)

        internal val rootScope: Scope = Scope(null).apply {
            ObjException.addExceptionsToContext(this)
            addConst("Unset", ObjUnset)
            addFn("print") {
                for ((i, a) in args.withIndex()) {
                    if (i > 0) print(' ' + a.toString(this).value)
                    else print(a.toString(this).value)
                }
                ObjVoid
            }
            addFn("println") {
                for ((i, a) in args.withIndex()) {
                    if (i > 0) print(' ' + a.toString(this).value)
                    else print(a.toString(this).value)
                }
                println()
                ObjVoid
            }
            addFn("call") {
                val callee = args.list.firstOrNull()
                    ?: raiseError("call requires a callable as the first argument")
                val rest = if (args.list.size > 1) {
                    Arguments(args.list.drop(1))
                } else {
                    Arguments.EMPTY
                }
                callee.callOn(createChildScope(pos, args = rest))
            }
            addFn("floor") {
                val x = args.firstAndOnly()
                (if (x is ObjInt) x
                else ObjReal(floor(x.toDouble())))
            }
            addFn("ceil") {
                val x = args.firstAndOnly()
                (if (x is ObjInt) x
                else ObjReal(ceil(x.toDouble())))
            }
            addFn("round") {
                val x = args.firstAndOnly()
                (if (x is ObjInt) x
                else ObjReal(round(x.toDouble())))
            }

            addFn("sin") {
                ObjReal(sin(args.firstAndOnly().toDouble()))
            }
            addFn("cos") {
                ObjReal(cos(args.firstAndOnly().toDouble()))
            }
            addFn("tan") {
                ObjReal(tan(args.firstAndOnly().toDouble()))
            }
            addFn("asin") {
                ObjReal(asin(args.firstAndOnly().toDouble()))
            }
            addFn("acos") {
                ObjReal(acos(args.firstAndOnly().toDouble()))
            }
            addFn("atan") {
                ObjReal(atan(args.firstAndOnly().toDouble()))
            }

            addFn("sinh") {
                ObjReal(sinh(args.firstAndOnly().toDouble()))
            }
            addFn("cosh") {
                ObjReal(cosh(args.firstAndOnly().toDouble()))
            }
            addFn("tanh") {
                ObjReal(tanh(args.firstAndOnly().toDouble()))
            }
            addFn("asinh") {
                ObjReal(asinh(args.firstAndOnly().toDouble()))
            }
            addFn("acosh") {
                ObjReal(acosh(args.firstAndOnly().toDouble()))
            }
            addFn("atanh") {
                ObjReal(atanh(args.firstAndOnly().toDouble()))
            }

            addFn("exp") {
                ObjReal(exp(args.firstAndOnly().toDouble()))
            }
            addFn("ln") {
                ObjReal(ln(args.firstAndOnly().toDouble()))
            }

            addFn("log10") {
                ObjReal(log10(args.firstAndOnly().toDouble()))
            }

            addFn("log2") {
                ObjReal(log2(args.firstAndOnly().toDouble()))
            }

            addFn("pow") {
                requireExactCount(2)
                ObjReal(
                    (args[0].toDouble()).pow(args[1].toDouble())
                )
            }
            addFn("sqrt") {
                ObjReal(
                    sqrt(args.firstAndOnly().toDouble())
                )
            }
            addFn("abs") {
                val x = args.firstAndOnly()
                if (x is ObjInt) ObjInt(x.value.absoluteValue) else ObjReal(x.toDouble().absoluteValue)
            }

            addFnDoc(
                "clamp",
                doc = "Clamps the value within the specified range. If the value is outside the range, it is set to the nearest boundary. Respects inclusive/exclusive range ends.",
                params = listOf(ParamDoc("value"), ParamDoc("range")),
                moduleName = "lyng.stdlib"
            ) {
                val value = requiredArg<Obj>(0)
                val range = requiredArg<ObjRange>(1)
                
                var result = value
                if (range.start != null && !range.start.isNull) {
                    if (result.compareTo(this, range.start) < 0) {
                        result = range.start
                    }
                }
                if (range.end != null && !range.end.isNull) {
                    val cmp = range.end.compareTo(this, result)
                    if (range.isEndInclusive) {
                        if (cmp < 0) result = range.end
                    } else {
                        if (cmp <= 0) {
                            if (range.end is ObjInt) {
                                result = ObjInt.of(range.end.value - 1)
                            } else if (range.end is ObjChar) {
                                result = ObjChar((range.end.value.code - 1).toChar())
                            } else {
                                // For types where we can't easily find "previous" value (like Real),
                                // we just return the exclusive boundary as a fallback.
                                result = range.end
                            }
                        }
                    }
                }
                result
            }

            addVoidFn("assert") {
                val cond = requiredArg<ObjBool>(0)
                val message = if (args.size > 1)
                    ": " + (args[1] as Statement).execute(this).toString(this).value
                else ""
                if (!cond.value == true)
                    raiseError(ObjAssertionFailedException(this, "Assertion failed$message"))
            }

            addVoidFn("assertEquals") {
                val a = requiredArg<Obj>(0)
                val b = requiredArg<Obj>(1)
                if (a.compareTo(this, b) != 0)
                    raiseError(
                        ObjAssertionFailedException(
                            this,
                            "Assertion failed: ${a.inspect(this)} == ${b.inspect(this)}"
                        )
                    )
            }
            // alias used in tests
            addVoidFn("assertEqual") {
                val a = requiredArg<Obj>(0)
                val b = requiredArg<Obj>(1)
                if (a.compareTo(this, b) != 0)
                    raiseError(
                        ObjAssertionFailedException(
                            this,
                            "Assertion failed: ${a.inspect(this)} == ${b.inspect(this)}"
                        )
                    )
            }
            addVoidFn("assertNotEquals") {
                val a = requiredArg<Obj>(0)
                val b = requiredArg<Obj>(1)
                if (a.compareTo(this, b) == 0)
                    raiseError(
                        ObjAssertionFailedException(
                            this,
                            "Assertion failed: ${a.inspect(this)} != ${b.inspect(this)}"
                        )
                    )
            }
            addFnDoc(
                "assertThrows",
                doc = """
                    Asserts that the provided code block throws an exception, with or without exception: 
                    ```lyng
                        assertThrows { /* ode */ }
                        assertThrows(IllegalArgumentException) { /* code */ }
                    ```
                    If an expected exception class is provided,
                    it checks that the thrown exception is of that class. If no expected class is provided, any exception
                    will be accepted.
                """.trimIndent()
            ) {
                val code: Statement
                val expectedClass: ObjClass?
                when (args.size) {
                    1 -> {
                        code = requiredArg<Statement>(0)
                        expectedClass = null
                    }

                    2 -> {
                        code = requiredArg<Statement>(1)
                        expectedClass = requiredArg<ObjClass>(0)
                    }

                    else -> raiseIllegalArgument("Expected 1 or 2 arguments, got ${args.size}")
                }
                val result = try {
                    code.execute(this)
                    null
                } catch (e: ExecutionError) {
                    e.errorObject
                } catch (_: ScriptError) {
                    ObjNull
                }
                if (result == null) raiseError(
                    ObjAssertionFailedException(
                        this,
                        "Expected exception but nothing was thrown"
                    )
                )
                expectedClass?.let {
                    if (!result.isInstanceOf(it)) {
                        val actual = if (result is ObjException) result.exceptionClass else result.objClass
                        raiseError("Expected $it, got $actual")
                    }
                }
                result
            }

            addFn("dynamic", callSignature = CallSignature(tailBlockReceiverType = "DelegateContext")) {
                ObjDynamic.create(this, requireOnlyArg())
            }

            val root = this
            val mathClass = ObjClass("Math").apply {
                addFn("sqrt") {
                    ObjReal(sqrt(args.firstAndOnly().toDouble()))
                }
            }
            addItem("Math", false, ObjInstance(mathClass).apply {
                instanceScope = Scope(root, thisObj = this)
            })

            addFn("require") {
                val condition = requiredArg<ObjBool>(0)
                if (!condition.value) {
                    var message = args.list.getOrNull(1)
                    if (message is Statement) message = message.execute(this)
                    raiseIllegalArgument(message?.toString() ?: "requirement not met")
                }
                ObjVoid
            }
            addFn("check") {
                val condition = requiredArg<ObjBool>(0)
                if (!condition.value) {
                    var message = args.list.getOrNull(1)
                    if (message is Statement) message = message.execute(this)
                    raiseIllegalState(message?.toString() ?: "check failed")
                }
                ObjVoid
            }
            addFn("traceScope") {
                this.trace(args.getOrNull(0)?.toString() ?: "")
                ObjVoid
            }
            addFn("run") {
                requireOnlyArg<Statement>().execute(this)
            }
            addFn("cached") {
                val builder = requireOnlyArg<Statement>()
                val capturedScope = this
                var calculated = false
                var cachedValue: Obj = ObjVoid
                val thunk = object : Statement() {
                    override val pos: Pos = Pos.builtIn
                    override suspend fun execute(scope: Scope): Obj {
                        if (!calculated) {
                            cachedValue = builder.execute(capturedScope)
                            calculated = true
                        }
                        return cachedValue
                    }
                }
                thunk
            }
            addFn("lazy") {
                val builder = requireOnlyArg<Statement>()
                ObjLazyDelegate(builder, this)
            }
            addVoidFn("delay") {
                val a = args.firstAndOnly()
                when (a) {
                    is ObjInt -> delay(a.value)
                    is ObjReal -> delay((a.value * 1000).roundToLong())
                    is ObjDuration -> delay(a.duration)
                    else -> raiseIllegalArgument("Expected Int, Real or Duration, got ${a.inspect(this)}")
                }
            }

            addConst("Object", rootObjectType)
            addConst("Real", ObjReal.type)
            addConst("String", ObjString.type)
            addConst("Int", ObjInt.type)
            addConst("Bool", ObjBool.type)
            addConst("Char", ObjChar.type)
            addConst("List", ObjList.type)
            addConst("Set", ObjSet.type)
            addConst("Range", ObjRange.type)
            addConst("Map", ObjMap.type)
            addConst("MapEntry", ObjMapEntry.type)
            @Suppress("RemoveRedundantQualifierName")
            addConst("Callable", Statement.type)
            // interfaces
            addConst("Iterable", ObjIterable)
            addConst("Collection", ObjCollection)
            addConst("Iterator", ObjIterator)
            addConst("Array", ObjArray)
            addConst("RingBuffer", ObjRingBuffer.type)
            addConst("Class", ObjClassType)

            addConst("Deferred", ObjDeferred.type)
            addConst("CompletableDeferred", ObjCompletableDeferred.type)
            addConst("Mutex", ObjMutex.type)
            addConst("Flow", ObjFlow.type)
            addConst("FlowBuilder", ObjFlowBuilder.type)
            addConst("Delegate", ObjDynamic.type)
            addConst("DelegateContext", ObjDynamicContext.type)

            addConst("Regex", ObjRegex.type)
            addConst("RegexMatch", ObjRegexMatch.type)
            addConst("MapEntry", ObjMapEntry.type)

            addFn("launch") {
                val callable = requireOnlyArg<Statement>()
                ObjDeferred(globalDefer {
                    callable.execute(this@addFn)
                })
            }

            addFn("yield") {
                yield()
                ObjVoid
            }

            addFn("flow", callSignature = CallSignature(tailBlockReceiverType = "FlowBuilder")) {
                // important is: current context contains closure often used in call;
                // we'll need it for the producer
                ObjFlow(requireOnlyArg<Statement>(), this)
            }

            val pi = ObjReal(PI)
            addConstDoc(
                name = "π",
                value = pi,
                doc = "The mathematical constant pi (π).",
                type = type("lyng.Real")
            )
            getOrCreateNamespace("Math").apply {
                addConstDoc(
                    name = "PI",
                    value = pi,
                    doc = "The mathematical constant pi (π) in the Math namespace.",
                    type = type("lyng.Real")
                )
            }
        }

        val defaultImportManager: ImportManager by lazy {
            ImportManager(rootScope, SecurityManager.allowAll).apply {
                addTextPackages(
                    rootLyng
                )
                addPackage("lyng.buffer") {
                    it.addConstDoc(
                        name = "Buffer",
                        value = ObjBuffer.type,
                        doc = "Immutable sequence of bytes. Use for binary data and IO.",
                        type = type("lyng.Class")
                    )
                    it.addConstDoc(
                        name = "MutableBuffer",
                        value = ObjMutableBuffer.type,
                        doc = "Mutable byte buffer. Supports in-place modifications.",
                        type = type("lyng.Class")
                    )
                }
                addPackage("lyng.serialization") {
                    it.addConstDoc(
                        name = "Lynon",
                        value = ObjLynonClass,
                        doc = "Lynon serialization utilities: encode/decode data structures to a portable binary/text form.",
                        type = type("lyng.Class")
                    )
                }
                addPackage("lyng.time") {
                    it.addConstDoc(
                        name = "Instant",
                        value = ObjInstant.type,
                        doc = "Point in time (epoch-based).",
                        type = type("lyng.Class")
                    )
                    it.addConstDoc(
                        name = "DateTime",
                        value = ObjDateTime.type,
                        doc = "Point in time in a specific time zone.",
                        type = type("lyng.Class")
                    )
                    it.addConstDoc(
                        name = "Duration",
                        value = ObjDuration.type,
                        doc = "Time duration with millisecond precision.",
                        type = type("lyng.Class")
                    )
                    it.addVoidFnDoc(
                        "delay",
                        doc = "Suspend for the given time. Accepts Duration, Int seconds, or Real seconds."
                    ) {
                        val a = args.firstAndOnly()
                        when (a) {
                            is ObjInt -> delay(a.value * 1000)
                            is ObjReal -> delay((a.value * 1000).roundToLong())
                            is ObjDuration -> delay(a.duration)
                            else -> raiseIllegalArgument("Expected Duration, Int or Real, got ${a.inspect(this)}")
                        }
                    }
                }
            }

        }

    }
}
