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

import net.sergeych.lyng.obj.ObjString

class Source(val fileName: String, val text: String) {

    // Preserve original text characters exactly; do not trim trailing spaces.
    // Split by "\n" boundaries as the lexer models line breaks uniformly as a single newline
    // between logical lines, regardless of original platform line endings.
    // We intentionally do NOT trim line ends to keep columns accurate.
    val lines: List<String> = text.split('\n')

    val objSourceName by lazy { ObjString(fileName) }

    companion object {
        val builtIn: Source by lazy { Source("built-in", "") }
        val UNKNOWN: Source by lazy { Source("UNKNOWN", "") }
    }

    val startPos: Pos = Pos(this, 0, 0)

    fun posAt(line: Int, column: Int): Pos = Pos(this, line, column)

    fun extractPackageName(): String {
        for ((n,line) in lines.withIndex()) {
            if( line.isBlank() )
                continue
            if( line.startsWith("package ") )
                return line.substring(8).trim()
            else throw ScriptError(Pos(this, n, 0),"package declaration expected")
        }
        throw ScriptError(Pos(this, 0, 0),"package declaration expected")
    }
}