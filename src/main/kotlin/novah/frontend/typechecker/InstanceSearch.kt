/**
 * Copyright 2022 Islon Scherer
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

class InstanceSearch(private val tc: Typechecker) {

    private val uni = tc.uni

    fun instanceSearch(apps: List<Expr>) {
        for (app in apps) {
            val impCtx = app.implicitContext!!

            val resolved = impCtx.types.map { find(impCtx.env, it, 0, app.span) }
            impCtx.resolveds.addAll(resolved)
        }
    }

    private fun find(env: Env, ty: Type, depth: Int, span: Span): Expr {
        if (depth > MAX_DEPTH) inferError(Errors.maxSearchDepth(ty.show()), span)

        val candidates = findCandidates(env, ty, span)
        val genTy = tc.infer.generalize(-1, ty)

        fun checkImplicit(name: String, impType: Type): Expr? {
            return try {
                val (imps, ret) = peelImplicits(impType)
                if (imps.isNotEmpty()) {
                    uni.unifySimple(ret, genTy, span)
                    val founds = imps.map { t -> find(env, t, depth + 1, span) }
                    val v = mkVar(name, impType, span)
                    nestApps(listOf(v) + founds)
                } else {
                    uni.unifySimple(impType, genTy, span)
                    mkVar(name, impType, span)
                }
            } catch (_: Unification.UnifyException) {
                null
            }
        }

        val res = candidates.mapNotNull { (name, ienv) ->
            checkImplicit(name, ienv.type) ?: if (!ienv.isLambdaVar) {
                checkImplicit(name, tc.instantiate(0, ienv.type))
            } else null
        }

        if (res.size == 1) return res[0]
        if (res.size > 1) inferError(Errors.overlappingInstances(showImplicit(ty)), span)
        inferError(Errors.noInstanceFound(showImplicit(ty)), span)
    }

    private fun findCandidates(env: Env, ty: Type, span: Span): List<Pair<String, InstanceEnv>> {
        val cands = mutableListOf<Pair<String, InstanceEnv>>()
        val tname = typeName(ty) ?: inferError(Errors.unamedType(ty.show()), span)
        env.forEachInstance { name, ienv ->
            if (isCandidate(tname, ienv)) cands += name to ienv
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

    /**
     * Returns the name of this type if it's a named type.
     */
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

    /**
     * An instance is a candidate for the type named tname if,
     * either they are the same type (names match), or if
     * the instance is an unbound implicit lambda parameter.
     */
    private fun isCandidate(tname: String, ienv: InstanceEnv): Boolean {
        if (ienv.typeName == null) ienv.typeName = typeName(ienv.type)

        if (tname == ienv.typeName) return true
        if (!ienv.isLambdaVar) return false

        return ienv.type is TImplicit && ienv.type.type.isUnbound()
    }

    private tailrec fun getReturn(ty: Type): Type = when (ty) {
        is TArrow -> ty.ret
        is TVar -> {
            if (ty.tvar is TypeVar.Link) getReturn((ty.tvar as TypeVar.Link).type)
            else ty
        }
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

    companion object {
        private const val MAX_DEPTH = 5
    }
}