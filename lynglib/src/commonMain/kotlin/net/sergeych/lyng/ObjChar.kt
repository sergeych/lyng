package net.sergeych.lyng

class ObjChar(val value: Char): Obj() {

    override val objClass: ObjClass = type

    override suspend fun compareTo(scope: Scope, other: Obj): Int =
        (other as? ObjChar)?.let { value.compareTo(it.value) } ?: -1

    override fun toString(): String = value.toString()

    override fun inspect(): String = "'$value'"

    companion object {
        val type = ObjClass("Char").apply {
            addFn("code") { ObjInt(thisAs<ObjChar>().value.code.toLong()) }
        }
    }
}