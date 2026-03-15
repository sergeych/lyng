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
import net.sergeych.lyng.bridge.LyngClassBridge
import net.sergeych.lyng.bridge.bindObject
import net.sergeych.lyng.bridge.data
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertTrue

class BridgeBindingTest {
    private data class CounterState(var count: Long)

    @Test
    fun testExternClassBinding() = runTest {
        val im = Script.defaultImportManager.copy()
        im.addPackage("bridge.mod") { scope ->
            scope.eval(
                """
                class Foo {
                    extern fun add(a: Int, b: Int): Int
                    extern val status: String
                    extern var count: Int
                    private extern fun secret(): Int
                    static extern fun ping(): Int
                    fun callAdd() = add(2, 3)
                    fun callSecret() = secret()
                    fun bump() { count = count + 1 }
                }

                class Bar {
                    extern var count: Int
                    fun inc() { count = count + 1 }
                }
                """.trimIndent()
            )
        }

        LyngClassBridge.bind(className = "Foo", module = "bridge.mod", importManager = im) {
            classData = "OK"
            init { _ ->
                data = CounterState(0)
            }
            addFun("add") {
                val a = (args.list[0] as ObjInt).value
                val b = (args.list[1] as ObjInt).value
                ObjInt.of(a + b)
            }
            addVal("status") { ObjString(classData as String) }
            addVar(
                "count",
                get = {
                    val st = (thisObj as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                    ObjInt.of(st.count)
                },
                set = { value ->
                    val st = (thisObj as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                    st.count = (value as ObjInt).value
                }
            )
            addFun("secret") { ObjInt.of(42) }
            addFun("ping") { ObjInt.of(7) }
        }

        LyngClassBridge.bind(className = "Bar", module = "bridge.mod", importManager = im) {
            initWithInstance {
                (thisObj as net.sergeych.lyng.obj.ObjInstance).data = CounterState(10)
            }
            addVar(
                "count",
                get = {
                    val st = (thisObj as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                    ObjInt.of(st.count)
                },
                set = { value ->
                    val st = (thisObj as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                    st.count = (value as ObjInt).value
                }
            )
        }

        val scope = im.newStdScope()
        scope.eval(
            """
            import bridge.mod
            val f = Foo()
            assertEquals(5, f.callAdd())
            assertEquals("OK", f.status)
            assertEquals(0, f.count)
            f.bump()
            assertEquals(1, f.count)
            assertEquals(42, f.callSecret())
            assertEquals(7, Foo.ping())

            val b = Bar()
            assertEquals(10, b.count)
            b.inc()
            assertEquals(11, b.count)
            """.trimIndent()
        )

        val privateCallFails = try {
            scope.eval(
                """
                import bridge.mod
                Foo().secret()
                """.trimIndent()
            )
            false
        } catch (_: ScriptError) {
            true
        }
        assertTrue(privateCallFails)
    }

    @Test
    fun testBindAfterInstanceFails() = runTest {
        val im = Script.defaultImportManager.copy()
        im.addPackage("bridge.late") { scope ->
            scope.eval(
                """
                class Late {
                    extern val status: String
                }
                """.trimIndent()
            )
        }

        val scope = im.newStdScope()
        scope.eval(
            """
            import bridge.late
            val l = Late()
            """.trimIndent()
        )

        val bindFailed = try {
            LyngClassBridge.bind(className = "Late", module = "bridge.late", importManager = im) {
                addVal("status") { ObjString("late") }
            }
            false
        } catch (_: ScriptError) {
            true
        }
        assertTrue(bindFailed)
    }

    @Test
    fun testExternObjectBinding() = runTest {
        val im = Script.defaultImportManager.copy()
        im.addPackage("bridge.obj") { scope ->
            scope.eval(
                """
                extern object HostObject {
                    extern fun add(a: Int, b: Int): Int
                    extern val status: String
                    extern var count: Int
                }
                """.trimIndent()
            )
            scope.bindObject("HostObject") {
                classData = "OK"
                init { _ ->
                    data = CounterState(5)
                }
                addFun("add") {
                    val a = (args.list[0] as ObjInt).value
                    val b = (args.list[1] as ObjInt).value
                    ObjInt.of(a + b)
                }
                addVal("status") { ObjString(classData as String) }
                addVar(
                    "count",
                    get = {
                        val st = (thisObj as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                        ObjInt.of(st.count)
                    },
                    set = { value ->
                        val st = (thisObj as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                        st.count = (value as ObjInt).value
                    }
                )
            }
        }

        val scope = im.newStdScope()
        scope.eval(
            """
            import bridge.obj
            assertEquals(42, HostObject.add(10, 32))
            assertEquals("OK", HostObject.status)
            assertEquals(5, HostObject.count)
            HostObject.count = HostObject.count + 1
            assertEquals(6, HostObject.count)
            """.trimIndent()
        )
    }

    class ObjA: Obj() {
        companion object {
            val type = ObjClass("A").apply {
                addProperty("field",{
                    ObjInt.of(42)
                })
            }
        }
    }

    @Test
    fun testBindExternClass() = runTest {
        val ms = Script.newScope()
        ms.eval("""
            extern class A {
                extern val field: Int
            }
            
            fun test(a: A) = a.field
            
            val prop = dynamic {
                get { name ->
                    name + "=" + A().field
                }
            }
            
        """.trimIndent())
        ms.addConst("A", ObjA.type)
        ms.eval("assertEquals(42, test(A()))")
        ms.eval("assertEquals(\"test=42\", prop.test)")
    }
}
