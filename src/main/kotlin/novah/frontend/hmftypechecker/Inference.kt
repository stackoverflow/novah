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
import novah.Util.validByte
import novah.Util.validShort
import novah.ast.canonical.*
import novah.data.*
import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.hmftypechecker.InstanceSearch.instanceSearch
import novah.frontend.hmftypechecker.Subsumption.subsume
import novah.frontend.hmftypechecker.Type.Companion.nestArrows
import novah.frontend.hmftypechecker.Typechecker.checkWellFormed
import novah.frontend.hmftypechecker.Typechecker.context
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

    private val implicitsToCheck = mutableListOf<Expr.App>()

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

        val datas = ast.decls.filterIsInstance<Decl.TypeDecl>()
        datas.forEach { d ->
            val (ty, map) = getDataType(d, ast.name)
            checkShadowType(env, d.name, d.span)
            env.extendType("${ast.name}.${d.name}", ty)

            d.dataCtors.forEach { dc ->
                val dcty = getCtorType(dc, ty, map)
                checkShadow(env, dc.name, dc.span)
                env.extend(dc.name, dcty)
                decls[dc.name] = DeclRef(dcty, dc.visibility, false)
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
                if (decl.isInstance) env.extendInstance(name, expr.annType.second)
            } else {
                val t = newVar(0)
                env.extend(name, t)
                if (decl.isInstance) env.extendInstance(name, t)
            }
        }

        vals.forEach { decl ->
            implicitsToCheck.clear()
            context?.apply { this.decl = decl }
            val name = decl.name
            val newEnv = env.fork()
            val ty = if (decl.recursive) {
                newEnv.remove(name)
                inferRecursive(name, decl.exp, newEnv, 0)
            } else infer(newEnv, 0, decl.exp)

            if (implicitsToCheck.isNotEmpty()) instanceSearch(implicitsToCheck)

            val genTy = generalize(-1, ty)
            env.extend(name, genTy)
            if (decl.isInstance) env.extendInstance(name, genTy)
            decls[name] = DeclRef(genTy, decl.visibility, decl.isInstance)
        }

        return ModuleEnv(decls, types)
    }

    private fun infer(env: Env, level: Level, exp: Expr): Type {
        context?.apply { exps.push(exp) }
        val ty = when (exp) {
            is Expr.Int32 -> exp.withType(tInt32)
            is Expr.Int64 -> exp.withType(tInt64)
            is Expr.Float32 -> exp.withType(tFloat32)
            is Expr.Float64 -> exp.withType(tFloat64)
            is Expr.CharE -> exp.withType(tChar)
            is Expr.Bool -> exp.withType(tBoolean)
            is Expr.StringE -> exp.withType(tString)
            is Expr.Unit -> exp.withType(tUnit)
            is Expr.Var -> {
                val ty = env.lookup(exp.fullname()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(ty)
            }
            is Expr.Constructor -> {
                val ty = env.lookup(exp.fullname()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(ty)
            }
            is Expr.ImplicitVar -> {
                val ty = env.lookup(exp.fullname()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
                exp.withType(TImplicit(ty))
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
                val binder = exp.binder
                checkShadow(newEnv, binder.name, binder.span)
                val param = if (binder.isImplicit) TImplicit(newVar(level + 1)) else newVar(level + 1)
                newEnv.extend(binder.name, param)

                val infRetType = infer(newEnv, level + 1, exp.body)
                val retType = if (isAnnotated(exp.body)) infRetType else instantiate(level + 1, infRetType)

                val ty = if (!param.isMono()) inferError(Errors.polyParameterToLambda(param.show()), exp.body.span)
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
                val newEnv = env.fork()
                newEnv.extend(name, varType)
                if (exp.letDef.isInstance) newEnv.extendInstance(name, varType)

                val ty = infer(newEnv, level, exp.body)
                exp.withType(ty)
            }
            is Expr.App -> inferApp(env, level, exp)
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
            is Expr.Match -> inferMatch(env, level, exp)
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
                    val (id, type) = newBoundVar()
                    exp.withType(TForall(listOf(id), TApp(TConst(primVector), listOf(type))))
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
                    val (id, type) = newBoundVar()
                    exp.withType(TForall(listOf(id), TApp(TConst(primSet), listOf(type))))
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
        context?.apply { exps.pop() }
        return ty
    }

    private fun check(env: Env, level: Level, type: Type, exp: Expr) {
        context?.apply {
            exps.push(exp)
            types.push(type)
        }
        when {
            exp is Expr.Int32 && type == tByte && validByte(exp.v) -> exp.withType(tByte)
            exp is Expr.Int32 && type == tInt16 && validShort(exp.v) -> exp.withType(tInt16)
            exp is Expr.Int32 && type == tInt32 -> exp.withType(tInt32)
            exp is Expr.Int64 && type == tInt64 -> exp.withType(tInt64)
            exp is Expr.Float32 && type == tFloat32 -> exp.withType(tFloat32)
            exp is Expr.Float64 && type == tFloat64 -> exp.withType(tFloat64)
            exp is Expr.StringE && type == tString -> exp.withType(tString)
            exp is Expr.CharE && type == tChar -> exp.withType(tChar)
            exp is Expr.Bool && type == tBoolean -> exp.withType(tBoolean)
            exp is Expr.Unit && type == tUnit -> exp.withType(tUnit)
            exp is Expr.Lambda && type is TForall -> {
                val ty = instantiate(level + 1, type)
                check(env, level, ty, exp)
            }
            exp is Expr.Lambda && type is TArrow -> {
                val bind = exp.binder
                val newEnv = env.fork()
                val ty = type.args[0]
                if (bind.isImplicit) {
                    if (ty !is TImplicit)
                        inferError(Errors.implicitTypesDontMatch(bind.toString(), ty.show()), bind.span)
                    newEnv.extendInstance(bind.name, ty)
                }
                newEnv.extend(bind.name, ty)
                check(newEnv, level + 1, type.ret, exp.body)
                exp.withType(generalize(level, instantiate(level + 1, type)))
            }
            exp is Expr.App -> inferApp(env, level, exp, type)
            exp is Expr.RecordExtend && type is TForall -> {
                val ty = instantiate(level, type)
                check(env, level, ty, exp)
            }
            exp is Expr.RecordExtend && type is TRecord -> {
                val (rows, restRow) = type.row.collectRows()
                val allRows = rows.forked().linear()
                exp.labels.forEach { kv ->
                    val (label, exps) = kv.key() to kv.value()
                    allRows.remove(label)
                    // if the annotation has missing rows: throw error
                    val given = rows.get(label, null)
                        ?: inferError(Errors.recordMissingLabels(listOf(label)), exp.span)
                    // if the annotation has less duplicates then the actual type: throw error
                    if (given.size() != exps.size()) inferError(Errors.recordMissingLabels(listOf(label)), exp.span)
                    exps.zip(given).forEach { (expr, ty) -> check(env, level, ty, expr) }
                }
                // if there are rows left in the annotated type: throw error
                if (!allRows.isEmpty()) inferError(Errors.recordMissingLabels(allRows.keys().toList()), exp.span)
                check(env, level, TRecord(restRow), exp.exp)
                val ty = TRecord(TRowExtend(rows, restRow))
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
            exp is Expr.Match -> inferMatch(env, level, exp, type)
            else -> {
                validateType(type, env, exp.span)
                subsume(level, type, infer(env, level, exp), exp.span)
                exp.withType(type)
            }
        }
        context?.apply {
            exps.pop()
            types.pop()
        }
    }

    private fun inferApp(env: Env, level: Level, exp: Expr.App, type: Type? = null): Type {
        val nextLevel = level + 1
        val params = mutableListOf<Type>()
        var retTy = instantiate(nextLevel, infer(env, nextLevel, exp.fn))
        do {
            val (paramTypes, retType) = matchFunType(1, retTy, exp.fn.span)
            params += paramTypes[0]
            retTy = retType
        } while (paramTypes[0] is TImplicit && exp.arg !is Expr.ImplicitVar)

        val instReturnTy = instantiate(nextLevel, retTy)
        if (type != null && !type.isUnbound()) {
            unify(instantiate(nextLevel, type), instReturnTy, exp.span)
        }
        inferArg(env, nextLevel, params.last(), exp.arg, type != null)
        val ty = generalize(level, instReturnTy)

        if (params.size > 1) {
            val impTy = nestArrows(params, instReturnTy)
            exp.implicitContext = ImplicitContext(impTy, env.fork())
            implicitsToCheck += exp
        }
        return exp.withType(ty)
    }

    private fun inferArg(env: Env, level: Level, paramType: Type, arg: Expr, checkMode: Boolean) {
        if (checkMode) check(env, level, paramType, arg)
        else {
            val argType = infer(env, level, arg)
            if (isAnnotated(arg)) unify(paramType, argType, arg.span)
            else subsume(level, paramType, argType, arg.span)
        }
    }
    
    private fun inferMatch(env: Env, level: Level, exp: Expr.Match, type: Type? = null): Type {
        val expTys = exp.exps.map {
            val ty = instantiate(level, infer(env, level, it))
            ty to !ty.isUnbound()
        }
        val resType = type ?: newVar(level)

        exp.cases.forEach { case ->
            val vars = case.patterns.flatMapIndexed { i, pat ->
                val (expTy, isCheck) = expTys[i]
                inferpattern(env, level, pat, expTy, isCheck)
            }
            val newEnv = if (vars.isNotEmpty()) {
                val theEnv = env.fork()
                vars.forEach {
                    checkShadow(theEnv, it.name, it.span)
                    theEnv.extend(it.name, it.type)
                }
                theEnv
            } else env

            if (case.guard != null) check(newEnv, level, tBoolean, case.guard)

            if (type != null) {
                check(newEnv, level, type, case.exp)
            } else {
                val ty = infer(newEnv, level, case.exp)
                unify(resType, ty, case.exp.span)
            }
        }
        return exp.withType(resType)
    }

    data class PatternVar(val name: String, val type: Type, val span: Span)

    private fun inferpattern(env: Env, level: Level, pat: Pattern, ty: Type, isCheck: Boolean): List<PatternVar> {
        return when (pat) {
            is Pattern.LiteralP -> {
                check(env, level, ty, pat.lit.e)
                emptyList()
            }
            is Pattern.Wildcard -> emptyList()
            is Pattern.Unit -> {
                unify(ty, tUnit, pat.span)
                emptyList()
            }
            is Pattern.Var -> listOf(PatternVar(pat.name, ty, pat.span))
            is Pattern.Ctor -> {
                val cty = infer(env, level, pat.ctor)

                val (ctorTypes, ret) = peelArgs(listOf(), instantiate(level, cty))
                unify(ret, ty, pat.ctor.span)

                if (ctorTypes.size - pat.fields.size != 0)
                    internalError("unified two constructors with wrong kinds: $pat")

                if (ctorTypes.isEmpty()) emptyList()
                else {
                    val vars = mutableListOf<PatternVar>()
                    ctorTypes.zip(pat.fields).forEach { (type, pattern) ->
                        vars += inferpattern(env, level, pattern, type, isCheck)
                    }
                    vars
                }
            }
            is Pattern.Record -> {
                if (pat.labels.isEmpty()) {
                    unify(TRecord(newVar(level)), ty, pat.span)
                    return emptyList()
                }

                val vars = mutableListOf<PatternVar>()
                if (isCheck) {
                    val (rows, _) = if (ty is TRecord) ty.row.collectRows() else ty.collectRows()
                    pat.labels.forEachKeyList { label, p ->
                        val rowTy = rows.find { it.key() == label }?.value()?.first()
                            ?: inferError(Errors.recordMissingLabels(listOf(label)), pat.span)
                        vars += inferpattern(env, level, p, rowTy, isCheck)
                    }
                } else {
                    // we are in infer mode so we can't make any assumptions about rows
                    val tys: LabelMap<Type> = pat.labels.mapList { p ->
                        val rowTy = newVar(level)
                        vars += inferpattern(env, level, p, rowTy, isCheck)
                        rowTy
                    }
                    unify(TRecord(TRowExtend(tys, newVar(level))), ty, pat.span)
                }
                vars
            }
            is Pattern.Vector -> {
                if (pat.elems.isEmpty()) {
                    unify(TApp(TConst(primVector), listOf(newVar(level))), ty, pat.span)
                    return emptyList()
                }

                val vars = mutableListOf<PatternVar>()
                val elemTy = newVar(level)
                unify(TApp(TConst(primVector), listOf(elemTy)), ty, pat.span)

                pat.elems.forEach { p ->
                    vars += inferpattern(env, level, p, elemTy, isCheck)
                }
                vars
            }
            is Pattern.VectorHT -> {
                val vars = mutableListOf<PatternVar>()
                val elemTy = newVar(level)
                val vecTy = TApp(TConst(primVector), listOf(elemTy))
                unify(vecTy, ty, pat.span)

                vars += inferpattern(env, level, pat.head, elemTy, isCheck)
                vars += inferpattern(env, level, pat.tail, vecTy, isCheck)
                vars
            }
            is Pattern.Named -> {
                val vars = mutableListOf<PatternVar>()
                vars += inferpattern(env, level, pat.pat, ty, isCheck)
                vars += PatternVar(pat.name, ty, pat.span)
                vars
            }
            is Pattern.TypeTest -> {
                if (pat.alias != null) {
                    // we need special handling for Vectors, Sets and Arrays
                    val type = when {
                        pat.type is TConst && pat.type.name == primVector -> TApp(TConst(primVector), listOf(tObject))
                        pat.type is TConst && pat.type.name == primSet -> TApp(TConst(primSet), listOf(tObject))
                        pat.type is TConst && pat.type.name == primArray -> TApp(TConst(primArray), listOf(tObject))
                        else -> pat.type
                    }
                    listOf(PatternVar(pat.alias, type, pat.span))
                } else emptyList()
            }
        }
    }

    private tailrec fun peelArgs(args: List<Type>, t: Type): Pair<List<Type>, Type> = when (t) {
        is TArrow -> {
            if (t.ret is TArrow) peelArgs(args + t.args, t.ret)
            else args + t.args to t.ret
        }
        else -> args to t
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
        val ty = infer(env, level, fix)
        return exp.withType(ty)
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
        type.everywhereUnit { ty ->
            if (ty is TConst && env.lookupType(ty.name) == null) {
                inferError(Errors.undefinedType(ty.show()), ty.span ?: span)
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
        return binder to Expr.Lambda(Binder(name, expr.span), null, expr, expr.span)
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

    private fun getDataType(d: Decl.TypeDecl, moduleName: String): Pair<Type, Map<String, TVar>> {
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
            is TConst -> if (dc.args.isEmpty()) dataType else nestArrows(dc.args, dataType).span(dc.span)
            is TForall -> {
                val args = dc.args.map { it.substConst(map) }
                TForall(dataType.ids, nestArrows(args, dataType.type)).span(dc.span)
            }
            else -> internalError("Got absurd type for data constructor: $dataType")
        }
    }
}