package net.sergeych.lyng



class Symbols(
    unitType: UnitType,
    val name: String,
    val x: TypeDecl
) {
    enum class UnitType {
        Module, Function, Lambda
    }
}