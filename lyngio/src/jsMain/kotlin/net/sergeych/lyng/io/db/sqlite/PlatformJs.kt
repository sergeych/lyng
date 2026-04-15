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

package net.sergeych.lyng.io.db.sqlite

import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.requireScope
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjString

internal actual suspend fun openSqliteBackend(
    scope: ScopeFacade,
    core: SqliteCoreModule,
    options: SqliteOpenOptions,
): SqliteDatabaseBackend {
    scope.raiseError(
        ObjException(
            core.databaseException,
            scope.requireScope(),
            ObjString("SQLite provider is not implemented on this platform yet")
        )
    )
}
