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
import novah.data.*
import novah.frontend.Span
import novah.frontend.error.Action
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Severity
import novah.frontend.typechecker.Type.Companion.nestArrows
import novah.main.*
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import novah.frontend.error.Errors as E

class Inference(private val tc: Typechecker, private val classLoader: NovahClassLoader) {

    private val implicitsToCheck = mutableListOf<Expr>()
    private val errors = mutableSetOf<CompilerProblem>()
    
    private val env = tc.env
    private val uni = tc.uni
    private val instances = InstanceSearch(tc)

    fun errors(): Set<CompilerProblem> = errors

    /**
     * Infer the whole module
     */
    fun infer(ast: Module): ModuleEnv {
        errors.clear()
        val decls = mutableMapOf<String, DeclRef>()
        val types = mutableMapOf<String, TypeDeclRef>()
        val warner = makeWarner(ast)

        // add the fixpoint operator to the context for recursive functions
        // TODO: add this to the env as primitive
        val vvar = tc.newGenVar()
        env.extend("\$fix", TArrow(listOf(TArrow(listOf(vvar), vvar)), vvar))

        val datas = ast.decls.filterIsInstance<Decl.TypeDecl>()
        datas.forEach { d ->
            val (ty, map) = getDataType(d, ast.name.value)
            checkShadowType(env, d.name.value, d.span)
            env.extendType("${ast.name.value}.${d.name.value}", ty)

            d.dataCtors.forEach { dc ->
                val dcname = dc.name.value
                val dcty = getCtorType(dc, ty, map)
                checkShadow(env, dcname, dc.span)
                env.extend(dcname, dcty)
                Environment.cacheConstructorType("${ast.name.value}.$dcname", dcty)
                decls[dcname] = DeclRef(dcty, dc.visibility, false, null)
            }
            types[d.name.value] =
                TypeDeclRef(ty, d.visibility, d.isOpaque, d.dataCtors.map { it.name.value }, d.comment)
        }
        datas.forEach { d ->
            d.dataCtors.forEach { dc ->
                tc.checkWellFormed(env.lookup(dc.name.value)!!, dc.span)
            }
        }

        val vals = ast.decls.filterIsInstance<Decl.ValDecl>()
        vals.filter { it.exp is Expr.Ann }.forEach { decl ->
            val expr = decl.exp as Expr.Ann
            val name = decl.name.value
            checkShadow(env, name, decl.span)
            env.extend(name, expr.annType)
            if (decl.isInstance) env.extendInstance(name, expr.annType)
        }

        for (decl in vals) {
            implicitsToCheck.clear()
            tc.context?.apply { this.decl = decl }
            val name = decl.name.value
            val isAnnotated = decl.exp is Expr.Ann
            try {
                if (!isAnnotated) checkShadow(env, name, decl.span)

                val newEnv = env.fork()
                val ty = if (decl.recursive) {
                    newEnv.remove(name)
                    inferRecursive(name, decl.exp, newEnv, 0)
                } else infer(newEnv, 0, decl.exp)

                if (implicitsToCheck.isNotEmpty()) instances.instanceSearch(implicitsToCheck)

                val genTy = generalize(-1, ty)
                env.extend(name, genTy)
                if (decl.isInstance) env.extendInstance(name, genTy)
                decls[name] = DeclRef(genTy, decl.visibility, decl.isInstance, decl.comment)

                if (!isAnnotated) {
                    val fix = "$name : ${genTy.show(false)}"
                    warner(E.noTypeAnnDecl(name, genTy.show()), decl.span, Action.NoType(fix))
                }
            } catch (ie: InferenceError) {
                decl.typeError = true
                errors += CompilerProblem(ie.msg, ie.span, ast.sourceName, ast.name.value, tc.context?.copy())
                tc.context?.types?.clear()
            }
        }

        return ModuleEnv(decls, types)
    }

