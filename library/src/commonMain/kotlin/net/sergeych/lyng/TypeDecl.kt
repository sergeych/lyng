package net.sergeych.lyng

sealed class TypeDecl {
    // ??
    data class Fn(val argTypes: List<ArgsDeclaration.Item>, val retType: TypeDecl) : TypeDecl()
    object Obj : TypeDecl()
}

/*
To use in the compiler, we need symbol information when:

- declaring a class: the only way to export its public/protected symbols is to know it in compiler time
- importing a module: actually,  we cam try to do it in a more efficient way.

Importing module:

The moudule is efficiently a statement, that initializes it with all its symbols modifying some context.

The thing is, we need only

 */