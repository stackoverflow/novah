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

import novah.Util.splitAt
import novah.ast.canonical.Expr
import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.typechecker.Inference.generalize
import novah.frontend.typechecker.Typechecker.context
import novah.frontend.typechecker.Typechecker.instantiate
import novah.frontend.typechecker.Unification.unify

object InstanceSearch {

    private const val MAX_DEPTH = 5

    fun instanceSearch(apps: List<Expr>) {
        for (app in apps) {
            context?.apply { exps.push(app) }
            val impCtx = app.implicitContext!!
            val imps = impCtx.types

            val resolved = imps.map { find(impCtx.env, it, 0, app.span) }
            impCtx.resolveds.addAll(resolved)
            //println("resolved ${imps.joinToString { it.show() }} with $resolved at ${app.span}")
            context?.apply { exps.pop() }
        }
    }

    private sealed class Res {
        class Yes(val exp: Expr) : Res()
        class Maybe(val exp: Expr) : Res()
        object No : Res()
    }

    private fun find(env: Env, ty: Type, depth: Int, span: Span): Expr {
        if (depth > MAX_DEPTH) inferError(Errors.maxSearchDepth(ty.show()), span)

        val candidates = findCandidates(env, ty, span)
        val genTy = generalize(-1, ty)
        
        fun checkImplicit(name: String, impType: Type): Expr? {
            return try {
                val (imps, ret) = peelImplicits(impType)
                if (imps.isNotEmpty()) {
                    unify(ret, genTy, span)
                    val founds = imps.map { t -> find(env, t, depth + 1, span) }
                    val v = mkVar(name, impType, span)
                    nestApps(listOf(v) + founds)
                } else {
                    unify(impType, genTy, span)
                    mkVar(name, impType, span)
                }
            } catch (_: InferenceError) {
                null
            }
        }

        val res = candidates.map { (name, ienv) ->
            val found = checkImplicit(name, ienv.type)
            if (found != null) {
                Res.Yes(found)
            } else if (!ienv.isVar) {
                val found2 = checkImplicit(name, instantiate(0, ienv.type))
                if (found2 != null) Res.Maybe(found2) else Res.No
            } else Res.No
        }
        val yeses = res.filterIsInstance<Res.Yes>()
        if (yeses.size == 1) return yeses[0].exp
        if (yeses.size > 1) inferError(Errors.overlappingInstances(showImplicit(ty)), span)

        val maybes = res.filterIsInstance<Res.Maybe>()
        if (maybes.size == 1) return maybes[0].exp
        if (maybes.size > 1) inferError(Errors.overlappingInstances(showImplicit(ty)), span)
        inferError(Errors.noInstanceFound(showImplicit(ty)), span)
    }

    private fun findCandidates(env: Env, ty: Type, span: Span): List<Pair<String, InstanceEnv>> {
        val cands = mutableListOf<Pair<String, InstanceEnv>>()
        val tname = typeName(ty) ?: inferError(Errors.unamedType(ty.show()), span)
        env.forEachInstance { name, ienv ->
            if (tname == typeName(ienv.type)) cands += name to ienv
        }
        return cands.sortedBy { it.first }
    }

    private fun peelImplicits(ty: Type): Pair<List<Type>, Type> = when (ty) {
        is TArrow -> if (ty.args.all { it is TImplicit }) {
            val (ts, t) = peelImplicits(ty.ret)
            ty.args + ts to t
        } else emptyList<Type>() to ty
        else -> emptyList<Type>() to ty
    }

    private tailrec fun typeName(ty: Type): String? = when (ty) {
        is TConst -> ty.name
        is TApp -> typeName(ty.type)
        is TImplicit -> typeName(ty.type)
        is TArrow -> if (ty.args.all { it is TImplicit }) typeName(ty.ret) else null
        is TVar -> if (ty.tvar is TypeVar.Link) typeName((ty.tvar as TypeVar.Link).type) else null
        else -> null
    }

    private fun nestApps(exps: List<Expr>): Expr =
        exps.reversed().reduceRight { exp, acc ->
            Expr.App(acc, exp, acc.span).apply { type = getReturn(acc.type!!) }
        }

    private tailrec fun getReturn(ty: Type): Type = when (ty) {
        is TArrow -> getReturn(ty.ret)
        else -> ty
    }

    private fun showImplicit(ty: Type): String = when (ty) {
        is TImplicit -> ty.type.show()
        else -> ty.show()
    }

    private fun mkVar(name: String, ty: Type, span: Span): Expr {
        val idx = name.lastIndexOf('.')
        val (mod, nam) = if (idx != -1) name.splitAt(idx) else null to name
        return Expr.Var(nam, span, mod).apply { this.type = ty }
    }
}