    private fun infer(env: Env, level: Level, exp: Expr): Type = when (exp) {
        is Expr.Int32 -> exp.withType(tInt32)
        is Expr.Int64 -> exp.withType(tInt64)
        is Expr.Float32 -> exp.withType(tFloat32)
        is Expr.Float64 -> exp.withType(tFloat64)
        is Expr.CharE -> exp.withType(tChar)
        is Expr.Bool -> exp.withType(tBoolean)
        is Expr.StringE -> exp.withType(tString)
        is Expr.Unit -> exp.withType(tUnit)
        is Expr.Null -> exp.withType(tc.instantiate(level, tNullable))
        is Expr.Var -> {
            val ty = env.lookup(exp.fullname()) ?: inferError(E.undefinedVar(exp.name), exp.span)
            exp.withType(tc.instantiate(level, ty))
        }
        is Expr.Constructor -> {
            val ty = env.lookup(exp.fullname()) ?: inferError(E.undefinedVar(exp.name), exp.span)
            exp.withType(tc.instantiate(level, ty))
        }
        is Expr.ImplicitVar -> {
            val ty = env.lookup(exp.fullname()) ?: inferError(E.undefinedVar(exp.name), exp.span)
            exp.withType(tc.instantiate(level, ty))
        }
        is Expr.Lambda -> {
            val binder = exp.binder
            checkShadow(env, binder.name, binder.span)
            // if the binder is annotated, use it
            val par = if (binder.type != null) binder.type!! else tc.newVar(level)
            val param = if (binder.isImplicit) TImplicit(par) else par
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
            uni.unify(params.last(), argTy, exp.arg.span)

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
            tc.context?.apply { types.push(exp.annType) }
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
                    uni.unify(type, infer(env, level, expr), expr.span)
                    if (expr is Expr.Lambda && type is TArrow)
                        validateImplicitArgs(expr, type)
                    type
                }
            }
            tc.context?.apply { types.pop() }
            expr.withType(resTy)
            exp.withType(resTy)
        }
        is Expr.If -> {
            uni.unify(tBoolean, infer(env, level, exp.cond), exp.cond.span)
            val thenTy = infer(env, level, exp.thenCase)
            val elseTy = infer(env, level, exp.elseCase)
            uni.unify(thenTy, elseTy, exp.span)
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
            val resType = tc.newVar(level)

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
                    uni.unify(tBoolean, infer(newEnv, level, case.guard), case.patternSpan())
                }

                val ty = infer(newEnv, level, case.exp)
                uni.unify(resType, ty, case.exp.span)
            }
            exp.withType(resType)
        }
        is Expr.RecordEmpty -> exp.withType(TRecord(TRowEmpty()))
        is Expr.RecordSelect -> {
            val rest = tc.newVar(level)
            val field = tc.newVar(level)
            val param = TRecord(TRowExtend(singletonPMap(exp.label.value, field), rest))
            uni.unify(param, infer(env, level, exp.exp), exp.span)
            exp.withType(field)
        }
        is Expr.RecordRestrict -> {
            val rest = tc.newVar(level)
            val field = tc.newVar(level)
            val param = TRecord(TRowExtend(singletonPMap(exp.label, field), rest))
            val returnTy = TRecord(rest)
            uni.unify(param, infer(env, level, exp.exp), exp.span)
            exp.withType(returnTy)
        }
        is Expr.RecordUpdate -> {
            val field = infer(env, level, exp.value)
            val rest = tc.newVar(level)
            val recTy = TRecord(TRowExtend(singletonPMap(exp.label.value, field), rest))
            uni.unify(recTy, infer(env, level, exp.exp), exp.span)
            exp.withType(recTy)
        }
        is Expr.RecordExtend -> {
            val labelTys = exp.labels.mapList { infer(env, level, it) }
            val rest = tc.newVar(level)
            uni.unify(TRecord(rest), infer(env, level, exp.exp), exp.span)
            val ty = TRecord(TRowExtend(labelTys, rest))
            exp.withType(ty)
        }
        is Expr.RecordMerge -> {
            val rest1 = tc.newVar(level)
            val rest2 = tc.newVar(level)
            val param1 = TRecord(TRowExtend(LabelMap.empty(), rest1))
            val param2 = TRecord(TRowExtend(LabelMap.empty(), rest2))
            uni.unify(param1, infer(env, level, exp.exp1), exp.span)
            uni.unify(param2, infer(env, level, exp.exp2), exp.span)
            val (labels1, row1) = uni.matchRowType(rest1)
            val (labels2, row2) = uni.matchRowType(rest2)
            val row = when {
                row1 is TRowEmpty -> row2
                row2 is TRowEmpty -> row1
                else -> inferError(E.RECORD_MERGE, exp.span)
            }
            val ty = TRecord(TRowExtend(labels2.merge(labels1), row))
            exp.withType(ty)
        }
        is Expr.ListLiteral -> {
            val ty = tc.newVar(level)
            exp.exps.forEach { e ->
                uni.unify(ty, infer(env, level, e), e.span)
            }
            val res = TApp(TConst(primList), listOf(ty))
            exp.withType(res)
        }
        is Expr.SetLiteral -> {
            val ty = tc.newVar(level)
            exp.exps.forEach { e ->
                uni.unify(ty, infer(env, level, e), e.span)
            }
            val res = TApp(TConst(primSet), listOf(ty))
            exp.withType(res)
        }
        is Expr.Throw -> {
            val ty = infer(env, level, exp.exp)
            val res = classLoader.isException(ty.typeNameOrEmpty())
            if (!res) {
                inferError(E.notException(ty.show()), exp.span)
            }
            // throw returns anything
            val ret = tc.newVar(level)
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
                uni.unify(resTy, ty, case.exp.span)
            }
            // the result type of the finally expression is discarded
            if (exp.finallyExp != null) infer(env, level, exp.finallyExp)
            exp.withType(resTy)
        }
        is Expr.While -> {
            uni.unify(tBoolean, infer(env, level, exp.cond), exp.cond.span)
            exp.exps.forEach { e -> infer(env, level, e) }
            // while always returns unit
            exp.withType(tUnit)
        }
        is Expr.TypeCast -> {
            // a type cast ignores the type checker and just returns the cast type
            // very dangerous!
            infer(env, level, exp.exp)
            exp.withType(exp.cast)
        }
        is Expr.ClassConstant -> {
            val clazz = exp.clazz.value
            val classTy = env.lookupType(javaToNovah(clazz))
                ?: inferError(E.undefinedType(clazz), exp.clazz.span)

            val ty = TApp(TConst("java.lang.Class"), listOf(classTy))
            exp.withType(ty)
        }
        is Expr.ForeignStaticField -> {
            Reflection.typeCache.clear()
            val clazz = exp.clazz.value
            val jclass = classLoader.safeFindClass(clazz) ?: inferError(E.undefinedType(clazz), exp.clazz.span)

            val field = Reflection.findField(jclass, exp.fieldName.value)
                ?: inferError(E.fieldNotFound(exp.fieldName.value, clazz), exp.span)
            if (!Reflection.isStatic(field)) {
                inferError(E.nonStaticField(exp.fieldName.value, clazz), exp.fieldName.span)
            }
            if (!Reflection.isPublic(field)) {
                inferError(E.nonPublicField(exp.fieldName.value, clazz), exp.fieldName.span)
            }

            val ty = Reflection.collectType(tc, field.genericType, level)
            exp.field = field
            exp.withType(ty)
        }
        is Expr.ForeignField -> {
            Reflection.typeCache.clear()
            val objTy = infer(env, level, exp.exp).realType()
            val clazz = Reflection.findJavaType(objTy) ?: inferError(E.invalidJavaType(objTy.show()), exp.exp.span)
            val jclass = classLoader.safeFindClass(clazz) ?: inferError(E.undefinedType(clazz), exp.exp.span)

            val field = Reflection.findField(jclass, exp.fieldName.value)
                ?: inferError(E.fieldNotFound(exp.fieldName.value, clazz), exp.span)
            if (Reflection.isStatic(field)) inferError(E.staticField(exp.fieldName.value, clazz), exp.fieldName.span)
            if (!Reflection.isPublic(field)) {
                inferError(E.nonPublicField(exp.fieldName.value, clazz), exp.fieldName.span)
            }

            val ty = Reflection.collectType(tc, field.genericType, level)
            exp.field = field
            exp.withType(ty)
        }
        is Expr.ForeignStaticFieldSetter -> {
            val fieldTy = infer(env, level, exp.field)
            if (Reflection.isImutable(exp.field.field!!))
                inferError(E.immutableField(exp.field.fieldName.value, exp.field.clazz.value), exp.field.span)
            val argTy = infer(env, level, exp.value)
            uni.unify(fieldTy, argTy, exp.span)
            exp.withType(fieldTy)
            tUnit
        }
        is Expr.ForeignFieldSetter -> {
            val fieldTy = infer(env, level, exp.field)
            if (Reflection.isImutable(exp.field.field!!)) {
                val clazz = exp.field.field!!.declaringClass.name
                inferError(E.immutableField(exp.field.fieldName.value, clazz), exp.field.span)
            }
            val argTy = infer(env, level, exp.value)
            uni.unify(fieldTy, argTy, exp.span)
            exp.withType(fieldTy)
            tUnit
        }
        is Expr.ForeignStaticMethod -> {
            Reflection.typeCache.clear()
            val clazz = exp.clazz.value
            val argCount = exp.args.size
            val jclass = classLoader.safeFindClass(clazz) ?: inferError(E.undefinedType(clazz), exp.clazz.span)
            val method = exp.methodName

            if (method.value == "new") { // it's a constructor
                val ctors = Reflection.findConstructors(jclass, argCount)
                if (ctors.isEmpty()) inferError(E.ctorNotFound(clazz, argCount), method.span)

                val tys = exp.args.map { infer(env, level, it) }
                val found = unifyConstructors(ctors, tys, level, exp.span)
                    ?: inferError(E.methodDidNotUnify(method.value, clazz), method.span)
                if (!Reflection.isPublic(found)) inferError(E.nonPublicCtor(clazz), method.span)

                val ty = Reflection.collectType(tc, found.declaringClass, level)
                exp.ctor = found
                exp.withType(ty)
            } else { // it's a method
                val methods = Reflection.findStaticMethods(jclass, method.value, argCount)
                if (methods.isEmpty()) inferError(E.staticMethodNotFound(method.value, clazz, argCount), method.span)

                val tys = exp.args.map { infer(env, level, it) }
                val found = unifyMethods(methods, tys, level, exp.span)
                    ?: inferError(E.methodDidNotUnify(method.value, clazz), method.span)
                if (!Reflection.isPublic(found)) {
                    inferError(E.nonPublicMethod(method.value, clazz), method.span)
                }

                val ty = Reflection.collectType(tc, found.genericReturnType, level)
                exp.method = found
                exp.withType(ty)
            }
        }
        is Expr.ForeignMethod -> {
            Reflection.typeCache.clear()
            val objTy = infer(env, level, exp.exp).realType()
            val clazz = Reflection.findJavaType(objTy) ?: inferError(E.invalidJavaType(objTy.show()), exp.exp.span)
            val argCount = exp.args.size
            val jclass = classLoader.safeFindClass(clazz) ?: inferError(E.undefinedType(clazz), exp.exp.span)

            val methods = Reflection.findNonStaticMethods(jclass, exp.methodName.value, argCount)
            if (methods.isEmpty())
                inferError(E.methodNotFound(exp.methodName.value, clazz, argCount), exp.methodName.span)

            val tys = exp.args.map { infer(env, level, it) }
            val found = unifyMethods(methods, tys, level, exp.span)
                ?: inferError(E.methodDidNotUnify(exp.methodName.value, clazz), exp.methodName.span)
            if (!Reflection.isPublic(found)) {
                inferError(E.nonPublicMethod(exp.methodName.value, clazz), exp.methodName.span)
            }

            val ty = Reflection.collectType(tc, found.genericReturnType, level)
            exp.method = found
            exp.withType(ty)
        }
    }

    private data class PatternVar(val name: String, val type: Type, val span: Span)

    private fun inferpattern(env: Env, level: Level, pat: Pattern, ty: Type): List<PatternVar> {
        val res = when (pat) {
            is Pattern.LiteralP -> {
                uni.unify(ty, infer(env, level, pat.lit.e), pat.span)
                emptyList()
            }
            is Pattern.Wildcard -> emptyList()
            is Pattern.Unit -> {
                uni.unify(ty, tUnit, pat.span)
                emptyList()
            }
            is Pattern.Var -> listOf(PatternVar(pat.v.name, ty, pat.span))
            is Pattern.Ctor -> {
                val cty = infer(env, level, pat.ctor)

                val (ctorTypes, ret) = peelArgs(listOf(), cty)
                uni.unify(ret, ty, pat.ctor.span)

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
                    uni.unify(TRecord(tc.newVar(level)), ty, pat.span)
                    return emptyList()
                }

                val vars = mutableListOf<PatternVar>()
                val tys: LabelMap<Type> = pat.labels.mapList { p ->
                    val rowTy = tc.newVar(level)
                    vars += inferpattern(env, level, p, rowTy)
                    rowTy
                }
                uni.unify(TRecord(TRowExtend(tys, tc.newVar(level))), ty, pat.span)
                vars
            }
            is Pattern.ListP -> {
                if (pat.elems.isEmpty()) {
                    uni.unify(TApp(TConst(primList), listOf(tc.newVar(level))), ty, pat.span)
                    return emptyList()
                }

                val vars = mutableListOf<PatternVar>()
                val elemTy = tc.newVar(level)
                uni.unify(TApp(TConst(primList), listOf(elemTy)), ty, pat.span)

                pat.elems.forEach { p ->
                    vars += inferpattern(env, level, p, elemTy)
                }
                vars
            }
            is Pattern.ListHeadTail -> {
                val vars = mutableListOf<PatternVar>()
                val elemTy = tc.newVar(level)
                val listTy = TApp(TConst(primList), listOf(elemTy))
                uni.unify(listTy, ty, pat.span)

                vars += inferpattern(env, level, pat.head, elemTy)
                vars += inferpattern(env, level, pat.tail, listTy)
                vars
            }
            is Pattern.Named -> {
                val vars = mutableListOf<PatternVar>()
                vars += inferpattern(env, level, pat.pat, ty)
                vars += PatternVar(pat.name.value, ty, pat.span)
                vars
            }
            is Pattern.TypeTest -> {
                validateType(pat.test, env, pat.span)
                if (pat.alias != null) {
                    // we need special handling for Lists, Sets and Arrays
                    val type = when {
                        pat.test is TConst && pat.test.name == primList -> TApp(TConst(primList), listOf(tObject))
                        pat.test is TConst && pat.test.name == primSet -> TApp(TConst(primSet), listOf(tObject))
                        pat.test is TConst && pat.test.name == primArray -> TApp(TConst(primArray), listOf(tObject))
                        else -> pat.test
                    }
                    listOf(PatternVar(pat.alias, type, pat.span))
                } else emptyList()
            }
        }
        pat.type = ty
        return res
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
            val params = (1..numParams).map { tc.newVar(unb.level) }.reversed()
            val retur = tc.newVar(unb.level)
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

    private fun unifyMethods(methods: List<Method>, tys: List<Type>, level: Int, span: Span): Method? {
        if (methods.size == 1) {
            val mtys = methods[0].genericParameterTypes.map { Reflection.collectType(tc, it, level) }
            return if (unifyForeignPars(mtys, tys, span)) methods[0] else null
        }
        return methods.find { method ->
            val mtys = method.genericParameterTypes.map { Reflection.collectType(tc, it, level) }
            try {
                // we can't commit to the unification because this method has many overloads,
                // so we clone the types until we find a match and reunify the matching types
                mtys.zip(tys).forEach { (mty, ty) -> uni.unifySimple(mty.clone(), ty.clone(), span) }
                mtys.zip(tys).forEach { (mty, ty) -> uni.unifySimple(mty, ty, span) }
                true
            } catch (_: Unification.UnifyException) {
                false
            }
        }
    }

    private fun unifyConstructors(
        ctors: List<Constructor<*>>,
        tys: List<Type>,
        level: Int,
        span: Span
    ): Constructor<*>? {
        if (ctors.size == 1) {
            val mtys = ctors[0].genericParameterTypes.map { Reflection.collectType(tc, it, level) }
            return if (unifyForeignPars(mtys, tys, span)) ctors[0] else null
        }
        return ctors.find { ctor ->
            val mtys = ctor.genericParameterTypes.map { Reflection.collectType(tc, it, level) }
            try {
                // see `unifyMethods` for an explanation
                mtys.zip(tys).forEach { (mty, ty) -> uni.unifySimple(mty.clone(), ty.clone(), span) }
                mtys.zip(tys).forEach { (mty, ty) -> uni.unifySimple(mty, ty, span) }
                true
            } catch (_: Unification.UnifyException) {
                false
            }
        }
    }

    private fun unifyForeignPars(ftys: List<Type>, tys: List<Type>, span: Span): Boolean {
        return try {
            ftys.zip(tys).forEach { (mty, ty) -> uni.unifySimple(mty, ty, span) }
            true
        } catch (_: Unification.UnifyException) {
            false
        }
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
        val raw = TConst("$moduleName.${d.name.value}", kind).span(d.span)

        return if (d.tyVars.isEmpty()) raw to emptyMap()
        else {
            val varsMap = d.tyVars.map { it to tc.newGenVar() }
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

    private fun makeWarner(ast: Module) = { msg: String, span: Span, action: Action ->
        val warn = CompilerProblem(
            msg,
            span,
            ast.sourceName,
            ast.name.value,
            severity = Severity.WARN,
            action = action
        )
        errors += warn
    }
}