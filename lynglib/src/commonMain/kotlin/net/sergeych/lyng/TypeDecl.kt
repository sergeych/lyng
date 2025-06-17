@file:Suppress("unused")

package net.sergeych.lyng

// this is highly experimental and subject to complete redesign
// very soon
sealed class TypeDecl(val isNullable:Boolean = false) {
    // ??
//    data class Fn(val argTypes: List<ArgsDeclaration.Item>, val retType: TypeDecl) : TypeDecl()
    object TypeAny : TypeDecl()
    object TypeNullableAny : TypeDecl(true)

    class Simple(val name: String,isNullable: Boolean) : TypeDecl(isNullable)
}
