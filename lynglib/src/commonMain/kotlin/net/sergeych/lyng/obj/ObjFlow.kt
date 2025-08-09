package net.sergeych.lyng.obj

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.*
import net.sergeych.mp_tools.globalLaunch
import kotlin.coroutines.cancellation.CancellationException


class ObjFlowBuilder(val output: SendChannel<Obj>) : Obj() {

    override val objClass = type

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        val type = object : ObjClass("FlowBuilder") {}.apply {
            addFn("emit") {
                val data = requireOnlyArg<Obj>()
                try {
                    val channel = thisAs<ObjFlowBuilder>().output
                    if( !channel.isClosedForSend )
                        channel.send(data)
                    else
                        throw ScriptFlowIsNoMoreCollected()
                } catch (x: Exception) {
                    if( x !is CancellationException )
                        x.printStackTrace()
                    throw ScriptFlowIsNoMoreCollected()
                }
                ObjVoid
            }
        }
    }
}

private fun createLyngFlowInput(scope: Scope, producer: Statement): ReceiveChannel<Obj> {
    val channel = Channel<Obj>(Channel.RENDEZVOUS)
    val builder = ObjFlowBuilder(channel)
    val builderScope = scope.copy(newThisObj = builder)
    globalLaunch {
        try {
            producer.execute(builderScope)
        }
        catch(x: ScriptFlowIsNoMoreCollected) {
            x.printStackTrace()
            // premature flow closing, OK
        }
        catch(x: Exception) {
            x.printStackTrace()
        }
        channel.close()
    }
    return channel
}

class ObjFlow(val producer: Statement, val scope: Scope) : Obj() {

    override val objClass = type

    companion object {
        val type = object : ObjClass("Flow", ObjIterable) {
            override suspend fun callOn(scope: Scope): Obj {
                scope.raiseError("Flow constructor is not available")
            }
        }.apply {
            addFn("iterator") {
                val objFlow = thisAs<ObjFlow>()
                ObjFlowIterator( statement { objFlow.producer.execute(ClosureScope(this,objFlow.scope)) } )
            }
        }
    }
}


class ObjFlowIterator(val producer: Statement) : Obj() {

    override val objClass: ObjClass = type

    private var channel: ReceiveChannel<Obj>? = null

    private var nextItem: ChannelResult<Obj>? = null

    private var isCancelled = false

    private fun checkNotCancelled(scope: Scope) {
        if( isCancelled )
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
            addFn("hasNext") {
                thisAs<ObjFlowIterator>().hasNext(this).toObj()
            }
            addFn("next") {
                val x = thisAs<ObjFlowIterator>()
                x.next(this)
            }
            addFn("cancelIteration") {
                val x = thisAs<ObjFlowIterator>()
                x.cancel()
                ObjVoid
            }
        }
    }
}
