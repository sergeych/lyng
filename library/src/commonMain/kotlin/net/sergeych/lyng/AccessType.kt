package net.sergeych.lyng

enum class AccessType(val isMutable: Boolean) {
    Val(false), Var(true),

    @Suppress("unused")
    Initialization(false)
}