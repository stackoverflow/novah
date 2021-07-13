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

import novah.Util.internalError
import novah.Util.validByte
import novah.Util.validShort
import novah.ast.canonical.*
import novah.data.LabelMap
import novah.data.isEmpty
import novah.data.mapList
import novah.data.singletonPMap
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.frontend.error.Severity
import novah.frontend.typechecker.Type.Companion.nestArrows
import novah.frontend.typechecker.Typechecker.context
import novah.frontend.typechecker.Typechecker.env
import novah.frontend.typechecker.Typechecker.instantiate
import novah.frontend.typechecker.Typechecker.newGenVar
import novah.frontend.typechecker.Typechecker.newVar
import novah.frontend.typechecker.Unification.unify
import novah.main.DeclRef
import novah.main.Environment
import novah.main.ModuleEnv
import novah.main.TypeDeclRef
import novah.frontend.error.Errors as E

object Inference {

    private val implicitsToCheck = mutableListOf<Expr>()
    private val warnings = mutableListOf<CompilerProblem>()

    fun getWarnings(): List<CompilerProblem> = warnings

    /**
     * Infer the whole module
     */
    fun infer(ast: Module): ModuleEnv {
        warnings.clear()
        val decls = mutableMapOf<String, DeclRef>()
        val types = mutableMapOf<String, TypeDeclRef>()
        val warner = makeWarner(ast)

        // add the fixpoint operator to the context for recursive functions
        // TODO: add this to the env as primitive
        val vvar = newGenVar()
        env.extend("\$fix", TArrow(listOf(TArrow(listOf(vvar), vvar)), vvar))

        val datas = ast.decls.filterIsInstance<Decl.TypeDecl>()
        datas.forEach { d ->
            val (ty, map) = getDataType(d, ast.name.value)
            checkShadowType(env, d.name, d.span)
            env.extendType("${ast.name.value}.${d.name}", ty)

            d.dataCtors.forEach { dc ->
                val dcty = getCtorType(dc, ty, map)
                checkShadow(env, dc.name, dc.span)
                env.extend(dc.name, dcty)
                Environment.cacheConstructorType("${ast.name.value}.${dc.name}", dcty)
                decls[dc.name] = DeclRef(dcty, dc.visibility, false)
            }
            types[d.name] = TypeDeclRef(ty, d.visibility, d.dataCtors.map { it.name })
        }
        datas.forEach { d ->
            d.dataCtors.forEach { dc ->
                Typechecker.checkWellFormed(env.lookup(dc.name)!!, dc.span)
            }
        }

        val vals = ast.decls.filterIsInstance<Decl.ValDecl>()
        vals.filter { it.exp is Expr.Ann }.forEach { decl ->
            val expr = decl.exp as Expr.Ann
            val name = decl.name.name
            checkShadow(env, name, decl.span)
            env.extend(name, expr.annType)
            if (decl.isInstance) env.extendInstance(name, expr.annType)
        }

        vals.forEach { decl ->
            implicitsToCheck.clear()
            context?.apply { this.decl = decl }
            val name = decl.name.name
            val isAnnotated = decl.exp is Expr.Ann
            if (!isAnnotated) checkShadow(env, name, decl.span)

            val newEnv = env.fork()
            val ty = if (decl.recursive) {
                newEnv.remove(name)
                inferRecursive(name, decl.exp, newEnv, 0)
            } else infer(newEnv, 0, decl.exp)

            if (implicitsToCheck.isNotEmpty()) InstanceSearch.instanceSearch(implicitsToCheck)

            val genTy = generalize(-1, ty)
            env.extend(name, genTy)
            if (decl.isInstance) env.extendInstance(name, genTy)
            decls[name] = DeclRef(genTy, decl.visibility, decl.isInstance)
            if (!isAnnotated) warner(E.noTypeAnnDecl(name, genTy.show()), decl.span)
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
            is Expr.Null -> exp.withType(instantiate(level, tNullable))
            is Expr.Var -> {
                val ty = env.lookup(exp.fullname()) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(instantiate(level, ty))
            }
            is Expr.Constructor -> {
                val ty = env.lookup(exp.fullname()) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(instantiate(level, ty))
            }
            is Expr.ImplicitVar -> {
                val ty = env.lookup(exp.fullname()) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(TImplicit(instantiate(level, ty)))
            }
            is Expr.NativeFieldGet -> {
                val ty = env.lookup(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(instantiate(level, ty))
            }
            is Expr.NativeFieldSet -> {
                val ty = env.lookup(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(instantiate(level, ty))
            }
            is Expr.NativeMethod -> {
                val ty = env.lookup(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(instantiate(level, ty))
            }
            is Expr.NativeConstructor -> {
                val ty = env.lookup(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(instantiate(level, ty))
            }
            is Expr.Lambda -> {
                val binder = exp.binder
                checkShadow(env, binder.name, binder.span)
                val param = if (binder.isImplicit) TImplicit(newVar(level)) else newVar(level)
                val newEnv = env.fork().extend(binder.name, param)
                if (binder.isImplicit) newEnv.extendInstance(binder.name, param, true)
                val returnTy = infer(newEnv, level, exp.body)
                val ty = TArrow(listOf(param), returnTy)
                binder.type = param
                exp.withType(ty)
            }
            is Expr.Let -> {
                val name = exp.letDef.binder.name
                checkShadow(env, name, exp.letDef.binder.span)
                val varTy = if (exp.letDef.recursive) {
                    inferRecursive(name, exp.letDef.expr, env, level + 1)
                } else infer(env, level + 1, exp.letDef.expr)
                val genTy = generalize(level, varTy)
                val newEnv = env.fork().extend(name, genTy)
                if (exp.letDef.isInstance) newEnv.extendInstance(name, genTy)
                val ty = infer(newEnv, level, exp.body)
                exp.withType(ty)
            }
            is Expr.App -> {
                val params = mutableListOf<Type>()
                var retTy = infer(env, level, exp.fn)
                do {
                    val (paramTypes, ret) = matchFunType(1, retTy, exp.fn.span)
                    params += paramTypes[0]
                    retTy = ret
                } while (paramTypes[0] is TImplicit && exp.arg !is Expr.ImplicitVar)
                val fullArgTy = infer(env, level, exp.arg)
                val (argTy, implicits) = peelImplicits(fullArgTy)
                unify(params.last(), argTy, exp.arg.span)

                if (params.size > 1) {
                    exp.implicitContext = ImplicitContext(params.dropLast(1), env.fork())
                    implicitsToCheck += exp
                }
                if (implicits.isNotEmpty()) {
                    exp.arg.implicitContext = ImplicitContext(implicits, env.fork())
                    implicitsToCheck += exp.arg
                }
                exp.withType(retTy)
            }
            is Expr.Ann -> {
                context?.apply { exps.push(exp.exp) }
                context?.apply { types.push(exp.annType) }
                val type = exp.annType
                val expr = exp.exp
                // this is a little `checking mode` for some base conversions
                val resTy = when {
                    expr is Expr.Int32 && type == tByte && validByte(expr.v) -> tByte
                    expr is Expr.Int32 && type == tInt16 && validShort(expr.v) -> tInt16
                    expr is Expr.ListLiteral && isListOf(type, tByte)
                            && expr.exps.all { it is Expr.Int32 && validByte(it.v) } -> {
                        expr.exps.forEach { it.withType(tByte) }
                        type
                    }
                    expr is Expr.ListLiteral && isListOf(type, tInt16)
                            && expr.exps.all { it is Expr.Int32 && validShort(it.v) } -> {
                        expr.exps.forEach { it.withType(tInt16) }
                        type
                    }
                    expr is Expr.SetLiteral && isSetOf(type, tByte)
                            && expr.exps.all { it is Expr.Int32 && validByte(it.v) } -> {
                        expr.exps.forEach { it.withType(tByte) }
                        type
                    }
                    expr is Expr.SetLiteral && isSetOf(type, tInt16)
                            && expr.exps.all { it is Expr.Int32 && validShort(it.v) } -> {
                        expr.exps.forEach { it.withType(tInt16) }
                        type
                    }
                    else -> {
                        validateType(type, env, expr.span)
                        unify(type, infer(env, level, expr), expr.span)
                        if (expr is Expr.Lambda && type is TArrow)
                            validateImplicitArgs(expr, type)
                        type
                    }
                }
                context?.apply { exps.pop() }
                context?.apply { types.pop() }
                expr.withType(resTy)
                exp.withType(resTy)
            }
            is Expr.If -> {
                unify(tBoolean, infer(env, level, exp.cond), exp.cond.span)
                val thenTy = infer(env, level, exp.thenCase)
                val elseTy = infer(env, level, exp.elseCase)
                unify(thenTy, elseTy, exp.span)
                exp.withType(thenTy)
            }
            is Expr.Do -> {
                var ty: Type? = null
                exp.exps.forEach { e ->
                    ty = infer(env, level, e)
                }
                exp.withType(ty!!)
            }
            is Expr.Match -> {
                val expTys = exp.exps.map { infer(env, level, it) }
                val resType = newVar(level)

                exp.cases.forEach { case ->
                    val vars = case.patterns.flatMapIndexed { i, pat ->
                        inferpattern(env, level, pat, expTys[i])
                    }
                    val newEnv = if (vars.isNotEmpty()) {
                        val theEnv = env.fork()
                        vars.forEach {
                            checkShadow(theEnv, it.name, it.span)
                            theEnv.extend(it.name, it.type)
                        }
                        theEnv
                    } else env

                    if (case.guard != null) {
                        unify(tBoolean, infer(newEnv, level, case.guard), case.patternSpan())
                    }

                    val ty = infer(newEnv, level, case.exp)
                    unify(resType, ty, case.exp.span)
                }
                exp.withType(resType)
            }
            is Expr.RecordEmpty -> exp.withType(TRecord(TRowEmpty()))
            is Expr.RecordSelect -> {
                val rest = newVar(level)
                val field = newVar(level)
                val param = TRecord(TRowExtend(singletonPMap(exp.label, field), rest))
                unify(param, infer(env, level, exp.exp), exp.span)
                exp.withType(field)
            }
            is Expr.RecordRestrict -> {
                val rest = newVar(level)
                val field = newVar(level)
                val param = TRecord(TRowExtend(singletonPMap(exp.label, field), rest))
                val returnTy = TRecord(rest)
                unify(param, infer(env, level, exp.exp), exp.span)
                exp.withType(returnTy)
            }
            is Expr.RecordUpdate -> {
                val field = infer(env, level, exp.value)
                val rest = newVar(level)
                val recTy = TRecord(TRowExtend(singletonPMap(exp.label, field), rest))
                unify(recTy, infer(env, level, exp.exp), exp.span)
                exp.withType(recTy)
            }
            is Expr.RecordExtend -> {
                val labelTys = exp.labels.mapList { infer(env, level, it) }
                val rest = newVar(level)
                unify(TRecord(rest), infer(env, level, exp.exp), exp.span)
                val ty = TRecord(TRowExtend(labelTys, rest))
                exp.withType(ty)
            }
            is Expr.ListLiteral -> {
                val ty = newVar(level)
                exp.exps.forEach { e ->
                    unify(ty, infer(env, level, e), e.span)
                }
                val res = TApp(TConst(primList), listOf(ty))
                exp.withType(res)
            }
            is Expr.SetLiteral -> {
                val ty = newVar(level)
                exp.exps.forEach { e ->
                    unify(ty, infer(env, level, e), e.span)
                }
                val res = TApp(TConst(primSet), listOf(ty))
                exp.withType(res)
            }
            is Expr.Throw -> {
                val ty = infer(env, level, exp.exp)
                val res = Environment.classLoader().isException(ty.typeNameOrEmpty())
                if (res.isEmpty || !res.get()) {
                    inferError(E.notException(ty.show()), exp.span)
                }
                // throw returns anything
                val ret = newVar(level)
                exp.withType(ret)
            }
            is Expr.TryCatch -> {
                val resTy = infer(env, level, exp.tryExp)

                exp.cases.forEach { case ->
                    val vars = case.patterns.flatMap { pat ->
                        // the type parameter is not actually used here
                        // we just passed resTy as an optimization instead
                        // of creating a new type var
                        inferpattern(env, level, pat, resTy)
                    }
                    val newEnv = if (vars.isNotEmpty()) {
                        val theEnv = env.fork()
                        vars.forEach {
                            checkShadow(theEnv, it.name, it.span)
                            theEnv.extend(it.name, it.type)
                        }
                        theEnv
                    } else env

                    val ty = infer(newEnv, level, case.exp)
                    unify(resTy, ty, case.exp.span)
                }
                // the result type of the finally expression is discarded
                if (exp.finallyExp != null) infer(env, level, exp.finallyExp)
                exp.withType(resTy)
            }
            is Expr.While -> {
                unify(tBoolean, infer(env, level, exp.cond), exp.cond.span)
                exp.exps.forEach { e -> infer(env, level, e) }
                // while always returns unit
                exp.withType(tUnit)
            }
        }
        context?.apply { exps.pop() }
        return ty
    }

    private data class PatternVar(val name: String, val type: Type, val span: Span)

    private fun inferpattern(env: Env, level: Level, pat: Pattern, ty: Type): List<PatternVar> {
        return when (pat) {
            is Pattern.LiteralP -> {
                unify(ty, infer(env, level, pat.lit.e), pat.span)
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

                val (ctorTypes, ret) = peelArgs(listOf(), cty)
                unify(ret, ty, pat.ctor.span)

                if (ctorTypes.size - pat.fields.size != 0)
                    inferError(E.wrongArityCtorPattern(pat.ctor.name, pat.fields.size, ctorTypes.size), pat.span)

                if (ctorTypes.isEmpty()) emptyList()
                else {
                    val vars = mutableListOf<PatternVar>()
                    ctorTypes.zip(pat.fields).forEach { (type, pattern) ->
                        vars += inferpattern(env, level, pattern, type)
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
                val tys: LabelMap<Type> = pat.labels.mapList { p ->
                    val rowTy = newVar(level)
                    vars += inferpattern(env, level, p, rowTy)
                    rowTy
                }
                unify(TRecord(TRowExtend(tys, newVar(level))), ty, pat.span)
                vars
            }
            is Pattern.ListP -> {
                if (pat.elems.isEmpty()) {
                    unify(TApp(TConst(primList), listOf(newVar(level))), ty, pat.span)
                    return emptyList()
                }

                val vars = mutableListOf<PatternVar>()
                val elemTy = newVar(level)
                unify(TApp(TConst(primList), listOf(elemTy)), ty, pat.span)

                pat.elems.forEach { p ->
                    vars += inferpattern(env, level, p, elemTy)
                }
                vars
            }
            is Pattern.ListHeadTail -> {
                val vars = mutableListOf<PatternVar>()
                val elemTy = newVar(level)
                val listTy = TApp(TConst(primList), listOf(elemTy))
                unify(listTy, ty, pat.span)

                vars += inferpattern(env, level, pat.head, elemTy)
                vars += inferpattern(env, level, pat.tail, listTy)
                vars
            }
            is Pattern.Named -> {
                val vars = mutableListOf<PatternVar>()
                vars += inferpattern(env, level, pat.pat, ty)
                vars += PatternVar(pat.name, ty, pat.span)
                vars
            }
            is Pattern.TypeTest -> {
                validateType(pat.type, env, pat.span)
                if (pat.alias != null) {
                    // we need special handling for Lists, Sets and Arrays
                    val type = when {
                        pat.type is TConst && pat.type.name == primList -> TApp(TConst(primList), listOf(tObject))
                        pat.type is TConst && pat.type.name == primSet -> TApp(TConst(primSet), listOf(tObject))
                        pat.type is TConst && pat.type.name == primArray -> TApp(TConst(primArray), listOf(tObject))
                        else -> pat.type
                    }
                    listOf(PatternVar(pat.alias, type, pat.span))
                } else emptyList()
            }
        }
    }

    private tailrec fun peelArgs(args: List<Type>, t: Type): Pair<List<Type>, Type> = when {
        t is TArrow -> {
            if (t.ret is TArrow) peelArgs(args + t.args, t.ret)
            else args + t.args to t.ret
        }
        t is TVar && t.tvar is TypeVar.Link -> peelArgs(args, (t.tvar as TypeVar.Link).type)
        else -> args to t
    }

    fun generalize(level: Level, ty: Type): Type = when (ty) {
        is TVar -> {
            val tv = ty.tvar
            when {
                tv is TypeVar.Link -> generalize(level, tv.type)
                tv is TypeVar.Unbound && tv.level > level -> TVar(TypeVar.Generic(tv.id))
                else -> ty
            }
        }
        is TApp -> ty.copy(type = generalize(level, ty.type), types = ty.types.map { generalize(level, it) })
        is TArrow -> ty.copy(args = ty.args.map { generalize(level, it) }, ret = generalize(level, ty.ret))
        is TImplicit -> ty.copy(generalize(level, ty.type))
        is TRecord -> ty.copy(generalize(level, ty.row))
        is TRowExtend -> {
            ty.copy(labels = ty.labels.mapList { generalize(level, it) }, row = generalize(level, ty.row))
        }
        is TConst, is TRowEmpty -> ty
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
        else -> inferError(E.NOT_A_FUNCTION, span)
    }

    private fun peelImplicits(ty: Type): Pair<Type, List<Type>> {
        val imps = mutableListOf<Type>()
        var ret = ty
        while (ret is TArrow && ret.args[0] is TImplicit) {
            imps += ret.args[0]
            ret = ret.ret
        }
        return ret to imps
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

    /**
     * Change this recursive expression in a way that
     * it can be infered.
     *
     * Ex.: fun x = fun x
     *      $fun fun$rec x = fun$rec x
     */
    private fun funToFixpoint(name: String, expr: Expr): Pair<String, Expr> {
        val binder = "${name}\$rec"
        return binder to Expr.Lambda(Binder(name, expr.span), expr, expr.span)
    }

    /**
     * Check if `name` is shadowing some variable
     * and throw an error if that's the case.
     */
    private fun checkShadow(env: Env, name: String, span: Span) {
        if (env.lookup(name) != null) inferError(E.shadowedVariable(name), span)
    }

    /**
     * Check if `type` is shadowing some other type
     * and throw an error if that's the case.
     */
    private fun checkShadowType(env: Env, type: String, span: Span) {
        if (env.lookupType(type) != null) inferError(E.duplicatedType(type), span)
    }

    private fun validateType(type: Type, env: Env, span: Span) {
        type.everywhereUnit { ty ->
            if (ty is TConst && env.lookupType(ty.name) == null) {
                inferError(E.undefinedType(ty.show()), ty.span ?: span)
            }
        }
    }

    private tailrec fun validateImplicitArgs(exp: Expr.Lambda, ty: TArrow) {
        val argTy = ty.args[0]
        val arg = exp.binder
        if (arg.isImplicit && argTy !is TImplicit)
            inferError(E.implicitTypesDontMatch(arg.toString(), argTy.show()), arg.span)

        if (exp.body is Expr.Lambda && ty.ret is TArrow) validateImplicitArgs(exp.body, ty.ret)
    }

    private fun getDataType(d: Decl.TypeDecl, moduleName: String): Pair<Type, Map<String, TVar>> {
        val kind = if (d.tyVars.isEmpty()) Kind.Star else Kind.Constructor(d.tyVars.size)
        val raw = TConst("$moduleName.${d.name}", kind).span(d.span)

        return if (d.tyVars.isEmpty()) raw to emptyMap()
        else {
            val varsMap = d.tyVars.map { it to newGenVar() }
            val map = mutableMapOf<String, TVar>()
            varsMap.forEach { (name, vvar) -> map[name] = vvar }
            val vars = varsMap.map { it.second }

            TApp(raw, vars).span(d.span) to map
        }
    }

    private fun getCtorType(dc: DataConstructor, dataType: Type, map: Map<String, TVar>): Type {
        return when (dataType) {
            is TConst -> if (dc.args.isEmpty()) dataType else nestArrows(dc.args, dataType).span(dc.span)
            is TApp -> {
                val args = dc.args.map { it.substConst(map) }
                nestArrows(args, dataType).span(dc.span)
            }
            else -> internalError("Got absurd type for data constructor: $dataType")
        }
    }

    private fun makeWarner(ast: Module) = { msg: String, span: Span ->
        val warn = CompilerProblem(
            msg,
            ProblemContext.TYPECHECK,
            span,
            ast.sourceName,
            ast.name.value,
            severity = Severity.WARN
        )
        warnings += warn
    }
}