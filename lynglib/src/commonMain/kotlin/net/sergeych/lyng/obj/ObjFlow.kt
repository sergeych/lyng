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

package net.sergeych.lyng.obj

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.*
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.type
import net.sergeych.mp_tools.globalLaunch
import kotlin.coroutines.cancellation.CancellationException


class ObjFlowBuilder(val output: SendChannel<Obj>) : Obj() {

    override val objClass get() = type

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        val type = object : ObjClass("FlowBuilder") {}.apply {
            addFnDoc(
                name = "emit",
                doc = "Send a value to the flow consumer. Suspends if back‑pressured; no‑ops after consumer stops.",
                params = listOf(ParamDoc("value", type("lyng.Any"))),
                returns = type("lyng.Void"),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val data = scp.requireOnlyArg<Obj>()
                        try {
                            val channel = scp.thisAs<ObjFlowBuilder>().output
                            if (!channel.isClosedForSend)
                                channel.send(data)
                            else
                                // Flow consumer is no longer collecting; signal producer to stop
                                throw ScriptFlowIsNoMoreCollected()
                        } catch (x: Exception) {
                            // Any failure to send (including closed channel) should gracefully stop the producer.
                            if (x is CancellationException) {
                                // Cancellation is a normal control-flow event
                                throw ScriptFlowIsNoMoreCollected()
                            } else {
                                // Treat other send failures as normal flow termination as well
                                throw ScriptFlowIsNoMoreCollected()
                            }
                        }
                        return ObjVoid
                    }
                }
            )
        }
    }
}

private fun createLyngFlowInput(scope: Scope, producer: Statement): ReceiveChannel<Obj> {
    val channel = Channel<Obj>(Channel.RENDEZVOUS)
    val builder = ObjFlowBuilder(channel)
    val builderScope = scope.createChildScope(newThisObj = builder)
    globalLaunch {
        try {
            producer.execute(builderScope)
        } catch (x: ScriptFlowIsNoMoreCollected) {
            // premature flow closing, OK
        } catch (x: Exception) {
            // Suppress stack traces in background producer to avoid noisy stderr during tests.
            // If needed, consider routing to a logger in the future.
        }
        channel.close()
    }
    return channel
}

class ObjFlow(val producer: Statement, val scope: Scope) : Obj() {

    override val objClass get() = type

    companion object {
        val type = object : ObjClass("Flow", ObjIterable) {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("Flow constructor is not available")
            }
        }.apply {
            addFnDoc(
                name = "iterator",
                doc = "Create a pull‑based iterator over this flow. Each step resumes the producer as needed.",
                returns = TypeGenericDoc(type("lyng.Iterator"), listOf(type("lyng.Any"))),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val objFlow = scp.thisAs<ObjFlow>()
                        return ObjFlowIterator(statement(f = object : ScopeCallable {
                            override suspend fun call(scp2: Scope): Obj {
                                objFlow.producer.execute(
                                    ClosureScope(scp2, objFlow.scope)
                                )
                                return ObjVoid
                            }
                        }))
                    }
                }
            )
        }
    }
}


class ObjFlowIterator(val producer: Statement) : Obj() {

    override val objClass: ObjClass get() = type

    private var channel: ReceiveChannel<Obj>? = null

    private var nextItem: ChannelResult<Obj>? = null

    private var isCancelled = false

    private fun checkNotCancelled(scope: Scope) {
        if (isCancelled)
            scope.raiseIllegalState("iteration is cancelled")
    }

    suspend fun hasNext(scope: Scope): ObjBool {
        checkNotCancelled(scope)
        // cold start:
        if (channel == null) channel = createLyngFlowInput(scope, producer)
        if (nextItem == null) nextItem = channel!!.receiveCatching()
        return ObjBool(nextItem!!.isSuccess)
    }

    suspend fun next(scope: Scope): Obj {
        checkNotCancelled(scope)
        if (hasNext(scope).value == false) scope.raiseIllegalState("iteration is done")
        return nextItem!!.getOrThrow().also { nextItem = null }
    }

    private val access = Mutex()
    suspend fun cancel() {
        access.withLock {
            if (!isCancelled) {
                isCancelled = true
                channel?.cancel()
            }
        }
    }

    companion object {
        val type = object : ObjClass("FlowIterator", ObjIterator) {

        }.apply {
            addFnDoc(
                name = "hasNext",
                doc = "Whether another element is available from the flow.",
                returns = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj = scp.thisAs<ObjFlowIterator>().hasNext(scp).toObj()
                }
            )
            addFnDoc(
                name = "next",
                doc = "Receive the next element from the flow or throw if completed.",
                returns = type("lyng.Any"),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val x = scp.thisAs<ObjFlowIterator>()
                        return x.next(scp)
                    }
                }
            )
            addFnDoc(
                name = "cancelIteration",
                doc = "Stop iteration and cancel the underlying flow producer.",
                returns = type("lyng.Void"),
                moduleName = "lyng.stdlib",
                code = object : ScopeCallable {
                    override suspend fun call(scp: Scope): Obj {
                        val x = scp.thisAs<ObjFlowIterator>()
                        x.cancel()
                        return ObjVoid
                    }
                }
            )
        }
    }
}
