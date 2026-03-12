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
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lyng.asFacade
import net.sergeych.lyng.miniast.ParamDoc
import net.sergeych.lyng.miniast.TypeGenericDoc
import net.sergeych.lyng.miniast.addFnDoc
import net.sergeych.lyng.miniast.addPropertyDoc
import net.sergeych.lyng.miniast.type

val ObjObservable = ObjClass("Observable").apply {
    addFn("beforeChange") { raiseNotImplemented("Observable.beforeChange") }
    addFn("onChange") { raiseNotImplemented("Observable.onChange") }
    addFn("changes") { raiseNotImplemented("Observable.changes") }
}

val ObjChangeRejectionExceptionClass = ObjException.Companion.ExceptionClass("ChangeRejectionException", ObjException.Root)

open class ObjListChange : Obj() {
    override val objClass: ObjClass get() = type

    companion object {
        val type = ObjClass("ListChange")
    }
}

class ObjListSetChange(val index: Int, val oldValue: Obj, val newValue: Obj) : ObjListChange() {
    override val objClass: ObjClass get() = type

    companion object {
        val type = ObjClass("ListSet", ObjListChange.type).apply {
            addPropertyDoc("index", "Changed index.", type("lyng.Int"), moduleName = "lyng.observable", getter = { thisAs<ObjListSetChange>().index.toObj() })
            addPropertyDoc("oldValue", "Value before assignment.", type("lyng.Any"), moduleName = "lyng.observable", getter = { thisAs<ObjListSetChange>().oldValue })
            addPropertyDoc("newValue", "Assigned value.", type("lyng.Any"), moduleName = "lyng.observable", getter = { thisAs<ObjListSetChange>().newValue })
        }
    }
}

class ObjListInsertChange(val index: Int, val values: ObjList) : ObjListChange() {
    override val objClass: ObjClass get() = type

    companion object {
        val type = ObjClass("ListInsert", ObjListChange.type).apply {
            addPropertyDoc("index", "Insertion index.", type("lyng.Int"), moduleName = "lyng.observable", getter = { thisAs<ObjListInsertChange>().index.toObj() })
            addPropertyDoc(
                "values",
                "Inserted values.",
                TypeGenericDoc(type("lyng.List"), listOf(type("lyng.Any"))),
                moduleName = "lyng.observable",
                getter = { thisAs<ObjListInsertChange>().values }
            )
        }
    }
}

class ObjListRemoveChange(val index: Int, val oldValue: Obj) : ObjListChange() {
    override val objClass: ObjClass get() = type

    companion object {
        val type = ObjClass("ListRemove", ObjListChange.type).apply {
            addPropertyDoc("index", "Removed index.", type("lyng.Int"), moduleName = "lyng.observable", getter = { thisAs<ObjListRemoveChange>().index.toObj() })
            addPropertyDoc("oldValue", "Removed value.", type("lyng.Any"), moduleName = "lyng.observable", getter = { thisAs<ObjListRemoveChange>().oldValue })
        }
    }
}

class ObjListClearChange(val oldValues: ObjList) : ObjListChange() {
    override val objClass: ObjClass get() = type

    companion object {
        val type = ObjClass("ListClear", ObjListChange.type).apply {
            addPropertyDoc(
                "oldValues",
                "All list values before clear.",
                TypeGenericDoc(type("lyng.List"), listOf(type("lyng.Any"))),
                moduleName = "lyng.observable",
                getter = { thisAs<ObjListClearChange>().oldValues }
            )
        }
    }
}

class ObjListReorderChange(val oldValues: ObjList, val newValues: Obj) : ObjListChange() {
    override val objClass: ObjClass get() = type

