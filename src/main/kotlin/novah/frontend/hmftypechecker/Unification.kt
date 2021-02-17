/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.frontend.hmftypechecker

import novah.Util.internalError
import novah.data.*
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
        if (t1 is Type.TRowEmpty && t2 is Type.TRowEmpty) return
        if (t1 is Type.TRecord && t2 is Type.TRecord) {
            unify(t1.row, t2.row, span)
            return
        }
        if (t1 is Type.TRowExtend && t2 is Type.TRowExtend) {
            unifyRows(t1, t2, span)
            return
        }
        if (t1 is Type.TRowEmpty && t2 is Type.TRowExtend) {
            unificationError(Errors.recordMissingLabels(t2.labels.keys().toList()), span)
        }
        if (t1 is Type.TRowExtend && t2 is Type.TRowEmpty) {
            unificationError(Errors.recordMissingLabels(t1.labels.keys().toList()), span)
        }
        unificationError(Errors.typesDontMatch(t1.show(false), t2.show(false)), span)
    }

    // Unify two row types
    private fun unifyRows(row1: Type, row2: Type, span: Span) {
        val (labels1, restTy1) = matchRowType(row1)
        val (labels2, restTy2) = matchRowType(row2)

        tailrec fun unifyTypes(t1s: PList<Type>, t2s: PList<Type>): Pair<PList<Type>, PList<Type>> = when {
            t1s.isEmpty() || t2s.isEmpty() -> t1s to t2s
            else -> {
                unify(t1s.first(), t2s.first(), span)
                unifyTypes(t1s.removeFirst(), t2s.removeFirst())
            }
        }

        tailrec fun unifyLabels(
            missing1: PLabelMap<Type>,
            missing2: PLabelMap<Type>,
            labels1: PList<Pair<String, PList<Type>>>,
            labels2: PList<Pair<String, PList<Type>>>
        ): Pair<PLabelMap<Type>, PLabelMap<Type>> = when {
            labels1.isEmpty() && labels2.isEmpty() -> missing1 to missing2
            labels1.isEmpty() -> addDistinctLabels(missing1, labels2) to missing2
            labels2.isEmpty() -> missing1 to addDistinctLabels(missing2, labels1)
            else -> {
                val (label1, tys1) = labels1.first()
                val (label2, tys2) = labels2.first()
                val rest1 = labels1.removeFirst()
                val rest2 = labels2.removeFirst()
                when {
                    label1 == label2 -> {
                        val (m1s, m2s) = unifyTypes(tys1, tys2)
                        val (missing11, missing22) = when {
                            m1s.isEmpty() && m2s.isEmpty() -> missing1 to missing2
                            m2s.isEmpty() -> missing1 to missing2.put(label1, m1s)
                            m1s.isEmpty() -> missing1.put(label2, m2s) to missing2
                            else -> internalError("impossible")
                        }
                        unifyLabels(missing11, missing22, rest1, rest2)
                    }
                    label1 < label2 -> unifyLabels(missing1, missing2.put(label1, tys1), rest1, labels2)
                    else -> unifyLabels(missing1.put(label2, tys2), missing2, labels1, rest2)
                }
            }
        }

        val (missing1, missing2) = unifyLabels(PLabelMap(), PLabelMap(), labels1.toPList(), labels2.toPList())

        val (empty1, empty2) = missing1.isEmpty() to missing2.isEmpty()
        when {
            empty1 && empty2 -> unify(restTy1, restTy2, span)
            empty1 && !empty2 -> unify(restTy2, Type.TRowExtend(missing2, restTy1), span)
            !empty1 && empty2 -> unify(restTy1, Type.TRowExtend(missing1, restTy2), span)
            !empty1 && !empty2 -> {
                when {
                    restTy1 is Type.TRowEmpty -> unify(restTy1, Type.TRowExtend(missing1, tc.newVar(0)), span)
                    restTy1 is Type.TVar && restTy1.tvar is TypeVar.Unbound -> {
                        val tv = restTy1.tvar as TypeVar.Unbound
                        val restRow = tc.newVar(tv.level)
                        unify(restTy2, Type.TRowExtend(missing2, restRow), span)
                        if (restTy1.tvar is TypeVar.Link) unificationError(Errors.RECURSIVE_ROWS, span)
                        unify(restTy1, Type.TRowExtend(missing1, restRow), span)
                    }
                    else -> unificationError(Errors.typesDontMatch(row1.show(false), row2.show(false)), span)
                }
            }
        }
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
            is Type.TRecord -> go(t.row)
            is Type.TRowExtend -> {
                t.labels.forEachList { go(it) }
                go(t.row)
            }
            is Type.TConst, is Type.TRowEmpty -> {
            }
        }
    }
    go(type)
}

fun substituteBoundVars(varIds: List<Id>, types: List<Type>, type: Type): Type {
    fun go(idMap: Map<Id, Type>, t: Type): Type = when (t) {
        is Type.TConst, is Type.TRowEmpty -> t
        is Type.TVar -> {
            when (val tv = t.tvar) {
                is TypeVar.Link -> go(idMap, tv.type)
                is TypeVar.Bound -> idMap[tv.id] ?: t
                else -> t
            }
        }
        is Type.TApp -> t.copy(go(idMap, t.type), t.types.map { go(idMap, it) })
        is Type.TArrow -> t.copy(t.args.map { go(idMap, it) }, go(idMap, t.ret))
        is Type.TForall -> {
            val map = mutableMapOf<Id, Type>()
            idMap.forEach { (k, v) -> if (k !in t.ids) map[k] = v }
            t.copy(t.ids, go(map, t.type))
        }
        is Type.TRecord -> t.copy(go(idMap, t.row))
        is Type.TRowExtend -> t.copy(row = go(idMap, t.row), labels = t.labels.mapList { go(idMap, it) })
    }
    return go(varIds.zip(types).toMap(), type)
}

fun freeGenericVars(type: Type): HashSet<Type> {
    val freeSet = HashSet<Type>()
    fun go(t: Type) {
        when (t) {
            is Type.TConst, is Type.TRowEmpty -> {
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
            is Type.TRecord -> go(t.row)
            is Type.TRowExtend -> {
                t.labels.forEachList { go(it) }
                go(t.row)
            }
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

fun matchRowType(ty: Type): Pair<PLabelMap<Type>, Type> = when (ty) {
    is Type.TRowEmpty -> PLabelMap<Type>() to Type.TRowEmpty().span(ty.span)
    is Type.TVar -> {
        when (val tv = ty.tvar) {
            is TypeVar.Link -> matchRowType(tv.type)
            else -> PLabelMap<Type>() to ty
        }
    }
    is Type.TRowExtend -> {
        val (restLabels, restTy) = matchRowType(ty.row)
        if (restLabels.isEmpty()) ty.labels to restTy
        else ty.labels.merge(restLabels) to restTy
    }
    else -> unificationError(Errors.notARow(ty.show(false)), ty.span ?: Span.empty())
}

fun addDistinctLabels(labelMap: PLabelMap<Type>, labels: PList<Pair<String, PList<Type>>>): PLabelMap<Type> {
    return labels.fold(labelMap) { acc, (s, l) -> acc.assocat(s, l) }
}

private fun unificationError(msg: String, span: Span): Nothing = inferError(msg, span, ProblemContext.UNIFICATION)