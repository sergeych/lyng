package net.sergeych.lyng

// this is highly experimental and subject to complete redesign
// very soon
sealed class TypeDecl {
    // ??
//    data class Fn(val argTypes: List<ArgsDeclaration.Item>, val retType: TypeDecl) : TypeDecl()
    object Obj : TypeDecl()
}
