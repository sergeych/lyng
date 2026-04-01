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

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.obj.Obj
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Host-managed lifetime owner for coroutines started from Lyng scripts.
 *
 * The session reuses a single [Scope] across eval calls and tracks async work launched by
 * `launch { ... }` and active flow producers created from these evals.
 */
class EvalSession(initialScope: Scope? = null) {
    private val ownerJob = SupervisorJob()
    private val evalMutex = Mutex()
    private val scopeMutex = Mutex()
    private val activeJobs = MutableStateFlow(0)

    private var _scope: Scope? = initialScope
    val scope: Scope? get() = _scope

    val isActive: Boolean get() = ownerJob.isActive
    val isCancelled: Boolean get() = ownerJob.isCancelled

    suspend fun getScope(): Scope = ensureScope()

    suspend fun eval(code: String): Obj = eval(code.toSource())

    suspend fun eval(source: Source): Obj = evalMutex.withLock {
        throwIfCancelled()
        val scope = ensureScope()
        withEvalSession(this@EvalSession) {
            scope.eval(source)
        }
    }

    fun cancel(cause: String? = null) {
        ownerJob.cancel(ScriptSessionCancelled(cause ?: "EvalSession cancelled"))
    }

    suspend fun join() {
        activeJobs.filter { it == 0 }.first()
    }

    suspend fun cancelAndJoin() {
        cancel()
        join()
    }

    internal suspend fun <T> launchTrackedDeferred(block: suspend CoroutineScope.() -> T): Deferred<T> {
        throwIfCancelled()
        val deferred = CoroutineScope(currentTrackedCoroutineContext()).async(block = block)
        track(deferred)
        return deferred
    }

    internal suspend fun launchTrackedJob(block: suspend CoroutineScope.() -> Unit): Job {
        throwIfCancelled()
        val job = CoroutineScope(currentTrackedCoroutineContext()).launch(block = block)
        track(job)
        return job
    }

    private suspend fun currentTrackedCoroutineContext(): CoroutineContext {
        val base = currentCoroutineContext()
        return base.minusKey(Job) + ownerJob + EvalSessionElement(this)
    }

    private suspend fun ensureScope(): Scope {
        _scope?.let { return it }
        return scopeMutex.withLock {
            _scope ?: Script.newScope().also { _scope = it }
        }
    }

    private fun track(job: Job) {
        activeJobs.update { it + 1 }
        job.invokeOnCompletion {
            activeJobs.update { count -> if (count > 0) count - 1 else 0 }
        }
    }

    internal fun throwIfCancelled() {
        if (ownerJob.isCancelled) {
            throw ScriptSessionCancelled("EvalSession cancelled")
        }
    }

    companion object {
        suspend fun currentOrNull(): EvalSession? = currentCoroutineContext()[EvalSessionElement]?.session
    }
}

internal class EvalSessionElement(val session: EvalSession) :
    AbstractCoroutineContextElement(EvalSessionElement) {
    companion object Key : CoroutineContext.Key<EvalSessionElement>
}

internal suspend fun <T> withEvalSession(session: EvalSession, block: suspend () -> T): T {
    return kotlinx.coroutines.withContext(EvalSessionElement(session)) {
        block()
    }
}

class ScriptSessionCancelled(message: String) : CancellationException(message)
