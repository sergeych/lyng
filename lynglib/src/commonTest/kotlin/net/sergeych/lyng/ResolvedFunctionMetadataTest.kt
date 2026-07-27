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
 */

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResolvedFunctionMetadataTest {

    @Test
    fun resolvesCompleteExportSignatureWithoutExecutingSource() = runTest {
        val source = Source(
            "metadata.lyng",
            """
                @Export
                fun exported<T: Object = String>(
                    head: T,
                    rest: T...,
                    limit: Int = 7,
                    suffix: String = "!",
                ): List<T> { [] }

                fun helper(value: String): String { value }
            """.trimIndent()
        )

        val functions = Compiler.resolveFunctionMetadata(source, Script.defaultImportManager)
        val exported = functions.single { it.name == "exported" }
        val helper = functions.single { it.name == "helper" }

        assertEquals(listOf("Export"), exported.annotations)
        assertTrue(helper.annotations.isEmpty())

        assertEquals("metadata.lyng", exported.namePos.source.fileName)
        assertSame(source, exported.namePos.source)
        assertEquals("exported", exported.namePos.currentLine.substring(exported.namePos.column, exported.namePos.column + 8))
        assertSame(source, exported.declarationPos.source)

        val typeParameter = exported.typeParams.single()
        assertEquals("T", typeParameter.name)
        assertEquals(TypeDecl.Variance.Invariant, typeParameter.variance)
        assertSimple(typeParameter.bound, "Object")
        assertSimple(typeParameter.defaultType, "String")

        assertEquals(listOf("head", "rest", "limit", "suffix"), exported.parameters.map { it.name })
        assertTypeVariable(exported.parameters[0].type, "T")
        assertFalse(exported.parameters[0].isEllipsis)
        assertFalse(exported.parameters[0].hasDefault)
        assertNull(exported.parameters[0].defaultSource)

        assertTypeVariable(exported.parameters[1].type, "T")
        assertTrue(exported.parameters[1].isEllipsis)
        assertFalse(exported.parameters[1].hasDefault)
        assertNull(exported.parameters[1].defaultSource)

        assertSimple(exported.parameters[2].type, "Int")
        assertFalse(exported.parameters[2].isEllipsis)
        assertTrue(exported.parameters[2].hasDefault)
        assertEquals("7", exported.parameters[2].defaultSource)
        assertTrue(exported.parameters[3].hasDefault)
        assertEquals("\"!\"", exported.parameters[3].defaultSource)

        val returnType = assertIs<TypeDecl.Generic>(exported.returnType)
        assertEquals("List", returnType.name)
        assertEquals(1, returnType.args.size)
        assertTypeVariable(returnType.args.single(), "T")

        val functionType = assertIs<TypeDecl.Function>(exported.functionType)
        assertNull(functionType.receiver)
        assertTrue(functionType.contextReceivers.isEmpty())
        assertEquals(4, functionType.params.size)
        assertTypeVariable(functionType.params[0], "T")
        val variadicType = assertIs<TypeDecl.Ellipsis>(functionType.params[1])
        assertTypeVariable(variadicType.elementType, "T")
        assertSimple(functionType.params[2], "Int")
        assertGenericListOfTypeVariable(functionType.returnType, "T")
    }

    @Test
    fun reportsCompilerInferredReturnType() = runTest {
        val source = Source(
            "inferred.lyng",
            """
                @Export
                fun increment(value: Int) = value + 1
            """.trimIndent()
        )

        val function = Compiler.resolveFunctionMetadata(source, Script.defaultImportManager).single()

        assertEquals("increment", function.name)
        assertEquals(listOf("Export"), function.annotations)
        assertSimple(function.returnType, "Int")
        assertSimple(assertIs<TypeDecl.Function>(function.functionType).returnType, "Int")
    }

    private fun assertSimple(type: TypeDecl?, name: String, nullable: Boolean = false) {
        val simple = assertIs<TypeDecl.Simple>(type)
        assertEquals(name, simple.name)
        assertEquals(nullable, simple.isNullable)
    }

    private fun assertTypeVariable(type: TypeDecl?, name: String, nullable: Boolean = false) {
        val variable = assertIs<TypeDecl.TypeVar>(type)
        assertEquals(name, variable.name)
        assertEquals(nullable, variable.isNullable)
    }

    private fun assertGenericListOfTypeVariable(type: TypeDecl?, variableName: String) {
        val generic = assertIs<TypeDecl.Generic>(type)
        assertEquals("List", generic.name)
        assertEquals(1, generic.args.size)
        assertTypeVariable(generic.args.single(), variableName)
    }
}
