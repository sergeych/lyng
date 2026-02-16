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
import net.sergeych.lyng.Script
import net.sergeych.lyng.ScriptError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClosedClassTest {
    @Test
    fun testClosedClass() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            closed class MyClosedClass {
                fun foo() = 42
            }
        """.trimIndent())
        
        assertFailsWith<ScriptError> {
            scope.eval("""
                class SubClass : MyClosedClass()
            """.trimIndent())
        }
    }

    @Test
    fun testStdlibClosedClasses() = runTest {
        val scope = Script.newScope()
        
        assertFailsWith<ScriptError> {
            scope.eval("class MyInt : Int()")
        }
        assertFailsWith<ScriptError> {
            scope.eval("class MyReal : Real()")
        }
        assertFailsWith<ScriptError> {
            scope.eval("class MyString : String()")
        }
        assertFailsWith<ScriptError> {
            scope.eval("class MyBool : Bool()")
        }
    }
}
