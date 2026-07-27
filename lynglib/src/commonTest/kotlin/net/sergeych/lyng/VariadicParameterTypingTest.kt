/*
 * Copyright 2026 Sergey S. Chernov
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
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VariadicParameterTypingTest {
    @Test
    fun typedVariadicParameterIsAListInsideFunctionBody() = runTest {
        val result = Script.newScope().eval(
            """
            fun join(prefix: String, values: Int...): String =
                prefix + values.joinToString(",")

            fun sum(values: Int...): Int {
                var result = 0
                for (value in values) result += value
                return result
            }

            join("n=", 1, 2, 3) + "|" + sum(4, 5, 6)
            """.trimIndent()
        ) as ObjString

        assertEquals("n=1,2,3|15", result.value)
    }

    @Test
    fun variadicMetadataKeepsElementTypeInEllipsisSignature() = runTest {
        val metadata = Compiler.resolveFunctionMetadata(
            Source("variadic-metadata", "fun join(values: Int...): String = values.joinToString(\",\")"),
            Script.defaultImportManager,
        ).single()

        val parameter = metadata.parameters.single()
        assertEquals("Int", (parameter.type as TypeDecl.Simple).name)
        val signatureType = assertIs<TypeDecl.Ellipsis>(metadata.functionType.params.single())
        assertEquals("Int", (signatureType.elementType as TypeDecl.Simple).name)
    }
}
