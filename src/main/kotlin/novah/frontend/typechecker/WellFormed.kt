package novah.frontend.typechecker

import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.InferContext.store

object WellFormed {

    fun wfType(type: Type) {
        if (type is Type.TVar && !context.contains<Elem.CTVar>(type.name)) {
            inferError("undefined TVar ${type.name}")
        }
        if (type is Type.TMeta && !context.contains<Elem.CTMeta>(type.name)) {
            inferError("undefined TMeta ?${type.name}")
        }
        if (type is Type.TFun) {
            wfType(type.arg)
            wfType(type.ret)
        }
        if (type is Type.TForall) {
            val t = store.fresh(type.name)
            val m = store.fresh("m")
            context.enter(m, Elem.CTVar(t))
            wfType(Type.openTForall(type, Type.TVar(t)))
            context.leave(m)
        }
    }

    fun wfElem(elem: Elem) {
        when (elem) {
            is Elem.CTVar -> {
                if (context.contains<Elem.CTVar>(elem.name)) {
                    inferError("duplicated tvar ${elem.name}")
                }
            }
            is Elem.CTMeta -> {
                if (context.contains<Elem.CTMeta>(elem.name)) {
                    inferError("duplicated mvar ?${elem.name}")
                }
            }
            is Elem.CVar -> {
                if (context.contains<Elem.CVar>(elem.name)) {
                    inferError("duplicated cvar ${elem.name}")
                }
            }
            is Elem.CMarker -> {
                if (context.contains<Elem.CMarker>(elem.name)) {
                    inferError("duplicated cmarker |${elem.name}")
                }
            }
        }
    }

    fun wfContext() {
        store()
        var elem = context.pop()
        while (elem != null) {
            wfElem(elem)
            elem = context.pop()
        }
        InferContext.restore()
    }
}