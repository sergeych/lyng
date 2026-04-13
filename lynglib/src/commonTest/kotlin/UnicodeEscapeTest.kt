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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.*
import net.sergeych.lyng.obj.ObjChar
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnicodeEscapeTest {
    @Test
    fun parserDecodesUnicodeEscapeInStringLiteral() {
        val token = parseLyng("\"\\u263A\"".toSource()).first()
        assertEquals(Token.Type.STRING, token.type)
        assertEquals("☺", token.value)
    }

    @Test
    fun parserDecodesUnicodeEscapeInBacktickStringLiteral() {
        val token = parseLyng("`\\u263A`".toSource()).first()
        assertEquals(Token.Type.STRING, token.type)
        assertEquals("☺", token.value)
    }

    @Test
    fun parserDecodesUnicodeEscapeInCharLiteral() {
        val token = parseLyng("'\\u263A'".toSource()).first()
        assertEquals(Token.Type.CHAR, token.type)
        assertEquals("☺", token.value)
    }

    @Test
    fun parserRejectsMalformedUnicodeEscapeInStringLiteral() {
        assertFailsWith<ScriptError> {
            parseLyng("\"\\u12G4\"".toSource())
        }
    }

    @Test
    fun parserRejectsShortUnicodeEscapeInCharLiteral() {
        assertFailsWith<ScriptError> {
            parseLyng("'\\u12'".toSource())
        }
    }

    @Test
    fun evalDecodesUnicodeEscapes() = runTest {
        assertEquals(ObjString("☺"), eval("\"\\u263A\""))
        assertEquals(ObjString("☺"), eval("`\\u263A`"))
        assertEquals(ObjChar('☺'), eval("'\\u263A'"))
    }
}
