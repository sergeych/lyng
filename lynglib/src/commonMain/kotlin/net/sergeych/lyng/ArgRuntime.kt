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

package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj

/**
 * Helper factory for argument-building mutable lists.
 * Currently returns a fresh ArrayList with the requested initial capacity.
 * JVM-specific pooling/builder can be introduced later via expect/actual without
 * changing call sites that use [newArgMutableList].
 */
fun newArgMutableList(initialCapacity: Int): MutableList<Obj> = ArrayList(initialCapacity)
