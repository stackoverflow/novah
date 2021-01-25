package novah.frontend.typechecker

import novah.frontend.Span
import novah.frontend.error.ProblemContext
import novah.frontend.error.Errors as E

class Subsumption(
    private val wf: WellFormed,
    private val store: GenNameStore,
    private val ictx: InferContext
) {

    fun solve(x: Type.TMeta, type: Type, span: Span) {
        if (!type.isMono()) {
            inferError(E.cannotSolvePolytype(x.name, type), span, ProblemContext.SUBSUMPTION)
        }

        val newCtx = ictx.context.split<Elem.CTMeta>(x.name)
        wf.wfType(type, span)
        x.solvedType = type
        ictx.context.add(Elem.CTMeta(x.name, type))
        ictx.context.addAll(newCtx)
    }

    fun instL(x: Type.TMeta, type: Type, span: Span) {
        ictx.store()
        try {
            solve(x, type, span)
            ictx.discard()
        } catch (err: InferenceError) {
            ictx.restore()
            when (type) {
                is Type.TMeta -> solve(type, x, span)
                is Type.TFun -> {
                    val name = x.name
                    val a = store.fresh(name)
                    val b = store.fresh(name)
                    val ta = Type.TMeta(a)
                    val tb = Type.TMeta(b)
                    ictx.context.replace<Elem.CTMeta>(
                        name, listOf(
                            Elem.CTMeta(b),
                            Elem.CTMeta(a),
                            Elem.CTMeta(name, Type.TFun(ta, tb))
                        )
                    )
                    instR(type.arg, ta, span)
                    instL(tb, ictx.apply(type.ret), span)
                }
                is Type.TConstructor -> {
                    val name = x.name
                    val total = type.types.size - 1
                    val freshVars = (0..total).map { store.fresh(name) }
                    val tmetas = freshVars.map { Type.TMeta(it) }
                    val ctmetas = freshVars.reversed().map { Elem.CTMeta(it) }
                    ictx.context.replace<Elem.CTMeta>(
                        name,
                        ctmetas + Elem.CTMeta(name, Type.TConstructor(type.name, tmetas))
                    )
                    tmetas.zip(type.types).map { (meta, type) ->
                        instR(type, meta, span)
                    }
                }
                is Type.TForall -> {
                    val name = store.fresh(type.name)
                    val m = store.fresh("m")
                    ictx.context.enter(m, Elem.CTVar(name))
                    instL(x, Type.openTForall(type, Type.TVar(name)), span)
                    ictx.context.leave(m)
                }
                else -> inferError(E.unknownType(type), span, ProblemContext.INSTL)
            }
        }
    }

    fun instR(type: Type, x: Type.TMeta, span: Span) {
        ictx.store()
        try {
            solve(x, type, span)
            ictx.discard()
        } catch (err: InferenceError) {
            ictx.restore()
            when (type) {
                is Type.TMeta -> solve(type, x, span)
                is Type.TFun -> {
                    val name = x.name
                    val a = store.fresh(name)
                    val b = store.fresh(name)
                    val ta = Type.TMeta(a)
                    val tb = Type.TMeta(b)
                    ictx.context.replace<Elem.CTMeta>(
                        name, listOf(
                            Elem.CTMeta(b),
                            Elem.CTMeta(a),
                            Elem.CTMeta(name, Type.TFun(ta, tb))
                        )
                    )
                    instL(ta, type.arg, span)
                    instR(ictx.apply(type.ret), tb, span)
                }
                is Type.TConstructor -> {
                    val name = x.name
                    val total = type.types.size - 1
                    val freshVars = (0..total).map { store.fresh(name) }
                    val tmetas = freshVars.map { Type.TMeta(it) }
                    val ctmetas = freshVars.reversed().map { Elem.CTMeta(it) }
                    ictx.context.replace<Elem.CTMeta>(
                        name,
                        ctmetas + Elem.CTMeta(name, Type.TConstructor(type.name, tmetas))
                    )
                    tmetas.zip(type.types).map { (meta, type) ->
                        instL(meta, type, span)
                    }
                }
                is Type.TForall -> {
                    val name = store.fresh(type.name)
                    val m = store.fresh("m")
                    ictx.context.enter(m, Elem.CTVar(name))
                    instR(Type.openTForall(type, Type.TMeta(name)), x, span)
                    ictx.context.leave(m)
                }
                else -> inferError(E.unknownType(type), span, ProblemContext.INSTR)
            }
        }
    }

    fun subsume(a: Type, b: Type, span: Span) {
        if (a == b) return
        if (a is Type.TVar && b is Type.TVar && a.name == b.name) return
        if (a is Type.TMeta && b is Type.TMeta && a.name == b.name) return
        if (a is Type.TFun && b is Type.TFun) {
            subsume(b.arg, a.arg, span)
            subsume(ictx.apply(a.ret), ictx.apply(b.ret), span)
            return
        }
        // Not sure about this code but seems to work
        if (a is Type.TConstructor && b is Type.TConstructor && a.name == b.name) {
            kindCheck(a, b, span)
            a.types.forEachIndexed { i, ty ->
                subsume(ictx.apply(ty), ictx.apply(b.types[i]), span)
            }
            return
        }
        if (a is Type.TForall) {
            val x = store.fresh(a.name)
            val m = store.fresh("m")
            ictx.context.enter(m, Elem.CTMeta(x))
            subsume(Type.openTForall(a, Type.TMeta(x)), b, span)
            ictx.context.leave(m)
            return
        }
        if (b is Type.TForall) {
            val x = store.fresh(b.name)
            val m = store.fresh("m")
            ictx.context.enter(m, Elem.CTVar(x))
            subsume(a, Type.openTForall(b, Type.TVar(x)), span)
            ictx.context.leave(m)
            return
        }
        if (a is Type.TMeta) {
            if (b.containsTMeta(a.name)) {
                inferError(E.infiniteType(b), span, ProblemContext.SUBSUMPTION)
            }
            instL(a, b, span)
            return
        }
        if (b is Type.TMeta) {
            if (a.containsTMeta(b.name)) {
                inferError(E.infiniteType(a), span, ProblemContext.SUBSUMPTION)
            }
            instR(a, b, span)
            return
        }
        inferError(E.subsumptionFailed(a, b), span, ProblemContext.SUBSUMPTION)
    }

    private fun kindCheck(a: Type.TConstructor, b: Type.TConstructor, span: Span) {
        val atypes = a.types.size
        val btypes = b.types.size

        if (atypes != btypes) {
            inferError(E.wrongKind(makeKindString(atypes), makeKindString(btypes)), span, ProblemContext.SUBSUMPTION)
        }
    }

    companion object {
        fun makeKindString(s: Int): String {
            return (0..s).joinToString(" -> ") { "Type" }
        }
    }
}