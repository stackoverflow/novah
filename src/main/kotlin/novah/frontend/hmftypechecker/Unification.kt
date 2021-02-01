package novah.frontend.hmftypechecker

import novah.Util.internalError
import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext

class Unification(private val tc: Typechecker) {

    fun unify(t1: Type, t2: Type, span: Span) {
        if (t1 == t2) return
        if (t1 is Type.TConst && t2 is Type.TConst && t1.name == t2.name) return
        if (t1 is Type.TApp && t2 is Type.TApp) {
            unify(t1.type, t2.type, span)
            if (t1.types.size != t2.types.size)
                unificationError(Errors.typesDontMatch(t1.show(false), t2.show(false)), span)
            t1.types.zip(t2.types).forEach { (tt1, tt2) -> unify(tt1, tt2, span) }
            return
        }
        if (t1 is Type.TArrow && t2 is Type.TArrow) {
            if (t1.args.size != t2.args.size)
                unificationError(Errors.typesDontMatch(t1.show(false), t2.show(false)), span)
            t1.args.zip(t2.args).forEach { (t1arg, t2arg) -> unify(t1arg, t2arg, span) }
            unify(t1.ret, t2.ret, span)
            return
        }
        if (t1 is Type.TVar && t1.tvar is TypeVar.Link) {
            unify((t1.tvar as TypeVar.Link).type, t2, span)
            return
        }
        if (t2 is Type.TVar && t2.tvar is TypeVar.Link) {
            unify(t1, (t2.tvar as TypeVar.Link).type, span)
            return
        }
        if (t1 is Type.TVar && t2 is Type.TVar && t1.tvar is TypeVar.Unbound && t2.tvar is TypeVar.Unbound) {
            if ((t1.tvar as TypeVar.Unbound).id == (t2.tvar as TypeVar.Unbound).id)
                internalError("Error in unification: ${t1.show(false)} with ${t2.show(false)}")
        }
        if (t1 is Type.TVar && t2 is Type.TVar && t1.tvar is TypeVar.Generic && t2.tvar is TypeVar.Generic) {
            if ((t1.tvar as TypeVar.Generic).id == (t2.tvar as TypeVar.Generic).id)
                internalError("Error in unification: ${t1.show(false)} with ${t2.show(false)}")
        }
        if (t1 is Type.TVar && t2 is Type.TVar && t1.tvar is TypeVar.Bound && t2.tvar is TypeVar.Bound) {
            val t1str = t1.show(false)
            val t2str = t2.show(false)
            internalError("Error in unification (bound vars should have been instantiated): $t1str with $t2str")
        }
        if (t1 is Type.TVar && t1.tvar is TypeVar.Unbound) {
            val tvar = t1.tvar as TypeVar.Unbound
            occursCheckAndAdjustLevels(tvar.id, tvar.level, t2, span)
            t1.tvar = TypeVar.Link(t2)
            return
        }
        if (t2 is Type.TVar && t2.tvar is TypeVar.Unbound) {
            val tvar = t2.tvar as TypeVar.Unbound
            occursCheckAndAdjustLevels(tvar.id, tvar.level, t1, span)
            t2.tvar = TypeVar.Link(t1)
            return
        }
        if (t1 is Type.TForall && t2 is Type.TForall) {
            if (t1.ids.size != t2.ids.size)
                unificationError(Errors.typesDontMatch(t1.show(false), t2.show(false)), span)
            val genericVars = t1.ids.zip(t2.ids).reversed().map { (_, _) -> tc.newGenVar() }

            val genericT1 = substituteBoundVars(t1.ids, genericVars, t1.type)
            val genericT2 = substituteBoundVars(t2.ids, genericVars, t2.type)
            unify(genericT1, genericT2, span)

            if (escapeCheck(genericVars, t1, t2))
                unificationError(Errors.typesDontMatch(t1.show(false), t2.show(false)), span)
            return
        }
        unificationError(Errors.typesDontMatch(t1.show(false), t2.show(false)), span)
    }
}

fun occursCheckAndAdjustLevels(id: Id, level: Level, type: Type, span: Span) {
    fun go(t: Type) {
        when (t) {
            is Type.TVar -> {
                when (val tv = t.tvar) {
                    is TypeVar.Link -> go(tv.type)
                    is TypeVar.Generic, is TypeVar.Bound -> {
                    }
                    is TypeVar.Unbound -> {
                        if (id == tv.id) {
                            inferError(Errors.infiniteType(type.show(false)), span, ProblemContext.UNIFICATION)
                        } else if (tv.level > level) {
                            t.tvar = TypeVar.Unbound(tv.id, level)
                        }
                    }
                }
            }
            is Type.TApp -> {
                go(t.type)
                t.types.forEach(::go)
            }
            is Type.TArrow -> {
                t.args.forEach(::go)
                go(t.ret)
            }
            is Type.TForall -> go(t.type)
            is Type.TConst -> {
            }
        }
    }
    go(type)
}

fun substituteBoundVars(varIds: List<Id>, types: List<Type>, type: Type): Type {
    fun go(idMap: Map<Id, Type>, t: Type): Type = when (t) {
        is Type.TConst -> t
        is Type.TVar -> {
            when (val tv = t.tvar) {
                is TypeVar.Link -> go(idMap, tv.type)
                is TypeVar.Bound -> idMap[tv.id] ?: t
                else -> t
            }
        }
        is Type.TApp -> Type.TApp(go(idMap, t.type), t.types.map { go(idMap, it) })
        is Type.TArrow -> Type.TArrow(t.args.map { go(idMap, it) }, go(idMap, t.ret))
        is Type.TForall -> {
            val map = mutableMapOf<Id, Type>()
            idMap.forEach { (k, v) -> if (k !in t.ids) map[k] = v }
            Type.TForall(t.ids, go(map, t.type))
        }
    }
    return go(varIds.zip(types).toMap(), type)
}

fun freeGenericVars(type: Type): HashSet<Type> {
    val freeSet = HashSet<Type>()
    fun go(t: Type) {
        when (t) {
            is Type.TConst -> {
            }
            is Type.TVar -> {
                when (val tv = t.tvar) {
                    is TypeVar.Link -> go(tv.type)
                    is TypeVar.Generic -> freeSet.add(t)
                    else -> {
                    }
                }
            }
            is Type.TApp -> {
                go(t.type)
                t.types.forEach(::go)
            }
            is Type.TArrow -> {
                t.args.forEach(::go)
                go(t.ret)
            }
            is Type.TForall -> go(t.type)
        }
    }
    go(type)
    return freeSet
}

fun escapeCheck(genericVars: List<Type>, t1: Type, t2: Type): Boolean {
    val freeVars1 = freeGenericVars(t1)
    val freeVars2 = freeGenericVars(t2)
    return genericVars.any { it in freeVars1 || it in freeVars2 }
}

private fun unificationError(msg: String, span: Span): Nothing = inferError(msg, span, ProblemContext.UNIFICATION)