package novah.frontend.typechecker

import novah.Util.internalError
import novah.data.*
import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext

object Unification {

    fun unify(t1: Type, t2: Type, span: Span) {
        when {
            t1 == t2 -> {
            }
            t1 is TConst && t2 is TConst && t1.name == t2.name -> {
            }
            t1 is TApp && t2 is TApp -> {
                unify(t1.type, t2.type, span)
                if (t1.types.size != t2.types.size)
                    unificationError(Errors.typesDontMatch(t1.show(), t2.show()), span)
                t1.types.zip(t2.types).forEach { (tt1, tt2) -> unify(tt1, tt2, span) }
            }
            t1 is TArrow && t2 is TArrow -> {
                if (t1.args.size != t2.args.size)
                    unificationError(Errors.typesDontMatch(t1.show(), t2.show()), span)
                t1.args.zip(t2.args).forEach { (t1arg, t2arg) -> unify(t1arg, t2arg, span) }
                unify(t1.ret, t2.ret, span)
            }
            t1 is TVar && t1.tvar is TypeVar.Link -> unify((t1.tvar as TypeVar.Link).type, t2, span)
            t2 is TVar && t2.tvar is TypeVar.Link -> unify(t1, (t2.tvar as TypeVar.Link).type, span)
            t1 is TVar && t2 is TVar && t1.tvar is TypeVar.Unbound && t2.tvar is TypeVar.Unbound
                    && (t1.tvar as TypeVar.Unbound).id == (t2.tvar as TypeVar.Unbound).id -> {
                internalError("Error in unification: ${t1.show()} with ${t2.show()}")
            }
            t1 is TVar && t1.tvar is TypeVar.Unbound -> {
                val tvar = t1.tvar as TypeVar.Unbound
                occursCheckAndAdjustLevels(tvar.id, tvar.level, t2, span)
                t1.tvar = TypeVar.Link(t2)
            }
            t2 is TVar && t2.tvar is TypeVar.Unbound -> {
                val tvar = t2.tvar as TypeVar.Unbound
                occursCheckAndAdjustLevels(tvar.id, tvar.level, t1, span)
                t2.tvar = TypeVar.Link(t1)
            }
            t1 is TRowEmpty && t2 is TRowEmpty -> {
            }
            t1 is TRecord && t2 is TRecord -> unify(t1.row, t2.row, span)
            t1 is TRowExtend && t2 is TRowExtend -> unifyRows(t1, t2, span)
            t1 is TRowEmpty && t2 is TRowExtend -> {
                unificationError(Errors.recordMissingLabels(t2.labels.keys().toList()), span)
            }
            t1 is TRowExtend && t2 is TRowEmpty -> {
                unificationError(Errors.recordMissingLabels(t1.labels.keys().toList()), span)
            }
            t1 is TImplicit -> unify(t1.type, t2, span)
            t2 is TImplicit -> unify(t1, t2.type, span)
            else -> unificationError(Errors.typesDontMatch(t1.show(), t2.show()), span)
        }
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
            missing1: LabelMap<Type>,
            missing2: LabelMap<Type>,
            labels1: PList<Pair<String, PList<Type>>>,
            labels2: PList<Pair<String, PList<Type>>>
        ): Pair<LabelMap<Type>, LabelMap<Type>> = when {
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

        val (missing1, missing2) = unifyLabels(LabelMap(), LabelMap(), labels1.toPList(), labels2.toPList())

        val (empty1, empty2) = missing1.isEmpty() to missing2.isEmpty()
        when {
            empty1 && empty2 -> unify(restTy1, restTy2, span)
            empty1 && !empty2 -> unify(restTy2, TRowExtend(missing2, restTy1), span)
            !empty1 && empty2 -> unify(restTy1, TRowExtend(missing1, restTy2), span)
            !empty1 && !empty2 -> {
                when {
                    restTy1 is TRowEmpty -> unify(restTy1, TRowExtend(missing1, Typechecker.newVar(0)), span)
                    restTy1 is TVar && restTy1.tvar is TypeVar.Unbound -> {
                        val tv = restTy1.tvar as TypeVar.Unbound
                        val restRow = Typechecker.newVar(tv.level)
                        unify(restTy2, TRowExtend(missing2, restRow), span)
                        if (restTy1.tvar is TypeVar.Link) unificationError(Errors.RECURSIVE_ROWS, span)
                        unify(restTy1, TRowExtend(missing1, restRow), span)
                    }
                    else -> unificationError(Errors.typesDontMatch(row1.show(), row2.show()), span)
                }
            }
        }
    }

    private fun occursCheckAndAdjustLevels(id: Id, level: Level, type: Type, span: Span) {
        fun go(t: Type) {
            when (t) {
                is TVar -> {
                    when (val tv = t.tvar) {
                        is TypeVar.Link -> go(tv.type)
                        is TypeVar.Generic -> {
                        }
                        is TypeVar.Unbound -> {
                            if (id == tv.id) {
                                inferError(Errors.infiniteType(type.show()), span, ProblemContext.UNIFICATION)
                            } else if (tv.level > level) {
                                t.tvar = TypeVar.Unbound(tv.id, level)
                            }
                        }
                    }
                }
                is TApp -> {
                    go(t.type)
                    t.types.forEach(::go)
                }
                is TArrow -> {
                    t.args.forEach(::go)
                    go(t.ret)
                }
                is TRecord -> go(t.row)
                is TRowExtend -> {
                    t.labels.forEachList { go(it) }
                    go(t.row)
                }
                is TImplicit -> go(t.type)
                is TConst, is TRowEmpty -> {
                }
            }
        }
        go(type)
    }

    private fun matchRowType(ty: Type): Pair<LabelMap<Type>, Type> = when (ty) {
        is TRowEmpty -> LabelMap<Type>() to TRowEmpty().span(ty.span)
        is TVar -> {
            when (val tv = ty.tvar) {
                is TypeVar.Link -> matchRowType(tv.type)
                else -> LabelMap<Type>() to ty
            }
        }
        is TRowExtend -> {
            val (restLabels, restTy) = matchRowType(ty.row)
            if (restLabels.isEmpty()) ty.labels to restTy
            else ty.labels.merge(restLabels) to restTy
        }
        else -> unificationError(Errors.notARow(ty.show()), ty.span ?: Span.empty())
    }

    private fun addDistinctLabels(labelMap: LabelMap<Type>, labels: PList<Pair<String, PList<Type>>>): LabelMap<Type> {
        return labels.fold(labelMap) { acc, (s, l) ->
            if (acc.contains(s)) internalError("Label map already contains label $s")
            acc.assocat(s, l)
        }
    }
}

private fun unificationError(msg: String, span: Span): Nothing =
    inferError(msg, span, ProblemContext.UNIFICATION)