package net.sergeych.lyng.obj

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonType

/**
 * Special variant of [ObjClass] to be used in [ObjInstance], e.g. for Lyng compiled classes
 */
class ObjInstanceClass(val name: String) : ObjClass(name) {

    override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
        val args = decoder.decodeAnyList(scope)
        println("deserializing constructor $name, $args params")
        val actualSize = constructorMeta?.params?.size ?: 0
        if( args.size > actualSize )
            scope.raiseIllegalArgument("constructor $name has only $actualSize but serialized version has ${args.size}")
        val newScope = scope.copy(args = Arguments(args))
        return (callOn(newScope) as ObjInstance).apply {
            deserializeStateVars(scope,decoder)
            invokeInstanceMethod(scope, "onDeserialized", onNotFoundResult = ObjVoid)
        }
    }

}