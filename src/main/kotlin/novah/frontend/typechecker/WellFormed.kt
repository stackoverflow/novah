package novah.frontend.typechecker

import novah.frontend.Span
import novah.frontend.typechecker.Subsumption.Companion.makeKindString
import novah.frontend.error.Errors as E

class WellFormed(private val store: GenNameStore, private val ictx: InferContext) {

    fun wfType(type: Type, span: Span) {
        if (type is Type.TVar && !ictx.context.contains<Elem.CTVar>(type.name)) {
            inferError(E.undefinedType(type.name), span)
        }
        if (type is Type.TMeta && !ictx.context.contains<Elem.CTMeta>(type.name)) {
            inferError(E.undefinedType(type.name), span)
        }
        if (type is Type.TFun) {
            wfType(type.arg, span)
            wfType(type.ret, span)
        }
        if (type is Type.TForall) {
            val t = store.fresh(type.name)
            val m = store.fresh("m")
            ictx.context.enter(m, Elem.CTVar(t))
            wfType(Type.openTForall(type, Type.TVar(t)), span)
            ictx.context.leave(m)
        }
        if (type is Type.TConstructor) {
            val elem = ictx.context.lookup<Elem.CTVar>(type.name) ?: inferError(E.undefinedType(type.name), span)

            if (elem.kind != type.types.size) {
                val should = makeKindString(elem.kind)
                val has = makeKindString(type.types.size)
                inferError(E.wrongKind(should, has), span)
            }
            type.types.forEach { wfType(it, span) }
        }
    }

    private fun wfElem(elem: Elem, span: Span) {
        when (elem) {
            is Elem.CTVar -> {
                if (ictx.context.contains<Elem.CTVar>(elem.name)) {
                    inferError(E.duplicatedType(elem.name), span)
                }
            }
            is Elem.CTMeta -> {
                if (ictx.context.contains<Elem.CTMeta>(elem.name)) {
                    inferError(E.duplicatedType(elem.name), span)
                }
            }
            is Elem.CVar -> {
                if (ictx.context.contains<Elem.CVar>(elem.name)) {
                    inferError(E.duplicatedType(elem.name), span)
                }
            }
            is Elem.CMarker -> {
                if (ictx.context.contains<Elem.CMarker>(elem.name)) {
                    inferError(E.duplicatedType(elem.name), span)
                }
            }
        }
    }

    fun wfContext(span: Span) {
        ictx.store()
        var elem = ictx.context.pop()
        while (elem != null) {
            wfElem(elem, span)
            elem = ictx.context.pop()
        }
        ictx.restore()
    }
}