package net.sergeych.lyng.pacman

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.*

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
): ImportProvider(rootScope, securityManager) {

    private inner class Entry(
        val packageName: String,
        val builder: suspend (ModuleScope) -> Unit,
        var cachedScope: ModuleScope? = null
    ) {

        suspend fun getScope(pos: Pos): ModuleScope {
            cachedScope?.let { return it }
            return ModuleScope(inner, pos, packageName).apply {
                cachedScope = this
                builder(this)
            }
        }
    }


    /**
     * Inner provider does not lock [access], the only difference; it is meant to be used
     * exclusively by the coroutine that starts actual import chain
     */
    private inner class InternalProvider : ImportProvider(rootScope) {
        override suspend fun createModuleScope(pos: Pos, packageName: String): ModuleScope {
            return doImport(packageName, pos)
        }
    }

    /**
     * Inner module import provider used to prepare lazily prepared modules
     */
    private val inner = InternalProvider()


    private val imports = mutableMapOf<String, Entry>()
    private val access = Mutex()

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
    suspend fun addPackage(name: String, builder: suspend (ModuleScope) -> Unit) {
        access.withLock {
            if (name in imports)
                throw IllegalArgumentException("Package $name already exists")
            imports[name] = Entry(name, builder)
        }
    }

    /**
     * Bulk [addPackage] with slightly better performance
     */
    @Suppress("unused")
    suspend fun addPackages(registrationData: List<Pair<String, suspend (ModuleScope) -> Unit>>) {
        access.withLock {
            for (pp in registrationData) {
                if (pp.first in imports)
                    throw IllegalArgumentException("Package ${pp.first} already exists")
                imports[pp.first] = Entry(pp.first, pp.second)
            }
        }
    }

    /**
     * Perform actual import or return ready scope. __It must only be called when
     * [access] is locked__, e.g. only internally
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
    suspend fun addSourcePackages(vararg sources: Source) {
        for( s in sources) {
            addPackage(s.extractPackageName()) {
                it.eval(s)
            }

        }
    }

    /**
     * Add source packages using package name as [Source.fileName], for simplicity
     */
    suspend fun addTextPackages(vararg sourceTexts: String) {
        for( s in sourceTexts) {
            var source = Source("tmp", s)
            val packageName = source.extractPackageName()
            source = Source(packageName, s)
            addPackage(packageName) { it.eval(source)}
        }
    }

}