    companion object {
        val type = ObjClass("ListReorder", ObjListChange.type).apply {
            addPropertyDoc(
                "oldValues",
                "Values before reorder.",
                TypeGenericDoc(type("lyng.List"), listOf(type("lyng.Any"))),
                moduleName = "lyng.observable",
                getter = { thisAs<ObjListReorderChange>().oldValues }
            )
            addPropertyDoc(
                "newValues",
                "Values after reorder (or Unset in beforeChange when unknown before commit).",
                type("lyng.Any"),
                moduleName = "lyng.observable",
                getter = { thisAs<ObjListReorderChange>().newValues }
            )
        }
    }
}

class ObjSubscription(private val onCancel: suspend () -> Unit) : Obj() {
    private val access = Mutex()
    private var cancelled = false

    override val objClass: ObjClass get() = type

    suspend fun cancel() {
        var action: (suspend () -> Unit)? = null
        access.withLock {
            if (!cancelled) {
                cancelled = true
                action = onCancel
            }
        }
        action?.invoke()
    }

    companion object {
        val type = object : ObjClass("Subscription") {
            override suspend fun callOn(scope: Scope): Obj = scope.raiseIllegalArgument("Subscription constructor is not available")
        }.apply {
            addFnDoc(
                "cancel",
                "Unsubscribe listener. Safe to call multiple times.",
                returns = type("lyng.Void"),
                moduleName = "lyng.observable"
            ) {
                thisAs<ObjSubscription>().cancel()
                ObjVoid
            }
        }
    }
}

private class ObjObservableListFlowProducer(
    private val owner: ObjObservableList,
    private val subscriptionId: Long,
    private val channel: ReceiveChannel<Obj>
) : Obj() {
    override val objClass: ObjClass get() = Obj.rootObjectType

    override suspend fun callOn(scope: Scope): Obj {
        val builder = scope.thisObj as? ObjFlowBuilder
            ?: scope.raiseIllegalState("flow builder is not available")
        try {
            for (change in channel) {
                builder.invokeInstanceMethod(scope, "emit", change)
            }
        } finally {
            owner.unsubscribeChangeChannel(subscriptionId)
        }
        return ObjVoid
    }
}

class ObjObservableList(list: MutableList<Obj> = mutableListOf()) : ObjList(list) {
    private val access = Mutex()
    private var nextSubscriptionId: Long = 1L
    private val beforeListeners = linkedMapOf<Long, Obj>()
    private val afterListeners = linkedMapOf<Long, Obj>()
    private val changeChannels = linkedMapOf<Long, Channel<Obj>>()

    override val objClass: ObjClass get() = type

    private fun nextId(): Long = nextSubscriptionId++

    private suspend fun addBeforeListener(listener: Obj): ObjSubscription {
        val id = access.withLock {
            val id = nextId()
            beforeListeners[id] = listener
            id
        }
        return ObjSubscription {
            access.withLock { beforeListeners.remove(id) }
        }
    }

    private suspend fun addAfterListener(listener: Obj): ObjSubscription {
        val id = access.withLock {
            val id = nextId()
            afterListeners[id] = listener
            id
        }
        return ObjSubscription {
            access.withLock { afterListeners.remove(id) }
        }
    }

    private suspend fun notifyListeners(scope: Scope, listeners: Collection<Obj>, change: Obj) {
        val facade = scope.asFacade()
        for (listener in listeners) {
            facade.call(listener, Arguments(change))
        }
    }

    private suspend fun snapshotBeforeListeners(): List<Obj> = access.withLock { beforeListeners.values.toList() }
    private suspend fun snapshotAfterListeners(): List<Obj> = access.withLock { afterListeners.values.toList() }

    private suspend fun notifyBefore(scope: Scope, change: Obj) {
        notifyListeners(scope, snapshotBeforeListeners(), change)
    }

    private suspend fun notifyAfter(scope: Scope, change: Obj) {
        notifyListeners(scope, snapshotAfterListeners(), change)
    }

    private suspend fun emitCommittedChange(change: Obj) {
        val channels = access.withLock { changeChannels.toMap() }
        val dead = mutableListOf<Long>()
        for ((id, channel) in channels) {
            if (channel.trySend(change).isFailure) {
                dead += id
            }
        }
        if (dead.isNotEmpty()) {
            access.withLock {
                for (id in dead) {
                    changeChannels.remove(id)?.close()
                }
            }
        }
    }

