package novah.frontend.typechecker

import novah.ast.canonical.Expr
import novah.frontend.Span
import novah.frontend.typechecker.InferContext._apply
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.InferContext.discard
import novah.frontend.typechecker.InferContext.restore
import novah.frontend.typechecker.InferContext.store
import novah.frontend.typechecker.WellFormed.wfType

object Subsumption {

    fun solve(x: Type.TMeta, type: Type, ctxExpr: Expr) {
        if (!type.isMono()) {
            inferError("Cannot solve with polytype $x := $type", ctxExpr)
        }

        val newCtx = context.split<Elem.CTMeta>(x.name)
        wfType(type)
        context.add(Elem.CTMeta(x.name, type))
        context.addAll(newCtx)
    }

    fun instL(x: Type.TMeta, type: Type, ctxExpr: Expr) {
        store()
        try {
            solve(x, type, ctxExpr)
            discard()
        } catch (err: InferenceError) {
            restore()
            when (type) {
                is Type.TMeta -> solve(type, x, ctxExpr)
                is Type.TFun -> {
                    val name = x.name
                    val a = store.fresh(name)
                    val b = store.fresh(name)
                    val ta = Type.TMeta(a)
                    val tb = Type.TMeta(b)
                    context.replace<Elem.CTMeta>(
                        name, listOf(
                            Elem.CTMeta(b),
                            Elem.CTMeta(a),
                            Elem.CTMeta(name, Type.TFun(ta, tb))
                        )
                    )
                    instR(type.arg, ta, ctxExpr)
                    instL(tb, _apply(type.ret), ctxExpr)
                }
                is Type.TForall -> {
                    val name = store.fresh(type.name)
                    val m = store.fresh("m")
                    context.enter(m, Elem.CTVar(name))
                    instL(x, Type.openTForall(type, Type.TVar(name)), ctxExpr)
                    context.leave(m)
                }
                else -> inferError("instL failed: unknown type $type", ctxExpr)
            }
        }
    }

    fun instR(type: Type, x: Type.TMeta, ctxExpr: Expr) {
        store()
        try {
            solve(x, type, ctxExpr)
            discard()
        } catch (err: InferenceError) {
            restore()
            when (type) {
                is Type.TMeta -> solve(type, x, ctxExpr)
                is Type.TFun -> {
                    val name = x.name
                    val a = store.fresh(name)
                    val b = store.fresh(name)
                    val ta = Type.TMeta(a)
                    val tb = Type.TMeta(b)
                    context.replace<Elem.CTMeta>(
                        name, listOf(
                            Elem.CTMeta(b),
                            Elem.CTMeta(a),
                            Elem.CTMeta(name, Type.TFun(ta, tb))
                        )
                    )
                    instL(ta, type.arg, ctxExpr)
                    instR(_apply(type.ret), tb, ctxExpr)
                }
                is Type.TForall -> {
                    val name = store.fresh(type.name)
                    val m = store.fresh("m")
                    context.enter(m, Elem.CTVar(name))
                    instR(Type.openTForall(type, Type.TMeta(name)), x, ctxExpr)
                    context.leave(m)
                }
                else -> inferError("instR failed: unknown type $type", ctxExpr)
            }
        }
    }

    fun subsume(a: Type, b: Type, ctxExpr: Expr) {
        if (a == b) return
        if (a is Type.TVar && b is Type.TVar && a.name == b.name) return
        if (a is Type.TMeta && b is Type.TMeta && a.name == b.name) return
        if (a is Type.TFun && b is Type.TFun) {
            subsume(b.arg, a.arg, ctxExpr)
            subsume(_apply(a.ret), _apply(b.ret), ctxExpr)
            return
        }
        // Not sure about this code but seems to work
        if (a is Type.TConstructor && b is Type.TConstructor && a.name == b.name) {
            kindCheck(a, b, ctxExpr)
            a.types.forEachIndexed { i, ty ->
                subsume(_apply(ty), _apply(b.types[i]), ctxExpr)
            }
            return
        }
        if (a is Type.TForall) {
            val x = store.fresh(a.name)
            val m = store.fresh("m")
            context.enter(m, Elem.CTMeta(x))
            subsume(Type.openTForall(a, Type.TMeta(x)), b, ctxExpr)
            context.leave(m)
            return
        }
        if (b is Type.TForall) {
            val x = store.fresh(b.name)
            val m = store.fresh("m")
            context.enter(m, Elem.CTVar(x))
            subsume(a, Type.openTForall(b, Type.TVar(x)), ctxExpr)
            context.leave(m)
            return
        }
        if (a is Type.TMeta) {
            if (b.containsTMeta(a.name)) {
                inferError("Occurs check L failed: $a in $b", ctxExpr)
            }
            instL(a, b, ctxExpr)
            return
        }
        if (b is Type.TMeta) {
            if (a.containsTMeta(b.name)) {
                inferError("Occurs check R failed: $b in $a", ctxExpr)
            }
            instR(a, b, ctxExpr)
            return
        }
        inferError("subsume failed: expected type $a but got $b", ctxExpr)
    }

    private fun kindCheck(a: Type.TConstructor, b: Type.TConstructor, ctxExpr: Expr) {
        val atypes = a.types.size
        val btypes = b.types.size

        if (atypes != btypes) {
            inferError(
                "type ${a.name} must have kind ${makeKindString(atypes)} but got kind ${makeKindString(btypes)}: $b",
                ctxExpr
            )
        }
    }

    private fun makeKindString(s: Int): String {
        return (1..s).joinToString(" -> ") { "Type" }
    }
}