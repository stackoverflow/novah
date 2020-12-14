package novah.frontend.typechecker

import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.InferContext.store
import novah.frontend.typechecker.Subsumption.makeKindString

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
        if (type is Type.TConstructor) {
            val elem = context.lookup<Elem.CTVar>(type.name) ?: inferError("undefined TConstructor ${type.name}")

            if (elem.kind != type.types.size) {
                val should = makeKindString(elem.kind)
                val has = makeKindString(type.types.size)
                inferError("type $type must have kind $should but got kind $has")
            }
            type.types.forEach { wfType(it) }
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