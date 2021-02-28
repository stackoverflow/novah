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

import novah.Util.hasDuplicates
import novah.Util.internalError
import novah.Util.validByte
import novah.Util.validShort
import novah.ast.canonical.*
import novah.data.forEachList
import novah.data.mapList
import novah.data.singletonPMap
import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.hmftypechecker.Subsumption.subsume
import novah.frontend.hmftypechecker.Typechecker.checkWellFormed
import novah.frontend.hmftypechecker.Typechecker.env
import novah.frontend.hmftypechecker.Typechecker.instantiate
import novah.frontend.hmftypechecker.Typechecker.instantiateTypeAnnotation
import novah.frontend.hmftypechecker.Typechecker.newBoundVar
import novah.frontend.hmftypechecker.Typechecker.newVar
import novah.frontend.hmftypechecker.Unification.unify
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

object Inference {

    /**
     * Infer the whole module
     */
    fun infer(ast: Module): ModuleEnv {
        val decls = mutableMapOf<String, DeclRef>()
        val types = mutableMapOf<String, TypeDeclRef>()

        // add the fixpoint operator to the context for recursive functions
        // TODO: add this to the env as primitive
        val (id, vvar) = newBoundVar()
        env.extend("\$fix", TForall(listOf(id), TArrow(listOf(TArrow(listOf(vvar), vvar)), vvar)))

        val datas = ast.decls.filterIsInstance<Decl.DataDecl>()
        datas.forEach { d ->
            val (ty, map) = getDataType(d, ast.name)
            checkShadowType(env, d.name, d.span)
            env.extendType("${ast.name}.${d.name}", ty)

            d.dataCtors.forEach { dc ->
                val dcty = getCtorType(dc, ty, map)
                checkShadow(env, dc.name, dc.span)
                env.extend(dc.name, dcty)
                decls[dc.name] = DeclRef(dcty, dc.visibility)
            }
            types[d.name] = TypeDeclRef(ty, d.visibility, d.dataCtors.map { it.name })
        }
        datas.forEach { d ->
            d.dataCtors.forEach { dc ->
                checkWellFormed(env.lookup(dc.name)!!, dc.span)
            }
        }

        val vals = ast.decls.filterIsInstance<Decl.ValDecl>()
        vals.forEach { decl ->
            val expr = decl.exp
            val name = decl.name
            checkShadow(env, name, decl.span)
            if (expr is Expr.Ann) {
                env.extend(name, expr.annType.second)
            } else {
                val t = newVar(0)
                env.extend(name, t)
            }
        }

        vals.forEach { decl ->
            val name = decl.name
            val newEnv = env.fork()
            val ty = if (decl.recursive) inferRecursive(name, decl.exp, newEnv, 0)
            else infer(newEnv, 0, decl.exp)

            val genTy = generalize(-1, ty)
            env.extend(name, genTy)
            decls[name] = DeclRef(genTy, decl.visibility)
        }

        return ModuleEnv(decls, types)
    }

