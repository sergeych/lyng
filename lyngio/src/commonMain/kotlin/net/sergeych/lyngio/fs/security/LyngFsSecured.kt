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

package net.sergeych.lyngio.fs.security

import net.sergeych.lyngio.fs.LyngFs
import net.sergeych.lyngio.fs.LyngMetadata
import net.sergeych.lyngio.fs.LyngPath

/**
 * Decorator that applies an [FsAccessPolicy] to a delegate [LyngFs].
 * Composite operations are checked as a set of primitive [AccessOp]s.
 *
 * Note: attribute update operations are covered by [AccessOp.UpdateAttributes] which
 * by convention defaults to write permission in policy implementations.
 */
class LyngFsSecured(
    private val delegate: LyngFs,
    private val policy: FsAccessPolicy,
    private val ctx: AccessContext = AccessContext(),
) : LyngFs {

    override suspend fun exists(path: LyngPath): Boolean {
        // Existence check is safe; do not guard to allow probing. Policies may tighten later.
        return delegate.exists(path)
    }

    override suspend fun list(dir: LyngPath): List<LyngPath> {
        policy.require(AccessOp.ListDir(dir), ctx)
        return delegate.list(dir)
    }

    override suspend fun readBytes(path: LyngPath): ByteArray {
        policy.require(AccessOp.OpenRead(path), ctx)
        return delegate.readBytes(path)
    }

    override suspend fun writeBytes(path: LyngPath, data: ByteArray, append: Boolean) {
        if (append) {
            policy.require(AccessOp.OpenAppend(path), ctx)
        } else {
            policy.require(AccessOp.OpenWrite(path), ctx)
        }
        // Also require create-file to cover cases when the file does not exist yet.
        policy.require(AccessOp.CreateFile(path), ctx)
        return delegate.writeBytes(path, data, append)
    }

    override suspend fun readUtf8(path: LyngPath): String {
        policy.require(AccessOp.OpenRead(path), ctx)
        return delegate.readUtf8(path)
    }

    override suspend fun writeUtf8(path: LyngPath, text: String, append: Boolean) {
        if (append) {
            policy.require(AccessOp.OpenAppend(path), ctx)
        } else {
            policy.require(AccessOp.OpenWrite(path), ctx)
        }
        policy.require(AccessOp.CreateFile(path), ctx)
        return delegate.writeUtf8(path, text, append)
    }

    override suspend fun metadata(path: LyngPath): LyngMetadata {
        // Not specified in v1; treat as read access for now (can be revised later).
        // policy.require(AccessOp.OpenRead(path), ctx)
        return delegate.metadata(path)
    }

    override suspend fun createDirectories(dir: LyngPath, mustCreate: Boolean) {
        // Model directory creation using CreateFile on the path to be created.
        policy.require(AccessOp.CreateFile(dir), ctx)
        return delegate.createDirectories(dir, mustCreate)
    }

    override suspend fun move(from: LyngPath, to: LyngPath, overwrite: Boolean) {
        // Prefer Rename primitive; also check target deletion if overwrite.
        policy.require(AccessOp.Rename(from, to), ctx)
        if (overwrite) policy.require(AccessOp.Delete(to), ctx)
        return delegate.move(from, to, overwrite)
    }

    override suspend fun delete(path: LyngPath, mustExist: Boolean, recursively: Boolean) {
        policy.require(AccessOp.Delete(path), ctx)
        return delegate.delete(path, mustExist, recursively)
    }

    override suspend fun copy(from: LyngPath, to: LyngPath, overwrite: Boolean) {
        // Composite checks: read from source, create+write to dest, optional delete target first.
        policy.require(AccessOp.OpenRead(from), ctx)
        if (overwrite) policy.require(AccessOp.Delete(to), ctx)
        policy.require(AccessOp.CreateFile(to), ctx)
        policy.require(AccessOp.OpenWrite(to), ctx)
        return delegate.copy(from, to, overwrite)
    }
}
