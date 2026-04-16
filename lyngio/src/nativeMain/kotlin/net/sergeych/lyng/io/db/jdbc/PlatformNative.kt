package net.sergeych.lyng.io.db.jdbc

import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.io.db.SqlCoreModule
import net.sergeych.lyng.io.db.SqlDatabaseBackend
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.requireScope

internal actual suspend fun openJdbcBackend(
    scope: ScopeFacade,
    core: SqlCoreModule,
    options: JdbcOpenOptions,
): SqlDatabaseBackend {
    scope.raiseError(
        ObjException(
            core.databaseException,
            scope.requireScope(),
            ObjString("lyng.io.db.jdbc is available only on the JVM target")
        )
    )
}
