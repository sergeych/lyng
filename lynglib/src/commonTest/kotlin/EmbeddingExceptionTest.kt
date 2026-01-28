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

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.obj.*
import net.sergeych.lynon.lynonDecodeAny
import net.sergeych.lynon.lynonEncodeAny
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Ignore("TODO(bytecode-only): uses fallback (try/catch)")
class EmbeddingExceptionTest {

    @Test
    fun testExceptionMessageSerialization() = runTest {
        val scope = Script.newScope()
        val ex = scope.eval("Exception(\"test message\")")
        val encoded = lynonEncodeAny(scope, ex)
        val decoded = lynonDecodeAny(scope, encoded)
        assertEquals("test message", decoded.getLyngExceptionMessage(scope))
    }

    @Test
    fun testIsInstanceOfString() = runTest {
        val scope = Script.newScope()
        scope.eval("class T")
        val t = scope.eval("T()")
        assertTrue(t.isInstanceOf("T"))
        assertTrue(t.isInstanceOf("Obj"))
    }

    @Test
    fun testExceptionSerializationAndRethrow() = runTest {
        val scope = Script.newScope()

        // 1. Define, throw and catch the exception in Lyng to get the object
        val errorObj = scope.eval("""
            class MyException(val code, m) : Exception(m)
            try {
                throw MyException(123, "something failed")
            } catch {
                it
            }
        """.trimIndent())
        
        assertTrue(errorObj.isLyngException(), "Should be a Lyng exception")
        assertTrue(errorObj.isInstanceOf("MyException"), "Should be a MyException")

        // 2. Serialize it
        val encoded = lynonEncodeAny(scope, errorObj)

        // 3. Deserialize it
        val decodedObj = lynonDecodeAny(scope, encoded)

        // 4. Rethrow it using the new uniform extension
        val rethrown = try {
            decodedObj.raiseAsExecutionError(scope)
        } catch (e: ExecutionError) {
            e
        }

        // 5. Verify the rethrown exception preserves its identity and data
        val caughtObj = rethrown.errorObject
        assertTrue(caughtObj.isLyngException())
        assertTrue(caughtObj.isInstanceOf("MyException"))
        assertIs<ObjInstance>(caughtObj)
        assertEquals("MyException", caughtObj.objClass.className)
        
        // Verify we can still access the custom fields
        // 'code' is a field, so we use readField
        val code = caughtObj.readField(scope, "code").value.toKotlin(scope)
        assertEquals(123L, code)
        
        val message = caughtObj.getLyngExceptionMessage(scope)
        assertEquals("something failed", message)
        
        // Verify stack trace is available
        val stack = caughtObj.getLyngExceptionStackTrace(scope)
        assertTrue(stack.list.isNotEmpty(), "Stack trace should not be empty")
        
        val errorString = caughtObj.getLyngExceptionString(scope)
        assertTrue(errorString.contains("MyException: something failed"), "Error string should contain message")
    }

    @Test
    fun testBuiltInExceptionSerialization() = runTest {
        val scope = Script.newScope()

        val encoded = try {
            scope.eval("throw IllegalArgumentException(\"bad arg\")")
            null
        } catch (e: ExecutionError) {
            lynonEncodeAny(scope, e.errorObject)
        }!!

        val decodedObj = lynonDecodeAny(scope, encoded)
        assertTrue(decodedObj.isLyngException())
        assertTrue(decodedObj.isInstanceOf("IllegalArgumentException"))
        assertIs<ObjException>(decodedObj)

        val message = decodedObj.getLyngExceptionMessage(scope)
        assertEquals("bad arg", message)
    }
}
