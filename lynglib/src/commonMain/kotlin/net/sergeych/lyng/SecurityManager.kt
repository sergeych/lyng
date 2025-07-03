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