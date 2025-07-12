package net.sergeych.lynon

/**
 * Variant of [LynonEncoder] that writes to embedded [MemoryBitOutput]
 */
class LynonPacker(bout: MemoryBitOutput = MemoryBitOutput(), settings: LynonSettings = LynonSettings.default)
    : LynonEncoder(bout, settings) {
    fun toUByteArray(): UByteArray = (bout as MemoryBitOutput).toUByteArray()
}

/**
 * Variant of [LynonDecoder] that reads from a given `source` using [MemoryBitInput]
 */
class LynonUnpacker(source: UByteArray) : LynonDecoder(MemoryBitInput(source))