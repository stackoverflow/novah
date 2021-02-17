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
import novah.ast.canonical.*
import novah.data.forEachList
import novah.data.mapList
import novah.data.singletonPMap
import novah.frontend.Span
import novah.frontend.error.Errors
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

class Inference(private val tc: Typechecker, private val uni: Unification, private val sub: Subsumption) {

    private enum class Gen {
        GENERALIZED, INSTANTIATED;
    }

    /**
     * Infer the whole module
     */
    fun infer(ast: Module): ModuleEnv {
        val decls = mutableMapOf<String, DeclRef>()
        val types = mutableMapOf<String, TypeDeclRef>()
        val env = tc.env

        // add the fixpoint operator to the context for recursive functions
        // TODO: add this to the env as primitive
        val (id, vvar) = tc.newBoundVar()
        env.extend("\$fix", Type.TForall(listOf(id), Type.TArrow(listOf(Type.TArrow(listOf(vvar), vvar)), vvar)))

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
                tc.checkWellFormed(env.lookup(dc.name)!!, dc.span)
            }
        }

        val vals = ast.decls.filterIsInstance<Decl.ValDecl>()
        vals.forEach { decl ->
            val expr = decl.exp
            val name = decl.name
            checkShadow(tc.env, name, decl.span)
            if (expr is Expr.Ann) {
                env.extend(name, expr.annType.second)
            } else {
                val t = tc.newVar(0)
                env.extend(name, t)
            }
        }

        vals.forEach { decl ->
            val name = decl.name
            val newEnv = env.makeExtension()
            val ty = if (decl.recursive) inferRecursive(name, decl.exp, newEnv, 0)
            else infer(newEnv, 0, null, Gen.GENERALIZED, decl.exp)

            val genTy = generalize(-1, ty)
            env.extend(name, genTy)
            decls[name] = DeclRef(genTy, decl.visibility)
        }

        return ModuleEnv(decls, types)
    }

    private data class Quadruple(
        val pat: FunparPattern,
        val ann: Pair<List<Id>, Type>?,
        val retType: Type?,
        val gene: Gen
    )

    private fun infer(env: Env, level: Level, expectedType: Type?, generalized: Gen, exp: Expr): Type {
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
                exp.withType(maybeInstantiate(generalized, level, ty))
            }
            is Expr.Constructor -> {
                val ty = env.lookup(exp.name) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(maybeInstantiate(generalized, level, ty))
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
                val (expectedParam, ann, expectedReturn, genBody) = if (expectedType == null) {
                    Quadruple(exp.pattern, exp.ann, null, Gen.INSTANTIATED)
                } else {
                    when (val res = tc.instantiate(level + 1, expectedType)) {
                        is Type.TArrow -> {
                            Quadruple(
                                exp.pattern,
                                exp.ann ?: emptyList<Id>() to res.args[0],
                                res.ret,
                                shouldGeneralize(res.ret)
                            )
                        }
                        else -> Quadruple(exp.pattern, exp.ann, null, Gen.INSTANTIATED)
                    }
                }
                val newEnv = env.makeExtension()
                val vars = mutableListOf<Type>()
                val param = when (expectedParam) {
                    is FunparPattern.Bind -> {
                        checkShadow(newEnv, expectedParam.binder.name, expectedParam.binder.span)
                        val paramTy = if (ann == null) {
                            val param = tc.newVar(level + 1)
                            vars += param
                            param
                        } else {
                            val (varList, ty) = tc.instantiateTypeAnnotation(level + 1, ann.first, ann.second)
                            vars += varList
                            ty
                        }
                        newEnv.extend(expectedParam.binder.name, paramTy)
                        paramTy
                    }
                    is FunparPattern.Unit -> tUnit
                    is FunparPattern.Ignored -> tc.newVar(level + 1)
                }

                val retType = infer(newEnv, level + 1, expectedReturn, genBody, exp.body)

                val ty = if (!vars.all { it.isMono() }) inferError(
                    Errors.polyParameterToLambda(vars[0].show(false)),
                    exp.body.span
                )
                else maybeGeneralize(generalized, level, Type.TArrow(listOf(param), retType))
                exp.withType(ty)
            }
            is Expr.Let -> {
                val name = exp.letDef.binder.name
                val varType = if (exp.letDef.recursive) {
                    inferRecursive(name, exp.letDef.expr, env, level + 1)
                } else infer(env, level + 1, null, Gen.GENERALIZED, exp.letDef.expr)
                checkShadow(env, name, exp.letDef.binder.span)
                env.extend(name, varType)
                val ty = infer(env, level, expectedType, generalized, exp.body)
                exp.withType(ty)
            }
            is Expr.App -> {
                val nextLevel = level + 1
                val fnType = tc.instantiate(nextLevel, infer(env, nextLevel, null, Gen.INSTANTIATED, exp.fn))
                val (paramTypes, retType) = matchFunType(1, fnType, exp.fn.span)
                val instReturn = tc.instantiate(nextLevel, retType)
                if (expectedType != null && !(retType is Type.TVar && retType.tvar is TypeVar.Unbound)) {
                    uni.unify(tc.instantiate(nextLevel, expectedType), instReturn, exp.span)
                }
                inferArgs(env, nextLevel, paramTypes, listOf(exp.arg))
                val ty = generalizeOrInstantiate(generalized, level, instReturn)
                exp.withType(ty)
            }
            is Expr.Ann -> {
                val (_, type) = tc.instantiateTypeAnnotation(level, exp.annType.first, exp.annType.second)
                // this is a bidirectional hack to typecheck (and coerce) primitives
                this.check(env, level, type, shouldGeneralize(type), exp.exp)
                exp.withType(type)
            }
            is Expr.If -> {
                val condType = infer(env, level, null, Gen.INSTANTIATED, exp.cond)
                uni.unify(condType, tBoolean, exp.span)
                val thenType = infer(env, level, expectedType, generalized, exp.thenCase)
                val elseType = infer(env, level, expectedType, generalized, exp.elseCase)
                uni.unify(thenType, elseType, exp.span)
                exp.withType(thenType)
            }
            is Expr.Do -> {
                var ty: Type? = null
                val last = exp.exps.last()
                exp.exps.forEach { e ->
                    ty = if (e == last) infer(env, level, expectedType, generalized, e)
                    else infer(env, level, null, Gen.INSTANTIATED, e)
                }
                exp.withType(ty!!)
            }
            is Expr.Match -> {
                val expTy = infer(env, level, null, generalized, exp.exp)
                val resType = tc.newVar(level)

                exp.cases.forEach { case ->
                    val vars = inferpattern(env, level, case.pattern, expTy)
                    val newEnv = if (vars.isNotEmpty()) {
                        val theEnv = env.makeExtension()
                        vars.forEach { theEnv.extend(it.first, it.second) }
                        theEnv
                    } else env

                    val ty = infer(newEnv, level, expectedType, generalized, case.exp)
                    sub.subsume(level, resType, ty, case.exp.span)
                }
                exp.withType(resType)
            }
            is Expr.RecordEmpty -> exp.withType(Type.TRecord(Type.TRowEmpty()))
            is Expr.RecordSelect -> {
                val restRow = tc.newVar(level + 1)
                val fieldTy = tc.newVar(level + 1)
                val paramType =
                    Type.TRecord(Type.TRowExtend(singletonPMap(exp.label, fieldTy), restRow))
                val infered = infer(env, level + 1, null, Gen.INSTANTIATED, exp.exp)
                uni.unify(paramType, infered, exp.span)
                val ty = generalizeOrInstantiate(generalized, level, fieldTy)
                exp.withType(ty)
            }
            is Expr.RecordRestrict -> {
                val restRow = tc.newVar(level + 1)
                val fieldTy = tc.newVar(level + 1)
                val paramType =
                    Type.TRecord(Type.TRowExtend(singletonPMap(exp.label, fieldTy), restRow))
                val retType = Type.TRecord(restRow)
                uni.unify(paramType, infer(env, level + 1, null, Gen.INSTANTIATED, exp.exp), exp.span)
                val ty = generalizeOrInstantiate(generalized, level, retType)
                exp.withType(ty)
            }
            is Expr.RecordExtend -> {
                val nextLevel = level + 1
                val labelTys = exp.labels.mapList { infer(env, nextLevel, null, Gen.GENERALIZED, it) }
                val restRow = tc.newVar(nextLevel)
                uni.unify(
                    Type.TRecord(restRow),
                    infer(env, nextLevel, null, Gen.GENERALIZED, exp.exp),
                    exp.span
                )
                val ty = Type.TRecord(Type.TRowExtend(labelTys, restRow))
                if (expectedType != null) {
                    uni.unify(tc.instantiate(nextLevel, expectedType), ty, exp.span)
                }
                exp.withType(ty)
            }
            is Expr.VectorLiteral -> {
                val ty = tc.newVar(level + 1)
                if (exp.exps.isEmpty()) {
                    val res =
                        generalizeOrInstantiate(generalized, level, Type.TApp(Type.TConst(primVector), listOf(ty)))
                    exp.withType(res)
                } else {
                    exp.exps.forEach { e ->
                        val newTy = infer(env, level + 1, null, generalized, e)
                        uni.unify(newTy, ty, e.span)
                    }
                    var res: Type = Type.TApp(Type.TConst(primVector), listOf(ty))
                    res = generalizeOrInstantiate(generalized, level, res)
                    exp.withType(res)
                }
            }
            is Expr.SetLiteral -> {
                val ty = tc.newVar(level + 1)
                if (exp.exps.isEmpty()) {
                    val res =
                        generalizeOrInstantiate(generalized, level, Type.TApp(Type.TConst(primSet), listOf(ty)))
                    exp.withType(res)
                } else {
                    exp.exps.forEach { e ->
                        val newTy = infer(env, level + 1, null, generalized, e)
                        uni.unify(newTy, ty, e.span)
                    }
                    var res: Type = Type.TApp(Type.TConst(primSet), listOf(ty))
                    res = generalizeOrInstantiate(generalized, level, res)
                    exp.withType(res)
                }
            }
        }
    }

    private fun check(env: Env, level: Level, type: Type, generalized: Gen, exp: Expr) {
        when {
            exp is Expr.IntE && type == tByte && exp.v >= Byte.MIN_VALUE && exp.v <= Byte.MAX_VALUE ->
                exp.withType(tByte)
            exp is Expr.IntE && type == tShort && exp.v >= Short.MIN_VALUE && exp.v <= Short.MAX_VALUE ->
                exp.withType(tShort)
            exp is Expr.IntE && type == tInt -> exp.withType(tInt)
            exp is Expr.LongE && type == tLong -> exp.withType(tLong)
            exp is Expr.FloatE && type == tFloat -> exp.withType(tFloat)
            exp is Expr.DoubleE && type == tDouble -> exp.withType(tDouble)
            exp is Expr.StringE && type == tString -> exp.withType(tString)
            exp is Expr.CharE && type == tChar -> exp.withType(tChar)
            exp is Expr.Bool && type == tBoolean -> exp.withType(tBoolean)
            exp is Expr.Unit -> exp.withType(tUnit)
            else -> {
                validateType(type, env, exp.span)
                val inferedType = infer(env, level, type, generalized, exp)
                sub.subsume(level, type, inferedType, exp.span)
                exp.withType(type)
            }
        }
    }

    private fun inferArgs(env: Env, level: Level, paramTypes: List<Type>, argList: List<Expr>) {
        val pars = paramTypes.zip(argList)
        fun ordering(type: Type, arg: Expr): Int {
            return if (isAnnotated(arg)) 0
            else {
                val un = type.unlink()
                if (un is Type.TVar && un.tvar is TypeVar.Unbound) 2 else 1
            }
        }
        pars.sortedBy { ordering(it.first, it.second) }.forEach { (paramType, arg) ->
            val argType = infer(env, level, paramType, shouldGeneralize(paramType), arg)
            if (isAnnotated(arg)) uni.unify(paramType, argType, arg.span)
            else sub.subsume(level, paramType, argType, arg.span)
        }
    }

    private fun inferpattern(env: Env, level: Level, pat: Pattern, ty: Type): List<Pair<String, Type>> {
        tailrec fun peelArgs(args: List<Type>, t: Type): Pair<List<Type>, Type> = when (t) {
            is Type.TArrow -> {
                if (t.ret is Type.TArrow) peelArgs(args + t.args, t.ret)
                else args + t.args to t.ret
            }
            else -> args to t
        }
        return when (pat) {
            is Pattern.LiteralP -> {
                val type = infer(env, level, null, Gen.INSTANTIATED, pat.lit.e)
                uni.unify(ty, type, pat.lit.e.span)
                emptyList()
            }
            is Pattern.Wildcard -> emptyList()
            is Pattern.Var -> {
                checkShadow(env, pat.name, pat.span)
                listOf(pat.name to ty)
            }
            is Pattern.Ctor -> {
                val cty = infer(env, level, null, Gen.INSTANTIATED, pat.ctor)

                val (ctorTypes, ret) = peelArgs(listOf(), tc.instantiate(level, cty))
                uni.unify(ret, ty, pat.ctor.span)

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
        t is Type.TArrow -> {
            if (numParams != t.args.size) internalError("unexpected number of arguments to function: $numParams")
            t.args to t.ret
        }
        t is Type.TVar && t.tvar is TypeVar.Link -> matchFunType(numParams, (t.tvar as TypeVar.Link).type, span)
        t is Type.TVar && t.tvar is TypeVar.Unbound -> {
            val unb = t.tvar as TypeVar.Unbound
            val params = (1..numParams).map { tc.newVar(unb.level) }.reversed()
            val retur = tc.newVar(unb.level)
            t.tvar = TypeVar.Link(Type.TArrow(params, retur).span(t.span))
            params to retur
        }
        else -> inferError(Errors.NOT_A_FUNCTION, span)
    }

    /**
     * Use a fixpoint operator to infer a recursive function
     */
    private fun inferRecursive(name: String, exp: Expr, env: Env, level: Level): Type {
        val (newName, newExp) = funToFixpoint(name, exp)
        val recTy = infer(env, level, null, Gen.GENERALIZED, newExp)
        env.extend(newName, recTy)
        val fix = Expr.App(Expr.Var("\$fix", exp.span), Expr.Var(newName, exp.span), exp.span)
        return infer(env, level, null, Gen.GENERALIZED, fix)
    }

    private fun generalize(level: Level, type: Type): Type {
        val ids = mutableListOf<Id>()
        fun go(t: Type) {
            when {
                t is Type.TVar && t.tvar is TypeVar.Link -> go((t.tvar as TypeVar.Link).type)
                t is Type.TVar && t.tvar is TypeVar.Generic -> internalError("unexpected generic var in generalize")
                t is Type.TVar && t.tvar is TypeVar.Unbound -> {
                    val unb = t.tvar as TypeVar.Unbound
                    if (unb.level > level) {
                        t.tvar = TypeVar.Bound(unb.id)
                        if (unb.id !in ids) {
                            ids += unb.id
                        }
                    }
                }
                t is Type.TApp -> {
                    go(t.type)
                    t.types.forEach(::go)
                }
                t is Type.TArrow -> {
                    t.args.forEach(::go)
                    go(t.ret)
                }
                t is Type.TForall -> go(t.type)
                t is Type.TRecord -> go(t.row)
                t is Type.TRowExtend -> {
                    t.labels.forEachList { go(it) }
                    go(t.row)
                }
                t is Type.TConst || t is Type.TRowEmpty -> {
                }
            }
        }

        go(type)
        return if (ids.isEmpty()) type
        else Type.TForall(ids, type).span(type.span)
    }

    private tailrec fun isAnnotated(exp: Expr): Boolean = when (exp) {
        is Expr.Ann -> true
        is Expr.Let -> isAnnotated(exp.body)
        else -> false
    }

    private tailrec fun shouldGeneralize(type: Type): Gen = when (type) {
        is Type.TForall -> Gen.GENERALIZED
        is Type.TVar -> if (type.tvar is TypeVar.Link) shouldGeneralize((type.tvar as TypeVar.Link).type) else Gen.INSTANTIATED
        else -> Gen.INSTANTIATED
    }

    private fun maybeGeneralize(gene: Gen, level: Level, ty: Type): Type = when (gene) {
        Gen.INSTANTIATED -> ty
        Gen.GENERALIZED -> generalize(level, ty)
    }

    private fun maybeInstantiate(gene: Gen, level: Level, ty: Type): Type = when (gene) {
        Gen.INSTANTIATED -> tc.instantiate(level, ty)
        Gen.GENERALIZED -> ty
    }

    private fun generalizeOrInstantiate(gene: Gen, level: Level, ty: Type): Type = when (gene) {
        Gen.INSTANTIATED -> tc.instantiate(level, ty)
        Gen.GENERALIZED -> generalize(level, ty)
    }

    private fun validateType(type: Type, env: Env, span: Span) {
        type.everywhere { ty ->
            if (ty is Type.TConst && env.lookupType(ty.name) == null) {
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

    private fun getDataType(d: Decl.DataDecl, moduleName: String): Pair<Type, Map<String, Type.TVar>> {
        val kind = if (d.tyVars.isEmpty()) Kind.Star else Kind.Constructor(d.tyVars.size)
        val raw = Type.TConst("$moduleName.${d.name}", kind).span(d.span)

        return if (d.tyVars.isEmpty()) raw to emptyMap()
        else {
            val varsMap = d.tyVars.map { it to tc.newBoundVar() }
            val map = mutableMapOf<String, Type.TVar>()
            varsMap.forEach { (name, idVar) -> map[name] = idVar.second }
            val vars = varsMap.map { it.second }

            Type.TForall(vars.map { it.first }, Type.TApp(raw, vars.map { it.second })).span(d.span) to map
        }
    }

    private fun getCtorType(dc: DataConstructor, dataType: Type, map: Map<String, Type.TVar>): Type {
        return when (dataType) {
            is Type.TConst -> if (dc.args.isEmpty()) dataType else Type.nestArrows(dc.args, dataType).span(dc.span)
            is Type.TForall -> {
                val args = dc.args.map { it.substConst(map) }
                Type.TForall(dataType.ids, Type.nestArrows(args, dataType.type)).span(dc.span)
            }
            else -> internalError("Got absurd type for data constructor: $dataType")
        }
    }
}