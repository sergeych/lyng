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

package net.sergeych.lyng.pacman

import net.sergeych.lyng.*
import net.sergeych.synctools.ProtectedOp
import net.sergeych.synctools.withLock

interface ModuleBuilder {
    suspend fun build(scope: ModuleScope)
}

/**
 * Import manager allow to register packages with builder lambdas and act as an
 * [ImportProvider]. Note that packages _must be registered_ first with [addPackage],
 * [addSourcePackages] or [addTextPackages]. Registration is cheap, actual package
 * building is lazily performed on [createModuleScope], when the package will
 * be first imported.
 *
 * It is possible to register new packages at any time, but it is not allowed to override
 * packages already registered.
 */
class ImportManager(
    rootScope: Scope = Script.defaultImportManager.newModule(),
    securityManager: SecurityManager = SecurityManager.allowAll
) : ImportProvider(rootScope, securityManager) {

    val packageNames: List<String> get() = imports.keys.toList()

    private inner class Entry(
        val packageName: String,
        val builder: ModuleBuilder,
        var cachedScope: ModuleScope? = null
    ) {

        suspend fun getScope(pos: Pos): ModuleScope {
            cachedScope?.let { return it }
            val ms = ModuleScope(inner, pos, packageName)
            cachedScope = ms
            builder.build(ms)
            return ms
        }
    }


    /**
     * Inner provider does not lock [op], the only difference; it is meant to be used
     * exclusively by the coroutine that starts actual import chain
     */
    private inner class InternalProvider : ImportProvider(rootScope) {
        override suspend fun createModuleScope(pos: Pos, packageName: String): ModuleScope {
            return doImport(packageName, pos)
        }

        override fun getActualProvider(): ImportProvider {
            return this@ImportManager
        }
    }

    /**
     * Inner module import provider used to prepare lazily prepared modules
     */
    private val inner = InternalProvider()


    private val imports = mutableMapOf<String, Entry>()

    val op = ProtectedOp()


    /**
     * Register new package that can be imported. It is not possible to unregister or
     * update package already registered.
     *
     * Packages are lazily created when first imported somewhere, so the registration is
     * cheap; the recommended procedure is to register all available packages prior to
     * compile with this.
     *
     * @param name package name
     * @param builder lambda to create actual package using the given [ModuleScope]
     */
    fun addPackage(name: String, builder: ModuleBuilder) {
        op.withLock {
            if (name in imports)
                throw IllegalArgumentException("Package $name already exists")
            imports[name] = Entry(name, builder)
        }
    }

    /**
     * Bulk [addPackage] with slightly better performance
     */
    @Suppress("unused")
    fun addPackages(registrationData: List<Pair<String, ModuleBuilder>>) {
        op.withLock {
            for (pp in registrationData) {
                if (pp.first in imports)
                    throw IllegalArgumentException("Package ${pp.first} already exists")
                imports[pp.first] = Entry(pp.first, pp.second)
            }
        }
    }

    /**
     * Perform actual import or return ready scope. __It must only be called when
     * [op] is locked__, e.g. only internally
     */
    private suspend fun doImport(packageName: String, pos: Pos): ModuleScope {
        val entry = imports[packageName] ?: throw ImportException(pos, "package not found: $packageName")
        return entry.getScope(pos)
    }

    override suspend fun createModuleScope(pos: Pos, packageName: String): ModuleScope =
        doImport(packageName, pos)

    /**
     * Add packages that only need to compile [Source].
     */
    fun addSourcePackages(vararg sources: Source) {
        for (s in sources) {
            addPackage(s.extractPackageName(), object : ModuleBuilder {
                override suspend fun build(scope: ModuleScope) {
                    scope.eval(s)
                }
            })

        }
    }

    /**
     * Add source packages using package name as [Source.fileName], for simplicity
     */
    fun addTextPackages(vararg sourceTexts: String) {
        for (s in sourceTexts) {
            var source = Source("tmp", s)
            val packageName = source.extractPackageName()
            source = Source(packageName, s)
            addPackage(packageName, object : ModuleBuilder {
                override suspend fun build(scope: ModuleScope) {
                    scope.eval(source)
                }
            })
        }
    }

    fun copy(): ImportManager =
        op.withLock {
            ImportManager(rootScope, securityManager).apply {
                imports.putAll(this@ImportManager.imports)
            }
        }

}