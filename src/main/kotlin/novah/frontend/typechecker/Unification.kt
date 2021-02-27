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
package novah.frontend.typechecker

import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.frontend.typechecker.Subsumption.subsume
import novah.frontend.typechecker.Typechecker.context
import novah.frontend.typechecker.WellFormed.wfType

object Unification {

    private fun solveUnify(x: TMeta, type: Type, span: Span) {
        val right = context.split<CTMeta>(x.name)
        wfType(type, span)
        context.append(CTMeta(x.name, type)).concat(right)
    }

    fun unify(a: Type, b: Type, span: Span) {
        when {
            a == b -> {
            }
            a is TApp && b is TApp -> {
                unify(a.left, b.left, span)
                unify(context.apply(a.right), context.apply(b.right), span)
            }
            a is TArrow && b is TArrow -> {
                unify(a.left, b.left, span)
                unify(context.apply(a.right), context.apply(b.right), span)
            }
            a is TMeta && b is TMeta -> {
                val (i1, i2) = context.indexOf<CTMeta>(a.name) to context.indexOf<CTMeta>(b.name)
                if (i1 < i2) solveUnify(a, b, span) else solveUnify(b, a, span)
            }
            a is TMeta -> {
                if (b.containsMeta(a.name)) inferError(Errors.infiniteType(a.show(false)), span)
                solveUnify(a, b, span)
            }
            b is TMeta -> {
                if (a.containsMeta(b.name)) inferError(Errors.infiniteType(b.show(false)), span)
                solveUnify(b, a, span)
            }
            a is TForall && b is TForall -> {
                val x = Typechecker.freshName(a.name + b.name)
                context.append(CTVar(x))
                unify(Type.instantiateForall(a, TMeta(x)), Type.instantiateForall(b, TMeta(x)), span)
            }
            a is TForall -> {
                val xa = Typechecker.freshName(a.name)
                val fork = context.fork()
                context.append(CTVar(xa))
                subsume(Type.instantiateForall(a, TMeta(xa)), b, span)
                context.resetTo(fork)
            }
            b is TForall -> {
                val xb = Typechecker.freshName(b.name)
                val fork = context.fork()
                context.append(CTVar(xb))
                subsume(a, Type.instantiateForall(b, TMeta(xb)), span)
                context.resetTo(fork)
            }
            a is TRowEmpty && b is TRowEmpty -> {
            }
            a is TRecord && b is TRecord -> unify(a.row, b.row, span)
            a is TRowEmpty && b is TRowExtend -> {
                unificationError(Errors.recordMissingLabels(b.labels.keys().toList()), span)
            }
            a is TRowExtend && b is TRowEmpty -> {
                unificationError(Errors.recordMissingLabels(a.labels.keys().toList()), span)
            }
            else -> unificationError(Errors.typesDontMatch(a.show(false), b.show(false)), span)
        }
    }

    // Unify two row types
//    private fun unifyRows(row1: Type, row2: Type, span: Span) {
//        val (labels1, restTy1) = matchRowType(row1)
//        val (labels2, restTy2) = matchRowType(row2)
//
//        tailrec fun unifyTypes(t1s: PList<Type>, t2s: PList<Type>): Pair<PList<Type>, PList<Type>> = when {
//            t1s.isEmpty() || t2s.isEmpty() -> t1s to t2s
//            else -> {
//                unify(t1s.first(), t2s.first(), span)
//                unifyTypes(t1s.removeFirst(), t2s.removeFirst())
//            }
//        }
//
//        tailrec fun unifyLabels(
//            missing1: PLabelMap<Type>,
//            missing2: PLabelMap<Type>,
//            labels1: PList<Pair<String, PList<Type>>>,
//            labels2: PList<Pair<String, PList<Type>>>
//        ): Pair<PLabelMap<Type>, PLabelMap<Type>> = when {
//            labels1.isEmpty() && labels2.isEmpty() -> missing1 to missing2
//            labels1.isEmpty() -> addDistinctLabels(missing1, labels2) to missing2
//            labels2.isEmpty() -> missing1 to addDistinctLabels(missing2, labels1)
//            else -> {
//                val (label1, tys1) = labels1.first()
//                val (label2, tys2) = labels2.first()
//                val rest1 = labels1.removeFirst()
//                val rest2 = labels2.removeFirst()
//                when {
//                    label1 == label2 -> {
//                        val (m1s, m2s) = unifyTypes(tys1, tys2)
//                        val (missing11, missing22) = when {
//                            m1s.isEmpty() && m2s.isEmpty() -> missing1 to missing2
//                            m2s.isEmpty() -> missing1 to missing2.put(label1, m1s)
//                            m1s.isEmpty() -> missing1.put(label2, m2s) to missing2
//                            else -> Util.internalError("impossible")
//                        }
//                        unifyLabels(missing11, missing22, rest1, rest2)
//                    }
//                    label1 < label2 -> unifyLabels(missing1, missing2.put(label1, tys1), rest1, labels2)
//                    else -> unifyLabels(missing1.put(label2, tys2), missing2, labels1, rest2)
//                }
//            }
//        }
//
//        val (missing1, missing2) = unifyLabels(PLabelMap(), PLabelMap(), labels1.toPList(), labels2.toPList())
//
//        val (empty1, empty2) = missing1.isEmpty() to missing2.isEmpty()
//        when {
//            empty1 && empty2 -> unify(restTy1, restTy2, span)
//            empty1 && !empty2 -> unify(restTy2, TRowExtend(missing2, restTy1), span)
//            !empty1 && empty2 -> unify(restTy1, TRowExtend(missing1, restTy2), span)
//            !empty1 && !empty2 -> {
//                when {
//                    restTy1 is TRowEmpty -> unify(restTy1, TRowExtend(missing1, tc.newVar(0)), span)
//                    restTy1 is TVar && restTy1.tvar is TypeVar.Unbound -> {
//                        val tv = restTy1.tvar as TypeVar.Unbound
//                        val restRow = tc.newVar(tv.level)
//                        unify(restTy2, TRowExtend(missing2, restRow), span)
//                        if (restTy1.tvar is TypeVar.Link) unificationError(
//                            Errors.RECURSIVE_ROWS,
//                            span
//                        )
//                        unify(restTy1, TRowExtend(missing1, restRow), span)
//                    }
//                    else -> unificationError(
//                        Errors.typesDontMatch(
//                            row1.show(false),
//                            row2.show(false)
//                        ), span
//                    )
//                }
//            }
//        }
//    }

    private fun unificationError(msg: String, span: Span): Nothing = inferError(msg, span, ProblemContext.UNIFICATION)
}