    private fun infer(env: Env, level: Level, exp: Expr): Type {
        return when (exp) {
            is Expr.IntE -> exp.withType(tInt)
            is Expr.LongE -> exp.withType(tLong)
            is Expr.FloatE -> exp.withType(tFloat)
            is Expr.DoubleE -> exp.withType(tDouble)
            is Expr.CharE -> exp.withType(tChar)
            is Expr.Bool -> exp.withType(tBoolean)
            is Expr.StringE -> exp.withType(tString)
            is Expr.Unit -> exp.withType(tUnit)
            is Expr.Var -> {
                val ty = env.lookup(exp.name) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(ty)
            }
            is Expr.Constructor -> {
                val ty = env.lookup(exp.name) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(ty)
            }
            is Expr.NativeFieldGet -> {
                val ty = env.lookup(exp.name) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(ty)
            }
            is Expr.NativeFieldSet -> {
                val ty = env.lookup(exp.name) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(ty)
            }
            is Expr.NativeMethod -> {
                val ty = env.lookup(exp.name) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(ty)
            }
            is Expr.NativeConstructor -> {
                val ty = env.lookup(exp.name) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(ty)
            }
            is Expr.Lambda -> {
                val newEnv = env.fork()
                val param = when (val pat = exp.pattern) {
                    is FunparPattern.Bind -> {
                        checkShadow(newEnv, pat.binder.name, pat.binder.span)
                        val param = newVar(level + 1)
                        newEnv.extend(pat.binder.name, param)
                        param
                    }
                    is FunparPattern.Unit -> tUnit
                    is FunparPattern.Ignored -> newVar(level + 1)
                }

                val infRetType = infer(newEnv, level + 1, exp.body)
                val retType = if (isAnnotated(exp.body)) infRetType else instantiate(level + 1, infRetType)

                val ty = if (!param.isMono()) inferError(Errors.polyParameterToLambda(param.show(false)), exp.body.span)
                else generalize(level, TArrow(listOf(param), retType))
                exp.withType(ty)
            }
            is Expr.Let -> {
                val name = exp.letDef.binder.name
                val varType = when {
                    exp.letDef.type != null -> {
                        check(env, level + 1, exp.letDef.type, exp.letDef.expr)
                        exp.letDef.type
                    }
                    exp.letDef.recursive -> inferRecursive(name, exp.letDef.expr, env, level + 1)
                    else -> infer(env, level + 1, exp.letDef.expr)
                }
                checkShadow(env, name, exp.letDef.binder.span)
                env.extend(name, varType)
                val ty = infer(env, level, exp.body)
                exp.withType(ty)
            }
            is Expr.App -> {
                val nextLevel = level + 1
                val fnType = instantiate(nextLevel, infer(env, nextLevel, exp.fn))
                val (paramTypes, retType) = matchFunType(1, fnType, exp.fn.span)
                inferArg(env, nextLevel, paramTypes[0], exp.arg)
                val ty = generalize(level, instantiate(nextLevel, retType))
                exp.withType(ty)
            }
            is Expr.Ann -> {
                val (_, type) = instantiateTypeAnnotation(level, exp.annType.first, exp.annType.second)
                check(env, level, type, exp.exp)
                exp.withType(type)
            }
            is Expr.If -> {
                check(env, level + 1, tBoolean, exp.cond)
                val thenType = infer(env, level, exp.thenCase)
                val elseType = infer(env, level, exp.elseCase)
                unify(thenType, elseType, exp.span)
                exp.withType(thenType)
            }
            is Expr.Do -> {
                var ty: Type? = null
                val last = exp.exps.last()
                exp.exps.forEach { e ->
                    ty = if (e == last) infer(env, level, e)
                    else infer(env, level, e)
                }
                exp.withType(ty!!)
            }
            is Expr.Match -> {
                val expTy = infer(env, level, exp.exp)
                val resType = newVar(level)

                exp.cases.forEach { case ->
                    val vars = inferpattern(env, level, case.pattern, expTy)
                    val newEnv = if (vars.isNotEmpty()) {
                        val theEnv = env.fork()
                        vars.forEach { theEnv.extend(it.first, it.second) }
                        theEnv
                    } else env

                    val ty = infer(newEnv, level, case.exp)
                    unify(resType, ty, case.exp.span)
                }
                exp.withType(resType)
            }
            is Expr.RecordEmpty -> exp.withType(TRecord(TRowEmpty()))
            is Expr.RecordSelect -> {
                val restRow = newVar(level)
                val fieldTy = newVar(level)
                val paramType = TRecord(TRowExtend(singletonPMap(exp.label, fieldTy), restRow))
                val infered = infer(env, level, exp.exp)
                unify(paramType, infered, exp.span)
                exp.withType(fieldTy)
            }
            is Expr.RecordRestrict -> {
                val restRow = newVar(level)
                val fieldTy = newVar(level)
                val paramType = TRecord(TRowExtend(singletonPMap(exp.label, fieldTy), restRow))
                val retType = TRecord(restRow)
                unify(paramType, infer(env, level, exp.exp), exp.span)
                exp.withType(retType)
            }
            is Expr.RecordExtend -> {
                val labelTys = exp.labels.mapList { infer(env, level, it) }
                val restRow = newVar(level)
                unify(TRecord(restRow), infer(env, level, exp.exp), exp.span)
                val ty = TRecord(TRowExtend(labelTys, restRow))
                exp.withType(ty)
            }
            is Expr.VectorLiteral -> {
                if (exp.exps.isEmpty()) {
                    val type = newVar(level)
                    exp.withType(TApp(TConst(primVector), listOf(type)))
                } else {
                    val ty = newVar(level + 1)
                    exp.exps.forEach { e ->
                        val newTy = infer(env, level + 1, e)
                        unify(newTy, ty, e.span)
                    }
                    val res: Type = TApp(TConst(primVector), listOf(ty))
                    exp.withType(res)
                }
            }
            is Expr.SetLiteral -> {
                if (exp.exps.isEmpty()) {
                    val type = newVar(level)
                    exp.withType(TApp(TConst(primSet), listOf(type)))
                } else {
                    val ty = newVar(level + 1)
                    exp.exps.forEach { e ->
                        val newTy = infer(env, level + 1, e)
                        unify(newTy, ty, e.span)
                    }
                    val res: Type = TApp(TConst(primSet), listOf(ty))
                    exp.withType(res)
                }
            }
        }
    }

