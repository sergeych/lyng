package net.sergeych.lyng

enum class Visibility {
    Public, Private, Protected;//, Internal
    val isPublic by lazy { this == Public }
    @Suppress("unused")
    val isProtected by lazy { this == Protected }
}