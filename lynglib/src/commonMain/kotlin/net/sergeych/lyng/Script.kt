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
import net.sergeych.lyng.bridge.bind
import net.sergeych.lyng.bridge.bindObject
import net.sergeych.lyng.bytecode.CmdFunction
import net.sergeych.lyng.bytecode.CmdVm
import net.sergeych.lyng.bytecode.BytecodeLambdaCallable
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.serialization.ObjJsonClass
import net.sergeych.lyng.serialization.bindSerializationFormat
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.stdlib_included.complexLyng
import net.sergeych.lyng.stdlib_included.decimalLyng
import net.sergeych.lyng.stdlib_included.legacyDigestLyng
import net.sergeych.lyng.stdlib_included.matrixLyng
import net.sergeych.lyng.stdlib_included.observableLyng
import net.sergeych.lyng.stdlib_included.operatorsLyng
import net.sergeych.lyng.stdlib_included.rootLyng
import net.sergeych.lynon.ObjLynonClass
import net.sergeych.mp_tools.globalDefer
import kotlin.math.*
import kotlin.random.Random as KRandom

@Suppress("TYPE_INTERSECTION_AS_REIFIED_WARNING")
class Script(
    override val pos: Pos,
    private val statements: List<Statement> = emptyList(),
    private val moduleSlotPlan: Map<String, Int> = emptyMap(),
    private val moduleDeclaredNames: Set<String> = emptySet(),
    private val importBindings: Map<String, ImportBinding> = emptyMap(),
    private val importedModules: List<ImportBindingSource.Module> = emptyList(),
    private val moduleBytecode: CmdFunction? = null,
//    private val catchReturn: Boolean = false,
) : Statement() {
    fun statements(): List<Statement> = statements

    /** Compiler-resolved metadata for top-level function declarations in source order. */
    fun resolvedFunctionMetadata(): List<ResolvedFunctionMetadata> =
        statements.filterIsInstance<FunctionDeclStatement>().map { it.spec.resolvedMetadata }

    /**
     * Explicitly apply this script's import/module bindings to [scope] without executing the script.
     * This is intended for embedding scenarios where the host owns scope lifecycle and wants
     * script-specific imports to be a separate, opt-in preparation step.
     */
    suspend fun importInto(scope: Scope, seedScope: Scope = scope): Scope {
        prepareScriptScope(scope, seedScope)
        return scope
    }

    /**
     * Create a fresh raw module scope and apply this script's import/module bindings to it.
     * If [seedScope] is provided, its import provider is reused and seed-bound imports resolve from it.
     */
    suspend fun instantiateModule(seedScope: Scope? = null, pos: Pos = this.pos): ModuleScope {
        val provider = seedScope?.currentImportProvider ?: defaultImportManager
        val module = provider.newModuleAt(pos)
        prepareScriptScope(module, seedScope ?: module)
        return module
    }

    override suspend fun execute(scope: Scope): Obj {
        scope.pos = pos
        val execScope = resolveModuleScope(scope) ?: scope
        val isModuleScope = execScope is ModuleScope
        val shouldSeedModule = isModuleScope || execScope.thisObj === ObjVoid
        val moduleTarget = (execScope as? ModuleScope) ?: execScope.parent as? ModuleScope ?: execScope
        if (shouldSeedModule) {
            seedModuleSlots(moduleTarget, scope)
        }
        moduleBytecode?.let { fn ->
            if (execScope is ModuleScope) {
                execScope.ensureModuleFrame(fn)
            }
            var execFrame: net.sergeych.lyng.bytecode.CmdFrame? = null
            val result = CmdVm().execute(fn, execScope, scope.args) { frame, _ ->
                execFrame = frame
                seedModuleLocals(frame, moduleTarget, scope)
            }
            if (execScope !is ModuleScope) {
                execFrame?.let { syncFrameLocalsToScope(it, execScope) }
            }
            return result
        }
        if (statements.isNotEmpty()) {
            scope.raiseIllegalState("bytecode-only execution is required; missing module bytecode")
        }
        return ObjVoid
    }

    private suspend fun prepareScriptScope(scope: Scope, seedScope: Scope) {
        if (importBindings.isNotEmpty() || importedModules.isNotEmpty()) {
            seedImportBindings(scope, seedScope)
        }
        if (moduleSlotPlan.isNotEmpty()) {
            installModuleSlotPlan(scope)
        }
    }

    private suspend fun seedModuleSlots(scope: Scope, seedScope: Scope) {
        if (importBindings.isEmpty() && importedModules.isEmpty()) return
        seedImportBindings(scope, seedScope)
        if (moduleSlotPlan.isNotEmpty()) {
            installModuleSlotPlan(scope)
        }
    }

    private fun installModuleSlotPlan(scope: Scope) {
        for ((name, index) in moduleSlotPlan) {
            if (scope.getSlotIndexOf(name) != null) continue
            if (scope.hasSlotPlanConflict(mapOf(name to index))) {
                val record = scope.objects[name]
                    ?: scope.localBindings[name]
                    ?: ObjRecord(ObjUnset, isMutable = true)
                scope.allocateSlotFor(name, record)
            } else {
                scope.applySlotPlan(mapOf(name to index))
            }
        }
        for (name in moduleSlotPlan.keys) {
            val record = scope.objects[name] ?: scope.localBindings[name] ?: continue
            scope.updateSlotFor(name, record)
        }
    }

    private suspend fun seedModuleLocals(
        frame: net.sergeych.lyng.bytecode.CmdFrame,
        scope: Scope,
        seedScope: Scope
    ) {
        val localNames = frame.fn.localSlotNames
        if (localNames.isEmpty()) return
        val values = HashMap<String, Obj>()
        val base = frame.fn.scopeSlotCount
        for (i in localNames.indices) {
            val name = localNames[i] ?: continue
            if (moduleDeclaredNames.contains(name)) continue
            val record = seedScope.getLocalRecordDirect(name) ?: findSeedRecord(seedScope, name) ?: continue
            val value = if (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property) {
                scope.resolve(record, name)
            } else {
                val raw = record.value
                when (raw) {
                    is FrameSlotRef -> {
                        if (raw.refersTo(frame.frame, i)) {
                            raw.peekValue() ?: continue
                        } else if (seedScope !is ModuleScope) {
                            raw
                        } else {
                            raw.read()
                        }
                    }
                    is RecordSlotRef -> {
                        if (seedScope !is ModuleScope) raw else raw.read()
                    }
                    else -> raw
                }
            }
            frame.setObjUnchecked(base + i, value)
        }
    }

    private fun syncFrameLocalsToScope(frame: net.sergeych.lyng.bytecode.CmdFrame, scope: Scope) {
        val localNames = frame.fn.localSlotNames
        if (localNames.isEmpty()) return
        for (i in localNames.indices) {
            val name = localNames[i] ?: continue
            val record = scope.getLocalRecordDirect(name) ?: scope.localBindings[name] ?: scope.objects[name] ?: continue
            val value = frame.readLocalObj(i)
            when (val current = record.value) {
                is FrameSlotRef -> current.write(value)
                is RecordSlotRef -> current.write(value)
                else -> record.value = value
            }
            scope.updateSlotFor(name, record)
        }
    }

    private fun importedBindingRecord(record: ObjRecord, source: Scope): ObjRecord =
        record.copy(importedFrom = source)

    private suspend fun seedImportBindings(scope: Scope, seedScope: Scope) {
        val provider = scope.currentImportProvider
        val importedModules = LinkedHashSet<ModuleScope>()
        for (moduleRef in this.importedModules) {
            importedModules.add(provider.prepareImport(moduleRef.pos, moduleRef.name, null))
        }
        for (module in importedModules) {
            module.importInto(scope, null)
        }
        for ((name, binding) in importBindings) {
            val sourceScope: Scope
            val baseRecord = when (val source = binding.source) {
                is ImportBindingSource.Module -> {
                    val module = provider.prepareImport(source.pos, source.name, null)
                    importedModules.add(module)
                    sourceScope = module
                    module.objects[binding.symbol]?.takeIf { it.visibility.isPublic }
                        ?: scope.raiseSymbolNotFound("symbol ${source.name}.${binding.symbol} not found")
                }
                ImportBindingSource.Root -> {
                    sourceScope = provider.rootScope
                    provider.rootScope.objects[binding.symbol]?.takeIf { it.visibility.isPublic }
                        ?: scope.raiseSymbolNotFound("symbol ${binding.symbol} not found")
                }
                ImportBindingSource.Seed -> {
                    sourceScope = seedScope
                    findSeedRecord(seedScope, binding.symbol)
                        ?: scope.raiseSymbolNotFound("symbol ${binding.symbol} not found")
                }
            }
            val record = importedBindingRecord(baseRecord, sourceScope)
            if (name == "Exception" && record.value !is ObjClass) {
                scope.updateSlotFor(name, ObjRecord(ObjException.Root, isMutable = false, importedFrom = sourceScope))
            } else {
                scope.updateSlotFor(name, record)
            }
        }
        for (module in importedModules) {
            for ((cls, map) in module.extensions) {
                for ((symbol, record) in map) {
                    if (record.visibility.isPublic && record.importedFrom == null) {
                        scope.addExtension(cls, symbol, importedBindingRecord(record, module))
                    }
                }
            }
        }
        if (scope is ModuleScope) {
            scope.importedModules = importedModules.toList()
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
        return scope as? ModuleScope
    }

    internal fun debugStatements(): List<Statement> = statements

    suspend fun execute() = execute(
        defaultImportManager.newStdScope()
    )

    companion object {

        private suspend fun ScopeFacade.numberToDouble(value: Obj): Double =
            ObjDecimalSupport.toDoubleOrNull(value) ?: value.toDouble()

        private suspend fun ScopeFacade.decimalAwareUnaryMath(
            value: Obj,
            exactDecimal: (suspend ScopeFacade.(Obj) -> Obj?)? = null,
            fallback: (Double) -> Double
        ): Obj {
            exactDecimal?.let { exact ->
                exact(value)?.let { return it }
            }
            if (ObjDecimalSupport.isDecimalValue(value)) {
                return ObjDecimalSupport.fromRealLike(this, value, fallback(numberToDouble(value)))
                    ?: raiseIllegalState("failed to convert Real result back to Decimal")
            }
            return ObjReal(fallback(numberToDouble(value)))
        }

        private suspend fun ScopeFacade.decimalAwareRoundLike(
            value: Obj,
            exactDecimal: suspend ScopeFacade.(Obj) -> Obj?,
            realFallback: (Double) -> Double
        ): Obj {
            if (value is ObjInt) return value
            exactDecimal(value)?.let { return it }
            return ObjReal(realFallback(numberToDouble(value)))
        }

        private suspend fun ScopeFacade.decimalAwarePow(base: Obj, exponent: Obj): Obj {
            ObjDecimalSupport.exactPow(this, base, exponent)?.let { return it }
            if (ObjDecimalSupport.isDecimalValue(base) || ObjDecimalSupport.isDecimalValue(exponent)) {
                return ObjDecimalSupport.fromRealLike(this, base, numberToDouble(base).pow(numberToDouble(exponent)))
                    ?: ObjDecimalSupport.fromRealLike(this, exponent, numberToDouble(base).pow(numberToDouble(exponent)))
                    ?: raiseIllegalState("failed to convert Real pow result back to Decimal")
            }
            return ObjReal(numberToDouble(base).pow(numberToDouble(exponent)))
        }

        /**
         * Create new scope using a standard safe set of modules, using [defaultImportManager]. It is
         * suspended as first time invocation requires compilation of standard library or other
         * asynchronous initialization.
         */
        suspend fun newScope(pos: Pos = Pos.builtIn) = defaultImportManager.newStdScope(pos)

        internal val rootScope: Scope = Scope(null).apply {
            ObjException.addExceptionsToContext(this)
            addConst("Unset", ObjUnset)
            addFn("print") {
                for ((i, a) in args.withIndex()) {
                    if (i > 0) print(' ' + toStringOf(a).value)
                    else print(toStringOf(a).value)
                }
                ObjVoid
            }
            addFn("println") {
                for ((i, a) in args.withIndex()) {
                    if (i > 0) print(' ' + toStringOf(a).value)
                    else print(toStringOf(a).value)
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
                call(callee, rest)
            }
            addFn("floor") {
                val x = args.firstAndOnly()
                decimalAwareRoundLike(x, ObjDecimalSupport::exactFloor, ::floor)
            }
            addFn("ceil") {
                val x = args.firstAndOnly()
                decimalAwareRoundLike(x, ObjDecimalSupport::exactCeil, ::ceil)
            }
            addFn("round") {
                val x = args.firstAndOnly()
                decimalAwareRoundLike(x, ObjDecimalSupport::exactRound, ::round)
            }

            addFn("sin") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::sin)
            }
            addFn("cos") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::cos)
            }
            addFn("tan") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::tan)
            }
            addFn("asin") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::asin)
            }
            addFn("acos") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::acos)
            }
            addFn("atan") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::atan)
            }

            addFn("sinh") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::sinh)
            }
            addFn("cosh") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::cosh)
            }
            addFn("tanh") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::tanh)
            }
            addFn("asinh") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::asinh)
            }
            addFn("acosh") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::acosh)
            }
            addFn("atanh") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::atanh)
            }

            addFn("exp") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::exp)
            }
            addFn("ln") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::ln)
            }

            addFn("log10") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::log10)
            }

            addFn("log2") {
                decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::log2)
            }

            addFn("pow") {
                requireExactCount(2)
                decimalAwarePow(args[0], args[1])
            }
            addItem(
                "sqrt",
                false,
                ObjExternCallable.fromBridge {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::sqrt)
                },
                recordType = ObjRecord.Type.Fun,
                typeDecl = TypeDecl.Function(
                    receiver = null,
                    params = listOf(TypeDecl.TypeAny),
                    returnType = TypeDecl.Simple("Real", false),
                    nullable = false
                )
            )
            addFn("abs") {
                val x = args.firstAndOnly()
                if (x is ObjInt) ObjInt(x.value.absoluteValue)
                else decimalAwareUnaryMath(x, ObjDecimalSupport::exactAbs) { it.absoluteValue }
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
                    if (result.compareTo(requireScope(), range.start) < 0) {
                        result = range.start
                    }
                }
                if (range.end != null && !range.end.isNull) {
                    val cmp = range.end.compareTo(requireScope(), result)
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
                    ": " + toStringOf(call(args[1])).value
                else ""
                if (!cond.value == true)
                    raiseAssertionFailed("Assertion failed$message")
            }

            fun unwrapCompareArg(value: Obj): Obj {
                val resolved = when (value) {
                    is FrameSlotRef -> value.read()
                    is RecordSlotRef -> value.read()
                    else -> value
                }
                return if (resolved === ObjUnset.objClass) ObjUnset else resolved
            }

            addVoidFn("assertEquals") {
                val a = unwrapCompareArg(requiredArg(0))
                val b = unwrapCompareArg(requiredArg(1))
                if (a.compareTo(requireScope(), b) != 0) {
                    raiseAssertionFailed("Assertion failed: ${inspect(a)} == ${inspect(b)}")
                }
            }
            // alias used in tests
            addVoidFn("assertEqual") {
                val a = unwrapCompareArg(requiredArg(0))
                val b = unwrapCompareArg(requiredArg(1))
                if (a.compareTo(requireScope(), b) != 0)
                    raiseAssertionFailed("Assertion failed: ${inspect(a)} == ${inspect(b)}")
            }
            addVoidFn("assertNotEquals") {
                val a = unwrapCompareArg(requiredArg(0))
                val b = unwrapCompareArg(requiredArg(1))
                if (a.compareTo(requireScope(), b) == 0)
                    raiseAssertionFailed("Assertion failed: ${inspect(a)} != ${inspect(b)}")
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
                val code: Obj
                val expectedClass: ObjClass?
                when (args.size) {
                    1 -> {
                        code = requiredArg<Obj>(0)
                        expectedClass = null
                    }

                    2 -> {
                        code = requiredArg<Obj>(1)
                        expectedClass = requiredArg<ObjClass>(0)
                    }

                    else -> raiseIllegalArgument("Expected 1 or 2 arguments, got ${args.size}")
                }
                val result = try {
                    call(code)
                    null
                } catch (e: ExecutionError) {
                    e.errorObject
                } catch (_: ScriptError) {
                    ObjNull
                }
                if (result == null) raiseAssertionFailed("Expected exception but nothing was thrown")
                expectedClass?.let {
                    if (!result.isInstanceOf(it)) {
                        val actual = if (result is ObjException) result.exceptionClass else result.objClass
                        raiseError("Expected $it, got $actual")
                    }
                }
                result
            }

            addFn("dynamic", callSignature = CallSignature(tailBlockReceiverType = "DelegateContext")) {
                ObjDynamic.create(requireScope(), requireOnlyArg())
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
                    if (message is Obj && message.objClass == Statement.type) message = call(message)
                    raiseIllegalArgument(message?.toString() ?: "requirement not met")
                }
                ObjVoid
            }
            addFn("check") {
                val condition = requiredArg<ObjBool>(0)
                if (!condition.value) {
                    var message = args.list.getOrNull(1)
                    if (message is Obj && message.objClass == Statement.type) message = call(message)
                    raiseIllegalState(message?.toString() ?: "check failed")
                }
                ObjVoid
            }
            addFn("traceScope") {
                trace(args.getOrNull(0)?.toString() ?: "")
                ObjVoid
            }
            addFn("run") {
                call(requireOnlyArg())
            }
            addFn("cached") {
                val builder = requireOnlyArg<Obj>()
                val capturedScope = this
                var calculated = false
                var cachedValue: Obj = ObjVoid
                net.sergeych.lyng.obj.ObjExternCallable.fromBridge {
                    if (!calculated) {
                        cachedValue = capturedScope.call(builder)
                        calculated = true
                    }
                    cachedValue
                }
            }
            addVoidFn("delay") {
                val a = args.firstAndOnly()
                when (a) {
                    is ObjInt -> delay(a.value)
                    is ObjReal -> delay((a.value * 1000).roundToLong())
                    is ObjDuration -> delay(a.duration)
                    else -> raiseIllegalArgument("Expected Int, Real or Duration, got ${inspect(a)}")
                }
            }

            addConst("Object", rootObjectType)
            addConst("Real", ObjReal.type)
            addConst("String", ObjString.type)
            addConst("Int", ObjInt.type)
            addConst("Bool", ObjBool.type)
            addConst("Char", ObjChar.type)
            addConst("List", ObjList.type)
            addConst("ImmutableList", ObjImmutableList.type)
            addConst("Set", ObjSet.type)
            addConst("ImmutableSet", ObjImmutableSet.type)
            addConst("Range", ObjRange.type)
            addConst("Map", ObjMap.type)
            addConst("ImmutableMap", ObjImmutableMap.type)
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
            addConst("Channel", ObjChannel.type)
            addConst("Flow", ObjFlow.type)
            addConst("FlowBuilder", ObjFlowBuilder.type)
            addConst("Delegate", ObjDynamic.type)
            addConst("DelegateContext", ObjDynamicContext.type)

            addConst("Regex", ObjRegex.type)
            addConst("RegexMatch", ObjRegexMatch.type)
            addConst("MapEntry", ObjMapEntry.type)

            addFn("launch") {
                val rawCallable = requireOnlyArg<Obj>()
                val currentScope = requireScope()
                // Freeze non-module lexical state at launch time so each coroutine gets
                // its own view of loop locals and other transient frame values.
                val captured = if (currentScope is ModuleScope) currentScope else currentScope.snapshotForClosure()
                val callable = (rawCallable as? BytecodeLambdaCallable)?.freezeForLaunch(captured) ?: rawCallable
                val session = EvalSession.currentOrNull()
                val deferred = if (session != null) {
                    session.launchTrackedDeferred {
                        ScopeBridge(captured).call(callable)
                    }
                } else {
                    globalDefer {
                        ScopeBridge(captured).call(callable)
                    }
                }
                ObjDeferred(deferred)
            }

            addFn("yield") {
                yield()
                ObjVoid
            }

            addFn("flow", callSignature = CallSignature(tailBlockReceiverType = "FlowBuilder")) {
                // important is: current context contains closure often used in call;
                // we'll need it for the producer
                ObjFlow(requireOnlyArg<Obj>(), requireScope(), EvalSession.currentOrNull())
            }

            val pi = ObjReal(PI)
            addConstDoc(
                name = "π",
                value = pi,
                doc = "The mathematical constant pi (π).",
                type = type("lyng.Real")
            )
            getOrCreateNamespace("Math").apply {
                fun ensureFn(name: String, fn: suspend ScopeFacade.() -> Obj) {
                    if (members.containsKey(name)) return
                    addFn(name, code = fn)
                }
                addConstDoc(
                    name = "PI",
                    value = pi,
                    doc = "The mathematical constant pi (π) in the Math namespace.",
                    type = type("lyng.Real")
                )
                ensureFn("floor") {
                    val x = args.firstAndOnly()
                    decimalAwareRoundLike(x, ObjDecimalSupport::exactFloor, ::floor)
                }
                ensureFn("ceil") {
                    val x = args.firstAndOnly()
                    decimalAwareRoundLike(x, ObjDecimalSupport::exactCeil, ::ceil)
                }
                ensureFn("round") {
                    val x = args.firstAndOnly()
                    decimalAwareRoundLike(x, ObjDecimalSupport::exactRound, ::round)
                }
                ensureFn("sin") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::sin)
                }
                ensureFn("cos") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::cos)
                }
                ensureFn("tan") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::tan)
                }
                ensureFn("asin") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::asin)
                }
                ensureFn("acos") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::acos)
                }
                ensureFn("atan") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::atan)
                }
                ensureFn("sinh") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::sinh)
                }
                ensureFn("cosh") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::cosh)
                }
                ensureFn("tanh") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::tanh)
                }
                ensureFn("asinh") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::asinh)
                }
                ensureFn("acosh") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::acosh)
                }
                ensureFn("atanh") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::atanh)
                }
                ensureFn("exp") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::exp)
                }
                ensureFn("ln") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::ln)
                }
                ensureFn("log10") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::log10)
                }
                ensureFn("log2") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::log2)
                }
                ensureFn("pow") {
                    requireExactCount(2)
                    decimalAwarePow(args[0], args[1])
                }
                ensureFn("sqrt") {
                    decimalAwareUnaryMath(args.firstAndOnly(), fallback = ::sqrt)
                }
                ensureFn("abs") {
                    val x = args.firstAndOnly()
                    if (x is ObjInt) ObjInt(x.value.absoluteValue)
                    else decimalAwareUnaryMath(x, ObjDecimalSupport::exactAbs) { it.absoluteValue }
                }
            }
        }

        private fun seededRandomFromThis(scope: Scope, thisObj: Obj): KRandom {
            val instance = thisObj as? ObjInstance
                ?: scope.raiseIllegalState("SeededRandom method requires instance receiver")
            val stored = instance.kotlinInstanceData
            if (stored is KRandom) return stored
            return KRandom.Default.also { instance.kotlinInstanceData = it }
        }

        private suspend fun sampleRangeValue(scope: Scope, random: KRandom, range: ObjRange): Obj {
            if (range.start == null || range.start.isNull || range.end == null || range.end.isNull) {
                scope.raiseIllegalArgument("Random.next(range) requires a finite range")
            }
            val start = range.start
            val end = range.end

            // Real ranges without explicit step are sampled continuously.
            if (!range.hasExplicitStep &&
                start is Numeric &&
                end is Numeric &&
                (start !is ObjInt || end !is ObjInt)
            ) {
                val from = start.doubleValue
                val to = end.doubleValue
                if (from > to || (!range.isEndInclusive && from == to)) {
                    scope.raiseIllegalArgument("Random.next(range) got an empty numeric range")
                }
                if (from == to) return ObjReal(from)
                val upperExclusive = if (range.isEndInclusive) to.nextUp() else to
                if (upperExclusive <= from) {
                    scope.raiseIllegalArgument("Random.next(range) got an empty numeric range")
                }
                return ObjReal(random.nextDouble(from, upperExclusive))
            }

            // Discrete sampling for stepped ranges and integer/char ranges.
            var picked: Obj? = null
            var count = 0L
            range.enumerate(scope) { value ->
                count += 1
                if (random.nextLong(count) == 0L) {
                    picked = value
                }
                true
            }
            if (count <= 0L || picked == null) {
                scope.raiseIllegalArgument("Random.next(range) got an empty range")
            }
            return picked
        }

        val defaultImportManager: ImportManager by lazy {
            ImportManager(rootScope, SecurityManager.allowAll).apply {
                addPackage("lyng.stdlib") { module ->
                    module.eval(Source("lyng.stdlib", rootLyng))
                    ObjKotlinIterator.bindTo(module.requireClass("KotlinIterator"))
                    val seededRandomClass = module.requireClass("SeededRandom")
                    module.bind("SeededRandom") {
                        init { data = KRandom.Default }
                        addFun("nextInt") {
                            val rnd = seededRandomFromThis(requireScope(), thisObj)
                            ObjInt.of(rnd.nextInt().toLong())
                        }
                        addFun("nextFloat") {
                            val rnd = seededRandomFromThis(requireScope(), thisObj)
                            ObjReal(rnd.nextDouble())
                        }
                        addFun("next") {
                            val rnd = seededRandomFromThis(requireScope(), thisObj)
                            val range = requiredArg<ObjRange>(0)
                            sampleRangeValue(requireScope(), rnd, range)
                        }
                    }
                    module.bindObject("Random") {
                        addFun("nextInt") {
                            ObjInt.of(KRandom.Default.nextInt().toLong())
                        }
                        addFun("nextFloat") {
                            ObjReal(KRandom.Default.nextDouble())
                        }
                        addFun("next") {
                            val range = requiredArg<ObjRange>(0)
                            sampleRangeValue(requireScope(), KRandom.Default, range)
                        }
                        addFun("seeded") {
                            val seed = requiredArg<ObjInt>(0).value.toInt()
                            val instance = call(seededRandomClass) as? ObjInstance
                                ?: requireScope().raiseIllegalState("SeededRandom() did not return an object instance")
                            instance.kotlinInstanceData = KRandom(seed)
                            instance
                        }
                    }
                }
                addPackage("lyng.observable") { module ->
                    module.addConst("Observable", ObjObservable)
                    module.addConst("Subscription", ObjSubscription.type)
                    module.addConst("ListChange", ObjListChange.type)
                    module.addConst("ListSet", ObjListSetChange.type)
                    module.addConst("ListInsert", ObjListInsertChange.type)
                    module.addConst("ListRemove", ObjListRemoveChange.type)
                    module.addConst("ListClear", ObjListClearChange.type)
                    module.addConst("ListReorder", ObjListReorderChange.type)
                    module.addConst("ObservableList", ObjObservableList.type)
                    module.addConst("ChangeRejectionException", ObjChangeRejectionExceptionClass)
                    module.eval(Source("lyng.observable", observableLyng))
                }
                addPackage("lyng.operators") { module ->
                    module.eval(Source("lyng.operators", operatorsLyng))
                    module.bindObject("OperatorInterop") {
                        addFun("register") {
                            val leftClass = requiredArg<ObjClass>(0)
                            val rightClass = requiredArg<ObjClass>(1)
                            val commonClass = requiredArg<ObjClass>(2)
                            val operators = requiredArg<ObjList>(3).list.map { value ->
                                val entry = value as? ObjEnumEntry
                                    ?: requireScope().raiseIllegalArgument(
                                        "OperatorInterop.register expects BinaryOperator enum entries"
                                    )
                                entry.name.value
                            }
                            OperatorInteropRegistry.register(
                                leftClass = leftClass,
                                rightClass = rightClass,
                                commonClass = commonClass,
                                operatorNames = operators,
                                leftToCommon = args[4],
                                rightToCommon = args[5]
                            )
                            ObjVoid
                        }
                    }
                }
                addPackage("lyng.decimal") { module ->
                    module.eval(Source("lyng.decimal", decimalLyng))
                    ObjDecimalSupport.bindTo(module)
                }
                addPackage("lyng.matrix") { module ->
                    module.eval(Source("lyng.matrix", matrixLyng))
                    ObjMatrixSupport.bindTo(module)
                }
                addPackage("lyng.complex") { module ->
                    module.eval(Source("lyng.complex", complexLyng))
                    ObjComplexSupport.bindTo(module)
                }
                addPackage("lyng.legacy_digest") { module ->
                    module.eval(Source("lyng.legacy_digest", legacyDigestLyng))
                    module.bindObject("LegacyDigest") {
                        addFun("sha1") {
                            val data = requiredArg<Obj>(0)
                            val bytes = when (data) {
                                is ObjBuffer -> data.byteArray.toByteArray()
                                else -> data.toString().encodeToByteArray()
                            }
                            ObjString(LegacyDigest.sha1Hex(bytes))
                        }
                    }
                }
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
                    it.bindSerializationFormat(
                        ObjLynonClass,
                        doc = "Lynon serialization utilities: encode/decode data structures to a portable binary format."
                    )
                    it.bindSerializationFormat(
                        ObjJsonClass,
                        doc = "Universal JSON serialization utilities with bidirectional Lyng object support."
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
                        name = "Date",
                        value = ObjDate.type,
                        doc = "Calendar date without time-of-day or time zone.",
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
                        doc = "Suspend for the given time. Accepts Duration, Int milliseconds, or Real seconds."
                    ) {
                        val a = args.firstAndOnly()
                        when (a) {
                            is ObjInt -> delay(a.value)
                            is ObjReal -> delay((a.value * 1000).roundToLong())
                            is ObjDuration -> delay(a.duration)
                            else -> raiseIllegalArgument("Expected Duration, Int or Real, got ${inspect(a)}")
                        }
                    }
                }
            }

        }

    }
}
