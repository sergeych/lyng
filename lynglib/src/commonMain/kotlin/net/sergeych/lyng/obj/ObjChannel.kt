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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import net.sergeych.lyng.Scope
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type

/**
 * Lyng-visible wrapper around Kotlin's [Channel].
 *
 * Construction:
 *  - `Channel()` – rendezvous channel (capacity 0, sender and receiver synchronise)
 *  - `Channel(n)` – buffered channel with capacity *n* (n > 0)
 *  - `Channel(Channel.UNLIMITED)` – unlimited-buffer channel
 *
 * Methods:
 *  - `send(value)` – suspend until the value is accepted by a receiver (or buffered)
 *  - `receive()` – suspend until a value is available; returns `null` when channel is closed and drained
 *  - `tryReceive()` – return the next value immediately, or `null` if none is available
 *  - `close()` – signal that no more values will be sent; pending receivers can still drain buffered items
 *
 * Properties:
 *  - `isClosedForSend: Bool`
 *  - `isClosedForReceive: Bool`
 */
class ObjChannel(val channel: Channel<Obj>) : Obj() {

    override val objClass get() = type

    companion object {
        val type = object : ObjClass("Channel") {
            override suspend fun callOn(scope: Scope): Obj {
                val capacity = scope.args.list.getOrNull(0)
                    ?.let { (it as? ObjInt)?.value?.toInt() ?: scope.raiseIllegalArgument("Channel capacity must be an integer") }
                    ?: Channel.RENDEZVOUS
                return ObjChannel(Channel(capacity))
            }
        }.apply {
            // Expose Channel.UNLIMITED as a static constant on the Channel class so scripts can write
            // Channel(Channel.UNLIMITED).
            addConst("UNLIMITED", ObjInt(Channel.UNLIMITED.toLong()))

            addFnDoc(
                name = "send",
                doc = "Suspend until the value is accepted by a receiver (or placed into the buffer). " +
                    "Throws if the channel is already closed.",
                params = listOf(ParamDoc("value", type("lyng.Any"))),
                returns = type("lyng.Void"),
                moduleName = "lyng.stdlib"
            ) {
                val value = requiredArg<Obj>(0)
                try {
                    thisAs<ObjChannel>().channel.send(value)
                } catch (e: ClosedSendChannelException) {
                    raiseIllegalState("Channel is closed for send")
                }
                ObjVoid
            }

            addFnDoc(
                name = "receive",
                doc = "Suspend until a value is available and return it, or return `null` when the channel " +
                    "is closed and all buffered items have been consumed.",
                returns = type("lyng.Any"),
                moduleName = "lyng.stdlib"
            ) {
                try {
                    thisAs<ObjChannel>().channel.receive()
                } catch (_: ClosedReceiveChannelException) {
                    ObjNull
                }
            }

            addFnDoc(
                name = "tryReceive",
                doc = "Return the next buffered value immediately without suspending, or `null` if the " +
                    "channel is empty or closed.",
                returns = type("lyng.Any"),
                moduleName = "lyng.stdlib"
            ) {
                val result = thisAs<ObjChannel>().channel.tryReceive()
                result.getOrNull() ?: ObjNull
            }

            addFnDoc(
                name = "close",
                doc = "Signal that no more values will be sent. Receivers can still drain any buffered items.",
                returns = type("lyng.Void"),
                moduleName = "lyng.stdlib"
            ) {
                thisAs<ObjChannel>().channel.close()
                ObjVoid
            }

            addPropertyDoc(
                name = "isClosedForSend",
                doc = "Whether this channel is closed for sending (no more `send` calls are permitted).",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjChannel>().channel.isClosedForSend.toObj() }
            )

            addPropertyDoc(
                name = "isClosedForReceive",
                doc = "Whether this channel is closed for receiving (closed and fully drained).",
                type = type("lyng.Bool"),
                moduleName = "lyng.stdlib",
                getter = { thisAs<ObjChannel>().channel.isClosedForReceive.toObj() }
            )
        }
    }
}