    private fun check(env: Env, level: Level, type: Type, exp: Expr) {
        when {
            exp is Expr.IntE && type == tByte && validByte(exp.v) -> exp.withType(tByte)
            exp is Expr.IntE && type == tShort && validShort(exp.v) -> exp.withType(tShort)
            exp is Expr.IntE && type == tInt -> exp.withType(tInt)
            exp is Expr.LongE && type == tLong -> exp.withType(tLong)
            exp is Expr.FloatE && type == tFloat -> exp.withType(tFloat)
            exp is Expr.DoubleE && type == tDouble -> exp.withType(tDouble)
            exp is Expr.StringE && type == tString -> exp.withType(tString)
            exp is Expr.CharE && type == tChar -> exp.withType(tChar)
            exp is Expr.Bool && type == tBoolean -> exp.withType(tBoolean)
            exp is Expr.Unit && type == tUnit -> exp.withType(tUnit)
            exp is Expr.Lambda && type is TArrow -> {
                when (val pat = exp.pattern) {
                    is FunparPattern.Ignored -> {
                        check(env, level + 1, type.ret, exp.body)
                    }
                    is FunparPattern.Unit -> {
                        subsume(level + 1, type.args[0], tUnit, exp.span)
                        check(env, level + 1, type.ret, exp.body)
                    }
                    is FunparPattern.Bind -> {
                        val newEnv = env.fork()
                        newEnv.extend(pat.binder.name, type.args[0])
                        check(newEnv, level + 1, type.ret, exp.body)
                    }
                }
                exp.withType(generalize(level, instantiate(level + 1, type)))
            }
            exp is Expr.App -> {
                val nextLevel = level + 1
                val fnType = instantiate(nextLevel, infer(env, nextLevel, exp.fn))
                val (paramTypes, retType) = matchFunType(1, fnType, exp.fn.span)
                val instReturnTy = instantiate(nextLevel, retType)
                if (!type.isUnbound()) {
                    unify(instantiate(nextLevel, type), instReturnTy, exp.span)
                }
                inferArg(env, nextLevel, paramTypes[0], exp.arg)
                val ty = generalize(level, instReturnTy)
                exp.withType(ty)
            }
            exp is Expr.VectorLiteral && type is TApp && type.type.isConst(primVector) && type.types.size == 1 -> {
                val innerTy = type.types[0]
                exp.exps.forEach { check(env, level + 1, innerTy, it) }
                exp.withType(type)
            }
            exp is Expr.SetLiteral && type is TApp && type.type.isConst(primSet) && type.types.size == 1 -> {
                val innerTy = type.types[0]
                exp.exps.forEach { check(env, level + 1, innerTy, it) }
                exp.withType(type)
            }
            else -> {
                validateType(type, env, exp.span)
                subsume(level, type, infer(env, level, exp), exp.span)
                exp.withType(type)
            }
        }
    }

