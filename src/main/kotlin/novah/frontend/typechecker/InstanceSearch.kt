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
import novah.frontend.typechecker.Typechecker.context
import novah.frontend.typechecker.Typechecker.instantiate
import novah.frontend.typechecker.Unification.unify

object InstanceSearch {

    private const val MAX_DEPTH = 5

    fun instanceSearch(apps: List<Expr.App>) {
        for (app in apps) {
            context?.apply { exps.push(app) }
            val impCtx = app.implicitContext!!
            val (imps, _) = peelImplicits(impCtx.type)

            val resolved = imps.map { find(impCtx.env, instantiate(0, it), 0, app.span) }
            impCtx.resolveds.addAll(resolved)
            //println("resolved ${impCtx.type.show()} with $resolved")
            context?.apply { exps.pop() }
        }
    }

    private fun find(env: Env, ty: Type, depth: Int, span: Span): Expr {
        if (depth > MAX_DEPTH) inferError(Errors.maxSearchDepth(ty.show()), span)

        var exp: Expr? = null
        val candidates = findCandidates(env, ty, span)
        val isUnbound = unboundImplicit(ty)
        candidates.forEach { (name, type) ->
            val found = try {
                val instTy = instantiate(0, type)
                val (imps, ret) = peelImplicits(instTy)
                if (imps.isNotEmpty() && !isUnbound) {
                    unify(ret, ty, span)
                    val founds = imps.map { t -> find(env, t, depth + 1, span) }
                    val v = mkVar(name, type, span)
                    nestApps(listOf(v) + founds)
                } else {
                    unify(instTy, ty, span) 
                    mkVar(name, type, span)
                }
            } catch (_: InferenceError) {
                null
            }
            if (found != null) {
                if (exp != null) inferError(Errors.overlappingInstances(showImplicit(ty)), span)
                exp = found
            }
        }
        if (exp == null) inferError(Errors.noInstanceFound(showImplicit(ty)), span)
        return exp!!
    }

    private fun findCandidates(env: Env, ty: Type, span: Span): List<Pair<String, Type>> {
        val cands = mutableListOf<Pair<String, Type>>()
        val tname = typeName(ty) ?: inferError(Errors.unamedType(ty.show()), span)
        env.forEachInstance { name, type ->
            if (tname == typeName(type)) cands += name to type
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
        else -> null
    }

    private tailrec fun unboundImplicit(ty: Type): Boolean = when (ty) {
        is TImplicit -> unboundImplicit(ty.type)
        is TConst -> true
        is TApp -> ty.types.all { isUnbound(it) }
        else -> false
    }

    private tailrec fun isUnbound(ty: Type): Boolean = when (ty) {
        is TVar -> {
            when (val tv = ty.tvar) {
                is TypeVar.Unbound -> true
                is TypeVar.Link -> isUnbound(tv.type)
                else -> false
            }
        }
        else -> false
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