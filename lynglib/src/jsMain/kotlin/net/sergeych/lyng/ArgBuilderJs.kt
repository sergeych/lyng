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

actual object ArgBuilderProvider {
    actual fun acquire(): ArgsBuilder = JsArgsBuilder()
}

private class JsArgsBuilder : ArgsBuilder {
    private var buf: MutableList<Obj> = ArrayList()

    override fun reset(expectedSize: Int) {
        buf = ArrayList(expectedSize.coerceAtLeast(0))
    }

    override fun add(v: Obj) { buf.add(v) }
    override fun addAll(vs: List<Obj>) { if (vs.isNotEmpty()) buf.addAll(vs) }
    override fun build(tailBlockMode: Boolean): Arguments = Arguments(buf.toList(), tailBlockMode)
    override fun release() { /* no-op */ }
}
