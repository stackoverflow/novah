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
import novah.frontend.error.ProblemContext
import novah.frontend.typechecker.Type.Companion.instantiateForall
import novah.frontend.typechecker.Typechecker.context
import novah.frontend.typechecker.Typechecker.freshName
import novah.frontend.typechecker.WellFormed.wfType
import novah.frontend.error.Errors as E

object Subsumption {

    private fun solve(x: TMeta, type: Type, span: Span) {
        if (!type.isMono()) subsumeError(E.cannotSolveWithPoly(x.show(false), type.show(false)), span)
        val right = context.split<CTMeta>(x.name)
        wfType(type, span)
        context.append(CTMeta(x.name, type)).concat(right)
    }

    private fun instL(x: TMeta, type: Type, span: Span) {
        val fork = context.fork()
        return try {
            solve(x, type, span)
        } catch (_: InferenceError) {
            context.resetTo(fork)
            when (type) {
                is TMeta -> solve(type, x, span)
                is TForall -> {
                    val y = freshName(type.name)
                    context.append(CTVar(y))
                    instL(x, instantiateForall(type, TVar(y)), span)
                }
                is TArrow -> {
                    val y = x.name
                    val a = freshName(y)
                    val b = freshName(y)
                    val ta = TMeta(a)
                    val tb = TMeta(b)
                    context.replace<CTMeta>(y, CTMeta(b), CTMeta(a), CTMeta(y, TArrow(ta, tb)))
                    instR(type.left, ta, span)
                    instL(tb, context.apply(type.right), span)
                }
                else -> subsumeError(E.typeIsNotInstance(x.show(false), type.show(false)), span)
            }
        }
    }

    private fun instR(type: Type, x: TMeta, span: Span) {
        val fork = context.fork()
        try {
            solve(x, type, span)
        } catch (_: InferenceError) {
            context.resetTo(fork)
            when (type) {
                is TMeta -> solve(type, x, span)
                is TForall -> {
                    val y = freshName(type.name)
                    instR(instantiateForall(type, TMeta(y)), x, span)
                }
                is TArrow -> {
                    val y = x.name
                    val a = freshName(y)
                    val b = freshName(y)
                    val ta = TMeta(a)
                    val tb = TMeta(b)
                    context.replace<CTMeta>(y, CTMeta(b), CTMeta(a), CTMeta(y, TArrow(ta, tb)))
                    instL(ta, type.left, span)
                    instR(context.apply(type.right), tb, span)
                }
                else -> subsumeError(E.typeIsNotInstance(x.show(false), type.show(false)), span)
            }
        }
    }

    fun subsume(a: Type, b: Type, span: Span) {
        // this covers TConst, TVar, TMeta and TRowEmpty
        if (a == b) return
        if (a is TArrow && b is TArrow) {
            subsume(a.left, b.left, span)
            return subsume(context.apply(a.right), context.apply(b.right), span)
        }
        if (a is TApp && b is TApp) {
            TODO("Unification")
        }
        if (a is TRecord && b is TRecord) {
            TODO("Unification")
        }
        if (a is TForall) {
            val x = freshName(a.name)
            context.append(CTVar(x))
            return subsume(instantiateForall(a, TMeta(x)), b, span)
        }
        if (b is TForall) {
            val x = freshName(b.name)
            context.append(CTVar(x))
            return subsume(a, instantiateForall(b, TMeta(x)), span)
        }
        if (a is TMeta) {
            if (b.containsMeta(a.name)) inferError(E.infiniteType(a.show(false)), span)
            return instL(a, b, span)
        }
        if (b is TMeta) {
            if (a.containsMeta(b.name)) inferError(E.infiniteType(b.show(false)), span)
            return instR(a, b, span)
        }
        subsumeError(E.typeIsNotInstance(a.show(false), b.show(false)), span)
    }

    private fun subsumeError(msg: String, span: Span): Nothing = inferError(msg, span, ProblemContext.SUBSUMPTION)
}