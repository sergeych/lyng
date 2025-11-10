package net.sergeych.lyng

/**
 * Lightweight runtime counters for perf diagnostics. Enabled via flags in [PerfFlags].
 * Keep simple and zero-cost when disabled by guarding increments at call sites.
 */
object PerfStats {
    // Field PIC
    var fieldPicHit: Long = 0
    var fieldPicMiss: Long = 0
    var fieldPicSetHit: Long = 0
    var fieldPicSetMiss: Long = 0

    // Method PIC
    var methodPicHit: Long = 0
    var methodPicMiss: Long = 0

    // Local var PICs
    var localVarPicHit: Long = 0
    var localVarPicMiss: Long = 0
    var fastLocalHit: Long = 0
    var fastLocalMiss: Long = 0

    // Primitive fast ops
    var primitiveFastOpsHit: Long = 0

    fun resetAll() {
        fieldPicHit = 0
        fieldPicMiss = 0
        fieldPicSetHit = 0
        fieldPicSetMiss = 0
        methodPicHit = 0
        methodPicMiss = 0
        localVarPicHit = 0
        localVarPicMiss = 0
        fastLocalHit = 0
        fastLocalMiss = 0
        primitiveFastOpsHit = 0
    }
}
