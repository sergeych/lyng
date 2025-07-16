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