    private fun inferArg(env: Env, level: Level, paramType: Type, arg: Expr) {
        val argType = infer(env, level, arg)
        if (isAnnotated(arg)) unify(paramType, argType, arg.span)
        else subsume(level, paramType, argType, arg.span)
    }

    private fun inferpattern(env: Env, level: Level, pat: Pattern, ty: Type): List<Pair<String, Type>> {
        tailrec fun peelArgs(args: List<Type>, t: Type): Pair<List<Type>, Type> = when (t) {
            is TArrow -> {
                if (t.ret is TArrow) peelArgs(args + t.args, t.ret)
                else args + t.args to t.ret
            }
            else -> args to t
        }
        return when (pat) {
            is Pattern.LiteralP -> {
                val type = infer(env, level, pat.lit.e)
                unify(ty, type, pat.lit.e.span)
                emptyList()
            }
            is Pattern.Wildcard -> emptyList()
            is Pattern.Var -> {
                checkShadow(env, pat.name, pat.span)
                listOf(pat.name to ty)
            }
            is Pattern.Ctor -> {
                val cty = infer(env, level, pat.ctor)

                val (ctorTypes, ret) = peelArgs(listOf(), instantiate(level, cty))
                unify(ret, ty, pat.ctor.span)

                if (ctorTypes.size - pat.fields.size != 0)
                    internalError("unified two constructors with wrong kinds: $pat")

                if (ctorTypes.isEmpty()) emptyList()
                else {
                    val vars = mutableListOf<Pair<String, Type>>()
                    ctorTypes.zip(pat.fields).forEach { (type, pattern) ->
                        vars.addAll(inferpattern(env, level, pattern, type))
                    }
                    val varNames = vars.map { it.first }
                    if (varNames.hasDuplicates()) inferError(Errors.overlappingNamesInBinder(varNames), pat.span)
                    vars
                }
            }
        }
    }

    private fun matchFunType(numParams: Int, t: Type, span: Span): Pair<List<Type>, Type> = when {
        t is TArrow -> {
            if (numParams != t.args.size) internalError("unexpected number of arguments to function: $numParams")
            t.args to t.ret
        }
        t is TVar && t.tvar is TypeVar.Link -> matchFunType(numParams, (t.tvar as TypeVar.Link).type, span)
        t is TVar && t.tvar is TypeVar.Unbound -> {
            val unb = t.tvar as TypeVar.Unbound
            val params = (1..numParams).map { newVar(unb.level) }.reversed()
            val retur = newVar(unb.level)
            t.tvar = TypeVar.Link(TArrow(params, retur).span(t.span))
            params to retur
        }
        else -> inferError(Errors.NOT_A_FUNCTION, span)
    }

    /**
     * Use a fixpoint operator to infer a recursive function
     */
    private fun inferRecursive(name: String, exp: Expr, env: Env, level: Level): Type {
        val (newName, newExp) = funToFixpoint(name, exp)
        val recTy = infer(env, level, newExp)
        env.extend(newName, recTy)
        val fix = Expr.App(Expr.Var("\$fix", exp.span), Expr.Var(newName, exp.span), exp.span)
        return infer(env, level, fix)
    }

