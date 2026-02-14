package net.sergeych.lyng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.bridge.BridgeCallByName
import net.sergeych.lyng.bridge.LookupSpec
import net.sergeych.lyng.bridge.LookupTarget
import net.sergeych.lyng.bridge.resolver
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjInt

class BridgeResolverTest {
    @Test
    fun testLocalAndMemberHandles() = runTest {
        val im = Script.defaultImportManager.copy()
        val scope = im.newStdScope()
        scope.eval(
            """
            var x = 1
            fun add(a: Int, b: Int) = a + b

            class Foo {
                var count: Int = 0
                fun bump() { count = count + 1 }
            }

            val f = Foo()
            """.trimIndent()
        )

        val facade = scope.asFacade()
        val resolver = facade.resolver()

        val xHandle = resolver.resolveVar("x")
        assertEquals(1, (xHandle.get(facade) as ObjInt).value)
        xHandle.set(facade, ObjInt.of(5))
        assertEquals(5, (xHandle.get(facade) as ObjInt).value)

        val addHandle = resolver.resolveCallable("add")
        val addResult = addHandle.call(facade, Arguments(ObjInt.of(2), ObjInt.of(3)))
        assertEquals(5, (addResult as ObjInt).value)

        val fRec = scope["f"] ?: error("f not found")
        val fObj = scope.resolve(fRec, "f") as ObjInstance
        val countHandle = resolver.resolveMemberVar(fObj, "count")
        assertEquals(0, (countHandle.get(facade) as ObjInt).value)
        val bumpHandle = resolver.resolveMemberCallable(fObj, "bump")
        bumpHandle.call(facade)
        assertEquals(1, (countHandle.get(facade) as ObjInt).value)

        val callByName = resolver as BridgeCallByName
        val addByName = callByName.callByName(facade, "add", Arguments(ObjInt.of(4), ObjInt.of(6)))
        assertEquals(10, (addByName as ObjInt).value)
    }

    @Test
    fun testExtensionCallableHandle() = runTest {
        val im = Script.defaultImportManager.copy()
        val scope = im.newStdScope()
        scope.eval(
            """
            class Foo {
                var count: Int = 1
            }

            fun Foo.bumped() = count + 2

            val f = Foo()
            """.trimIndent()
        )

        val facade = scope.asFacade()
        val resolver = facade.resolver()

        val fooRec = scope["Foo"] ?: error("Foo not found")
        val fooClass = scope.resolve(fooRec, "Foo") as net.sergeych.lyng.obj.ObjClass
        val fRec = scope["f"] ?: error("f not found")
        val fObj = scope.resolve(fRec, "f") as ObjInstance

        val extHandle = resolver.resolveExtensionCallable(fooClass, "bumped")
        val result = extHandle.call(facade, newThisObj = fObj) as ObjInt
        assertEquals(3, result.value)
    }

    @Test
    fun testModuleFrameLookup() = runTest {
        val im = Script.defaultImportManager.copy()
        val module = im.newModuleAt(Pos.builtIn)
        module.eval(
            """
            var x = 7
            """.trimIndent()
        )
        val child = module.createChildScope()
        child.eval(
            """
            var x = 1
            """.trimIndent()
        )

        val facade = child.asFacade()
        val resolver = facade.resolver()
        val handle = resolver.resolveVal("x", LookupSpec(targets = setOf(LookupTarget.ModuleFrame)))
        val result = handle.get(facade) as ObjInt
        assertEquals(7, result.value)
    }
}
