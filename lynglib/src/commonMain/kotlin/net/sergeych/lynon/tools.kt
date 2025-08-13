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

package net.sergeych.lynon

/**
 * Variant of [LynonEncoder] that writes to embedded [MemoryBitOutput]
 */
class LynonPacker(bout: MemoryBitOutput = MemoryBitOutput(), settings: LynonSettings = LynonSettings.default)
    : LynonEncoder(bout, settings) {
}

/**
 * Variant of [LynonDecoder] that reads from a given `source` using [MemoryBitInput]
 */
class LynonUnpacker(source: BitInput) : LynonDecoder(source) {
    constructor(packer: LynonPacker) : this((packer.bout as MemoryBitOutput).toBitInput())
}