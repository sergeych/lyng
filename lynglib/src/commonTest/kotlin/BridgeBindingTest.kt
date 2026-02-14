package net.sergeych.lyng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.bridge.LyngClassBridge
import net.sergeych.lyng.bridge.data
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjString

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
            addFun("add") { _, _, args ->
                val a = (args.list[0] as ObjInt).value
                val b = (args.list[1] as ObjInt).value
                ObjInt.of(a + b)
            }
            addVal("status") { _, _ -> ObjString(classData as String) }
            addVar(
                "count",
                get = { _, instance ->
                    val st = (instance as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                    ObjInt.of(st.count)
                },
                set = { _, instance, value ->
                    val st = (instance as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                    st.count = (value as ObjInt).value
                }
            )
            addFun("secret") { _, _, _ -> ObjInt.of(42) }
            addFun("ping") { _, _, _ -> ObjInt.of(7) }
        }

        LyngClassBridge.bind(className = "Bar", module = "bridge.mod", importManager = im) {
            initWithInstance { _, instance ->
                (instance as net.sergeych.lyng.obj.ObjInstance).data = CounterState(10)
            }
            addVar(
                "count",
                get = { _, instance ->
                    val st = (instance as net.sergeych.lyng.obj.ObjInstance).data as CounterState
                    ObjInt.of(st.count)
                },
                set = { _, instance, value ->
                    val st = (instance as net.sergeych.lyng.obj.ObjInstance).data as CounterState
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
                addVal("status") { _, _ -> ObjString("late") }
            }
            false
        } catch (_: ScriptError) {
            true
        }
        assertTrue(bindFailed)
    }
}