    private fun generalize(level: Level, type: Type): Type {
        val ids = mutableListOf<Id>()
        fun go(t: Type) {
            when {
                t is TVar && t.tvar is TypeVar.Link -> go((t.tvar as TypeVar.Link).type)
                t is TVar && t.tvar is TypeVar.Generic -> internalError("unexpected generic var in generalize")
                t is TVar && t.tvar is TypeVar.Unbound -> {
                    val unb = t.tvar as TypeVar.Unbound
                    if (unb.level > level) {
                        t.tvar = TypeVar.Bound(unb.id)
                        if (unb.id !in ids) {
                            ids += unb.id
                        }
                    }
                }
                t is TApp -> {
                    go(t.type)
                    t.types.forEach(::go)
                }
                t is TArrow -> {
                    t.args.forEach(::go)
                    go(t.ret)
                }
                t is TForall -> go(t.type)
                t is TRecord -> go(t.row)
                t is TRowExtend -> {
                    t.labels.forEachList { go(it) }
                    go(t.row)
                }
                t is TConst || t is TRowEmpty -> {
                }
            }
        }

        go(type)
        return if (ids.isEmpty()) type
        else TForall(ids, type).span(type.span)
    }

    private tailrec fun isAnnotated(exp: Expr): Boolean = when (exp) {
        is Expr.Ann -> true
        is Expr.Let -> isAnnotated(exp.body)
        else -> false
    }

    private fun validateType(type: Type, env: Env, span: Span) {
        type.everywhere { ty ->
            if (ty is TConst && env.lookupType(ty.name) == null) {
                inferError(Errors.undefinedType(ty.show(false)), ty.span ?: span)
            }
        }
    }

    /**
     * Change this recursive expression in a way that
     * it can be infered.
     *
     * Ex.: fun x = fun x
     *      $fun fun$rec x = fun$rec x
     */
    private fun funToFixpoint(name: String, expr: Expr): Pair<String, Expr> {
        val binder = "${name}\$rec"
        return binder to Expr.Lambda(
            lambdaBinder("\$$name", expr.span),
            null,
            expr.substVar(name, "\$$name"),
            expr.span
        )
    }

    /**
     * Check if `name` is shadowing some variable
     * and throw an error if that's the case.
     */
    private fun checkShadow(env: Env, name: String, span: Span) {
        if (env.lookup(name) != null) inferError(Errors.shadowedVariable(name), span)
    }

    /**
     * Check if `type` is shadowing some other type
     * and throw an error if that's the case.
     */
    private fun checkShadowType(env: Env, type: String, span: Span) {
        if (env.lookupType(type) != null) inferError(Errors.duplicatedType(type), span)
    }

    private fun getDataType(d: Decl.DataDecl, moduleName: String): Pair<Type, Map<String, TVar>> {
        val kind = if (d.tyVars.isEmpty()) Kind.Star else Kind.Constructor(d.tyVars.size)
        val raw = TConst("$moduleName.${d.name}", kind).span(d.span)

        return if (d.tyVars.isEmpty()) raw to emptyMap()
        else {
            val varsMap = d.tyVars.map { it to newBoundVar() }
            val map = mutableMapOf<String, TVar>()
            varsMap.forEach { (name, idVar) -> map[name] = idVar.second }
            val vars = varsMap.map { it.second }

            TForall(vars.map { it.first }, TApp(raw, vars.map { it.second })).span(d.span) to map
        }
    }

    private fun getCtorType(dc: DataConstructor, dataType: Type, map: Map<String, TVar>): Type {
        return when (dataType) {
            is TConst -> if (dc.args.isEmpty()) dataType else Type.nestArrows(dc.args, dataType).span(dc.span)
            is TForall -> {
                val args = dc.args.map { it.substConst(map) }
                TForall(dataType.ids, Type.nestArrows(args, dataType.type)).span(dc.span)
            }
            else -> internalError("Got absurd type for data constructor: $dataType")
        }
    }
}