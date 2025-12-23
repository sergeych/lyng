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

enum class Visibility {
    Public, Private, Protected;//, Internal
    val isPublic by lazy { this == Public }
    @Suppress("unused")
    val isProtected by lazy { this == Protected }
}

/** MI-aware visibility check: whether [caller] can access a member declared in [decl] with [visibility]. */
fun canAccessMember(visibility: Visibility, decl: net.sergeych.lyng.obj.ObjClass?, caller: net.sergeych.lyng.obj.ObjClass?): Boolean {
    val res = when (visibility) {
        Visibility.Public -> true
        Visibility.Private -> (decl != null && caller === decl)
        Visibility.Protected -> when {
            decl == null -> false
            caller == null -> false
            caller === decl -> true
            else -> (caller.allParentsSet.contains(decl))
        }
    }
    return res
}