    private suspend fun mutate(scope: Scope, beforeChange: Obj, mutation: suspend () -> Obj?): Obj? {
        notifyBefore(scope, beforeChange)
        val committed = mutation()
        if (committed != null) {
            notifyAfter(scope, committed)
            emitCommittedChange(committed)
        }
        return committed
    }

    private fun snapshotList(): ObjList = ObjList(list.toMutableList())

    private suspend fun createChangesFlow(scope: Scope): ObjFlow {
        val channel = Channel<Obj>(Channel.UNLIMITED)
        val id = access.withLock {
            val id = nextId()
            changeChannels[id] = channel
            id
        }
        val producer = ObjObservableListFlowProducer(this, id, channel)
        return ObjFlow(producer, scope)
    }

    internal suspend fun unsubscribeChangeChannel(id: Long) {
        access.withLock {
            changeChannels.remove(id)?.close()
        }
    }

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        val idx = index.toInt()
        val old = list[idx]
        val change = ObjListSetChange(idx, old, newValue)
        mutate(scope, change) {
            list[idx] = newValue
            change
        }
    }

    override suspend fun plusAssign(scope: Scope, other: Obj): Obj {
        when {
            other is ObjList -> {
                if (other.list.isEmpty()) return this
                val insert = ObjListInsertChange(list.size, ObjList(other.list.toMutableList()))
                mutate(scope, insert) {
                    list.addAll(other.list)
                    insert
                }
            }

            !shouldTreatAsSingleElement(scope, other) && other.isInstanceOf(ObjIterable) -> {
                val otherList = (other.invokeInstanceMethod(scope, "toList") as ObjList).list
                if (otherList.isEmpty()) return this
                val insert = ObjListInsertChange(list.size, ObjList(otherList.toMutableList()))
                mutate(scope, insert) {
                    list.addAll(otherList)
                    insert
                }
            }

            else -> {
                val insert = ObjListInsertChange(list.size, ObjList(mutableListOf(other)))
                mutate(scope, insert) {
                    list.add(other)
                    insert
                }
            }
        }
        return this
    }

    override suspend fun minusAssign(scope: Scope, other: Obj): Obj {
        if (shouldTreatAsSingleElement(scope, other)) {
            val index = list.indexOf(other)
            if (index < 0) return this
            val old = list[index]
            val remove = ObjListRemoveChange(index, old)
            mutate(scope, remove) {
                list.removeAt(index)
                remove
            }
            return this
        }
        if (other.isInstanceOf(ObjIterable)) {
            val toRemove = mutableSetOf<Obj>()
            other.enumerate(scope) {
                toRemove += it
                true
            }
            val oldValues = snapshotList()
            val filtered = list.filterNot { toRemove.contains(it) }
            if (filtered.size == list.size) return this
            val committed = if (filtered.isEmpty()) {
                ObjListClearChange(oldValues)
            } else {
                ObjListReorderChange(oldValues, ObjList(filtered.toMutableList()))
            }
            mutate(scope, committed) {
                list.clear()
                list.addAll(filtered)
                committed
            }
            return this
        }
        val index = list.indexOf(other)
        if (index < 0) return this
        val old = list[index]
        val remove = ObjListRemoveChange(index, old)
        mutate(scope, remove) {
            list.removeAt(index)
            remove
        }
        return this
    }

    companion object {
        val type = object : ObjClass("ObservableList", ObjList.type) {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjObservableList(scope.args.list.toMutableList())
            }
        }.apply {
            addFnDoc(
                "beforeChange",
                "Subscribe to pre-commit list changes. Throw to reject mutation.",
                params = listOf(ParamDoc("listener", type("lyng.Any"))),
                returns = type("lyng.observable.Subscription"),
                moduleName = "lyng.observable"
            ) {
                thisAs<ObjObservableList>().addBeforeListener(requireOnlyArg())
            }
            addFnDoc(
                "onChange",
                "Subscribe to post-commit list changes.",
                params = listOf(ParamDoc("listener", type("lyng.Any"))),
                returns = type("lyng.observable.Subscription"),
                moduleName = "lyng.observable"
            ) {
                thisAs<ObjObservableList>().addAfterListener(requireOnlyArg())
            }
            addFnDoc(
                "changes",
                "Get a flow of committed list changes.",
                returns = TypeGenericDoc(type("lyng.Flow"), listOf(type("lyng.observable.ListChange"))),
                moduleName = "lyng.observable"
            ) {
                thisAs<ObjObservableList>().createChangesFlow(requireScope())
            }
            addFnDoc(
                "observable",
                "Observable list is already observable; returns this.",
                returns = type("lyng.observable.ObservableList"),
                moduleName = "lyng.observable"
            ) { thisObj }
            addFnDoc(
                "add",
                "Append one or more elements and emit ListInsert.",
                moduleName = "lyng.observable"
            ) {
                val self = thisAs<ObjObservableList>()
                if (args.isEmpty()) return@addFnDoc ObjVoid
                val inserted = ObjList(args.list.toMutableList())
                val change = ObjListInsertChange(self.list.size, inserted)
                self.mutate(requireScope(), change) {
                    self.list.addAll(args.list)
                    change
                }
                ObjVoid
            }
            addFnDoc(
                "insertAt",
                "Insert one or more values at index and emit ListInsert.",
                params = listOf(ParamDoc("index", type("lyng.Int"))),
                moduleName = "lyng.observable"
            ) {
                if (args.size < 2) raiseError("insertAt takes 2+ arguments")
                val self = thisAs<ObjObservableList>()
                val index = requiredArg<ObjInt>(0).value.toInt()
                val values = args.list.drop(1)
                val change = ObjListInsertChange(index, ObjList(values.toMutableList()))
                self.mutate(requireScope(), change) {
                    var i = index
                    for (v in values) self.list.add(i++, v)
                    change
                }
                ObjVoid
            }
            addFnDoc(
                "removeAt",
                "Remove element at index or range and emit remove/reorder/clear changes.",
                params = listOf(ParamDoc("start", type("lyng.Int")), ParamDoc("end", type("lyng.Int"))),
                moduleName = "lyng.observable"
            ) {
                val self = thisAs<ObjObservableList>()
                val start = requiredArg<ObjInt>(0).value.toInt()
                if (args.size == 2) {
                    val end = requireOnlyArg<ObjInt>().value.toInt()
                    val oldValues = self.snapshotList()
                    val newValues = self.list.toMutableList().apply { subList(start, end).clear() }
                    if (newValues == self.list) return@addFnDoc self
                    val change = if (newValues.isEmpty()) ObjListClearChange(oldValues)
                    else ObjListReorderChange(oldValues, ObjList(newValues.toMutableList()))
                    self.mutate(requireScope(), change) {
                        self.list.subList(start, end).clear()
                        change
                    }
                } else {
                    val old = self.list[start]
                    val change = ObjListRemoveChange(start, old)
                    self.mutate(requireScope(), change) {
                        self.list.removeAt(start)
                        change
                    }
                }
                self
            }
            addFnDoc(
                "removeLast",
                "Remove last element(s) and emit remove/reorder/clear changes.",
                params = listOf(ParamDoc("count", type("lyng.Int"))),
                moduleName = "lyng.observable"
            ) {
                val self = thisAs<ObjObservableList>()
                if (self.list.isEmpty()) return@addFnDoc self
                if (args.isNotEmpty()) {
                    val count = requireOnlyArg<ObjInt>().value.toInt()
                    if (count <= 0) return@addFnDoc self
                    val oldValues = self.snapshotList()
                    val newValues = if (count >= self.list.size) emptyList() else self.list.subList(0, self.list.size - count).toList()
                    val change = if (newValues.isEmpty()) ObjListClearChange(oldValues)
                    else ObjListReorderChange(oldValues, ObjList(newValues.toMutableList()))
                    self.mutate(requireScope(), change) {
                        if (count >= self.list.size) self.list.clear()
                        else self.list.subList(self.list.size - count, self.list.size).clear()
                        change
                    }
                } else {
                    val index = self.list.lastIndex
                    val old = self.list[index]
                    val change = ObjListRemoveChange(index, old)
                    self.mutate(requireScope(), change) {
                        self.list.removeAt(index)
                        change
                    }
                }
                self
            }
            addFnDoc(
                "removeRange",
                "Remove a range and emit reorder/clear changes.",
                params = listOf(ParamDoc("range")),
                moduleName = "lyng.observable"
            ) {
                val self = thisAs<ObjObservableList>()
                val before = self.snapshotList()
                val copy = self.list.toMutableList()
                val range = requiredArg<Obj>(0)
                if (range is ObjRange) {
                    val index = range
                    when {
                        index.start is ObjInt && index.end is ObjInt -> {
                            if (index.isEndInclusive) copy.subList(index.start.toInt(), index.end.toInt() + 1)
                            else copy.subList(index.start.toInt(), index.end.toInt())
                        }

                        index.isOpenStart && !index.isOpenEnd -> {
                            if (index.isEndInclusive) copy.subList(0, index.end!!.toInt() + 1)
                            else copy.subList(0, index.end!!.toInt())
                        }

                        index.isOpenEnd && !index.isOpenStart -> {
                            copy.subList(index.start!!.toInt(), copy.size)
                        }

                        index.isOpenStart && index.isOpenEnd -> {
                            copy
                        }

                        else -> {
                            throw RuntimeException("Can't apply range for index: $index")
                        }
                    }.clear()
                } else {
                    val start = range.toInt()
                    val end = requiredArg<ObjInt>(1).value.toInt() + 1
                    copy.subList(start, end).clear()
                }
                if (copy == self.list) return@addFnDoc self
                val change = if (copy.isEmpty()) ObjListClearChange(before)
                else ObjListReorderChange(before, ObjList(copy.toMutableList()))
                self.mutate(requireScope(), change) {
                    self.list.clear()
                    self.list.addAll(copy)
                    change
                }
                self
            }
            addFnDoc(
                "sortWith",
                "Sort list in place and emit ListReorder.",
                params = listOf(ParamDoc("comparator")),
                moduleName = "lyng.observable"
            ) {
                val self = thisAs<ObjObservableList>()
                if (self.list.size < 2) return@addFnDoc ObjVoid
                val comparator = requireOnlyArg<Obj>()
                val oldValues = self.snapshotList()
                val beforeChange = ObjListReorderChange(oldValues, ObjUnset)
                self.notifyBefore(requireScope(), beforeChange)
                self.quicksort { a, b -> call(comparator, Arguments(a, b)).toInt() }
                val newValues = self.snapshotList()
                if (oldValues.list != newValues.list) {
                    val committed = ObjListReorderChange(oldValues, newValues)
                    self.notifyAfter(requireScope(), committed)
                    self.emitCommittedChange(committed)
                }
                ObjVoid
            }
            addFnDoc(
                "shuffle",
                "Shuffle list in place and emit ListReorder.",
                moduleName = "lyng.observable"
            ) {
                val self = thisAs<ObjObservableList>()
                if (self.list.size < 2) return@addFnDoc ObjVoid
                val oldValues = self.snapshotList()
                val beforeChange = ObjListReorderChange(oldValues, ObjUnset)
                self.notifyBefore(requireScope(), beforeChange)
                self.list.shuffle()
                val newValues = self.snapshotList()
                if (oldValues.list != newValues.list) {
                    val committed = ObjListReorderChange(oldValues, newValues)
                    self.notifyAfter(requireScope(), committed)
                    self.emitCommittedChange(committed)
                }
                ObjVoid
            }
        }
    }
}
