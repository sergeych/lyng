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

interface SecurityManager {
    /**
     * Check that any symbol from the corresponding module can be imported. If it returns false,
     * the module will not be imported and no further processing will be done
     */
    fun canImportModule(name: String): Boolean

    /**\
     * if [canImportModule] this method allows fine-grained control over symbols.
     */
    fun canImportSymbol(moduleName: String, symbolName: String): Boolean = true

    companion object {
        val allowAll: SecurityManager = object : SecurityManager {
            override fun canImportModule(name: String): Boolean = true
            override fun canImportSymbol(moduleName: String, symbolName: String): Boolean = true
        }
    }

}