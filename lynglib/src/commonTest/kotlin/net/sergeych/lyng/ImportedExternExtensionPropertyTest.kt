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
import net.sergeych.lyng.bridge.globalBinder
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportedExternExtensionPropertyTest {
    @Test
    fun importedExtensionGetterCanCallExternFromItsImportedRuntimeModule() = runTest {
        val imports = Script.defaultImportManager.copy()
        imports.addPackage("test.contract.runtime") { module ->
            module.eval(
                """
                package test.contract.runtime

                class ContractContext(val label: String)
                class ContractRegistryBase
                class ContractRegistry : ContractRegistryBase

                extern val Contracts: ContractRegistry
                extern fun contractHandle(nameOrId: String): ContractContext
                """.trimIndent()
            )

            val contextClass = module.resolve(
                requireNotNull(module["ContractContext"]),
                "ContractContext"
            ) as ObjClass
            val registryClass = module.resolve(
                requireNotNull(module["ContractRegistry"]),
                "ContractRegistry"
            ) as ObjClass
            val context = contextClass.callOn(
                module.createChildScope(args = Arguments(ObjString("bound")))
            )
            val registry = registryClass.callOn(module.createChildScope())
            module.globalBinder().bindGlobalVarRaw("Contracts", get = { registry })
            module.globalBinder().bindGlobalFunRaw("contractHandle") { _, args ->
                assertEquals("Alpha", (args.firstAndOnly() as ObjString).value)
                context
            }
        }
        imports.addTextPackages(
            """
            package test.contract.Alpha
            import test.contract.runtime

            class AlphaContractAbi(val target: ContractContext)

            val ContractRegistryBase.AlphaHandle: ContractContext
                get() = contractHandle("Alpha")

            val ContractRegistryBase.Alpha: AlphaContractAbi
                get() = AlphaContractAbi(contractHandle("Alpha"))
            """.trimIndent()
        )

        val scope = imports.newStdScope()
        val handleResult = scope.eval(
            """
            import test.contract.runtime
            import test.contract.Alpha

            Contracts.AlphaHandle.label
            """.trimIndent()
        ) as ObjString
        assertEquals("bound", handleResult.value)

        val result = scope.eval(
            """
            import test.contract.runtime
            import test.contract.Alpha

            Contracts.Alpha.target.label
            """.trimIndent()
        ) as ObjString

        assertEquals("bound", result.value)
    }
}
