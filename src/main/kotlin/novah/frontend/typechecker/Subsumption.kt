package novah.frontend.typechecker

import novah.frontend.Span

class Subsumption(
    private val wf: WellFormed,
    private val store: GenNameStore,
    private val ictx: InferContext
) {

    private val solvedMetas = mutableMapOf<Name, Type>()

    fun solve(x: Type.TMeta, type: Type, span: Span) {
        if (!type.isMono()) {
            inferError("Cannot solve with polytype $x := $type", span)
        }

        val newCtx = ictx.context.split<Elem.CTMeta>(x.name)
        wf.wfType(type, span)
        setSolved(x.name, type)
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
                else -> inferError("instL failed: unknown type $type", span)
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
                else -> inferError("instR failed: unknown type $type", span)
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
                inferError("Occurs check L failed: $a in $b", span)
            }
            instL(a, b, span)
            return
        }
        if (b is Type.TMeta) {
            if (a.containsTMeta(b.name)) {
                inferError("Occurs check R failed: $b in $a", span)
            }
            instR(a, b, span)
            return
        }
        inferError("subsume failed: expected type $a but got $b", span)
    }

    private fun kindCheck(a: Type.TConstructor, b: Type.TConstructor, span: Span) {
        val atypes = a.types.size
        val btypes = b.types.size

        if (atypes != btypes) {
            inferError(
                "type ${a.name} must have kind ${makeKindString(atypes)} but got kind ${makeKindString(btypes)}: $b",
                span
            )
        }
    }

    fun getSolvedMetas(): Map<Name, Type> = solvedMetas

    fun cleanSolvedMetas() = solvedMetas.clear()

    fun setSolved(name: Name, type: Type) {
        if (type is Type.TMeta) {
            val solved = solvedMetas[type.name]
            if (solved != null) solvedMetas[name] = solved
            else solvedMetas[name] = type
        } else {
            solvedMetas[name] = type
            solvedMetas.forEach { (n, t) ->
                if (t is Type.TMeta && t.name == name)
                    solvedMetas[n] = type
            }
        }
    }

    companion object {
        fun makeKindString(s: Int): String {
            return (0..s).joinToString(" -> ") { "Type" }
        }
    }
}