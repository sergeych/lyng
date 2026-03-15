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

package net.sergeych.lyng.stdlib_included

@Suppress("Unused", "MemberVisibilityCanBePrivate")
internal val observableLyng = """
package lyng.observable

extern class ChangeRejectionException : Exception

extern class Subscription {
    extern fun cancel(): Void
}

extern class Observable<Change> {
    extern fun beforeChange(listener: (Change)->Void): Subscription
    extern fun onChange(listener: (Change)->Void): Subscription
    extern fun changes(): Flow<Change>
}

extern class ListChange<T>

extern class ListSet<T> : ListChange<T> {
    extern val index: Int
    extern val oldValue: Object
    extern val newValue: Object
}

extern class ListInsert<T> : ListChange<T> {
    extern val index: Int
    extern val values: List<T>
}

extern class ListRemove<T> : ListChange<T> {
    extern val index: Int
    extern val oldValue: Object
}

extern class ListClear<T> : ListChange<T> {
    extern val oldValues: List<T>
}

extern class ListReorder<T> : ListChange<T> {
    extern val oldValues: List<T>
    extern val newValues: Object
}

extern class ObservableList<T> : List<T> {
    extern fun beforeChange(listener: (ListChange<T>)->Void): Subscription
    extern fun onChange(listener: (ListChange<T>)->Void): Subscription
    extern fun changes(): Flow<ListChange<T>>
}

fun List<T>.observable(): ObservableList<T> {
    if( this is ObservableList<T> ) this
    else ObservableList(...this)
}
""".trimIndent()
