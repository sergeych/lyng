package net.sergeych.lyng.obj

import kotlinx.coroutines.CompletableDeferred
import net.sergeych.lyng.Scope

class ObjCompletableDeferred(val completableDeferred: CompletableDeferred<Obj>): ObjDeferred(completableDeferred) {

    override val objClass = type

    companion object {
        val type = object: ObjClass("CompletableDeferred", ObjDeferred.type){
            override suspend fun callOn(scope: Scope): Obj {
                return ObjCompletableDeferred(CompletableDeferred())
            }
        }.apply {
            addFn("complete") {
                thisAs<ObjCompletableDeferred>().completableDeferred.complete(args.firstAndOnly())
                ObjVoid
            }
        }
    }
}