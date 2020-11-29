package novah.frontend.typechecker

import novah.frontend.typechecker.InferContext._apply
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.InferContext.discard
import novah.frontend.typechecker.InferContext.restore
import novah.frontend.typechecker.InferContext.store
import novah.frontend.typechecker.WellFormed.wfType

object Subsumption {

    fun solve(x: Type.TMeta, type: Type) {
        if (!type.isMono()) {
            inferError("Cannot solve with polytype $x := $type")
        }

        val newCtx = context.split<Elem.CTMeta>(x.name)
        wfType(type)
        context.add(Elem.CTMeta(x.name, type))
        context.addAll(newCtx)
    }

    fun instL(x: Type.TMeta, type: Type) {
        store()
        try {
            solve(x, type)
            discard()
        } catch (err: InferenceError) {
            restore()
            when (type) {
                is Type.TMeta -> solve(type, x)
                is Type.TFun -> {
                    val name = x.name
                    val a = store.fresh(name)
                    val b = store.fresh(name)
                    val ta = Type.TMeta(a)
                    val tb = Type.TMeta(b)
                    context.replace<Elem.CTMeta>(name, listOf(
                        Elem.CTMeta(b),
                        Elem.CTMeta(a),
                        Elem.CTMeta(name, Type.TFun(ta, tb))
                    ))
                    instR(type.arg, ta)
                    instL(tb, _apply(type.ret))
                }
                is Type.TForall -> {
                    val name = store.fresh(type.name)
                    val m = store.fresh("m")
                    context.enter(m, Elem.CTVar(name))
                    instL(x, Type.openTForall(type, Type.TVar(name)))
                    context.leave(m)
                }
                else -> inferError("instL failed: $x := $type")
            }
        }
    }

    fun instR(type: Type, x: Type.TMeta) {
        store()
        try {
            solve(x, type)
            discard()
        } catch (err: InferenceError) {
            restore()
            when (type) {
                is Type.TMeta -> solve(type, x)
                is Type.TFun -> {
                    val name = x.name
                    val a = store.fresh(name)
                    val b = store.fresh(name)
                    val ta = Type.TMeta(a)
                    val tb = Type.TMeta(b)
                    context.replace<Elem.CTMeta>(name, listOf(
                        Elem.CTMeta(b),
                        Elem.CTMeta(a),
                        Elem.CTMeta(name, Type.TFun(ta, tb))
                    ))
                    instL(ta, type.arg)
                    instR(_apply(type.ret), tb)
                }
                is Type.TForall -> {
                    val name = store.fresh(type.name)
                    val m = store.fresh("m")
                    context.enter(m, Elem.CTVar(name))
                    instR(Type.openTForall(type, Type.TMeta(name)), x)
                    context.leave(m)
                }
                else -> inferError("instR failed: $x := $type")
            }
        }
    }

    fun subsume(a: Type, b: Type) {
        if (a == b) return
        if (a is Type.TVar && b is Type.TVar && a.name == b.name) return
        if (a is Type.TMeta && b is Type.TMeta && a.name == b.name) return
        if (a is Type.TFun && b is Type.TFun) {
            subsume(b.arg, a.arg)
            subsume(_apply(a.ret), _apply(b.ret))
            return
        }
        if (a is Type.TForall) {
            val x = store.fresh(a.name)
            val m = store.fresh("m")
            context.enter(m, Elem.CTMeta(x))
            subsume(Type.openTForall(a, Type.TMeta(x)), b)
            context.leave(m)
            return
        }
        if (b is Type.TForall) {
            val x = store.fresh(b.name)
            val m = store.fresh("m")
            context.enter(m, Elem.CTVar(x))
            subsume(a, Type.openTForall(b, Type.TVar(x)))
            context.leave(m)
            return
        }
        if (a is Type.TMeta) {
            if (b.containsTMeta(a.name)) {
                inferError("Occurs check L failed: $a in $b")
            }
            instL(a, b)
            return
        }
        if (b is Type.TMeta) {
            if (a.containsTMeta(b.name)) {
                inferError("Occurs check R failed: $b in $a")
            }
            instR(a, b)
            return
        }
        inferError("subsume failed: $a <: $b")
    }
}