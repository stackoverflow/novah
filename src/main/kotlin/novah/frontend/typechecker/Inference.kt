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

import novah.Util.hasDuplicates
import novah.Util.internalError
import novah.ast.canonical.*
import novah.data.mapList
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.frontend.typechecker.Prim.tBoolean
import novah.frontend.typechecker.Prim.tByte
import novah.frontend.typechecker.Prim.tChar
import novah.frontend.typechecker.Prim.tDouble
import novah.frontend.typechecker.Prim.tFloat
import novah.frontend.typechecker.Prim.tInt
import novah.frontend.typechecker.Prim.tLong
import novah.frontend.typechecker.Prim.tShort
import novah.frontend.typechecker.Prim.tString
import novah.frontend.typechecker.Prim.tUnit
import novah.frontend.typechecker.Subsumption.subsume
import novah.frontend.typechecker.Type.Companion.instantiateForall
import novah.frontend.typechecker.Type.Companion.nestApps
import novah.frontend.typechecker.Type.Companion.nestArrows
import novah.frontend.typechecker.Type.Companion.nestForalls
import novah.frontend.typechecker.Typechecker.context
import novah.frontend.typechecker.Typechecker.freshName
import novah.frontend.typechecker.WellFormed.wfContext
import novah.frontend.typechecker.WellFormed.wfType
import novah.main.CompilationError
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef
import novah.frontend.error.Errors as E

object Inference {

    fun infer(mod: Module): ModuleEnv {
        val decls = mutableMapOf<String, DeclRef>()
        val types = mutableMapOf<String, TypeDeclRef>()

        mod.decls.filterIsInstance<Decl.DataDecl>().forEach { ddecl ->
            val (ddtype, ddelem) = dataDeclToType(ddecl, mod.name)
            types[ddecl.name] = TypeDeclRef(ddtype, ddecl.visibility, ddecl.dataCtors.map { it.name })
            context.append(ddelem)

            dataConstructorsToType(ddecl, ddtype).forEach { (ctor, type) ->
                decls[ctor.name] = DeclRef(type, ctor.visibility)
                wfType(type, ctor.span)
                context.append(CVar(ctor.name, type))
            }
        }

        val vals = mod.decls.filterIsInstance<Decl.ValDecl>()
        vals.forEach { decl ->
            val expr = decl.exp
            val name = decl.name
            if (expr is Expr.Ann) {
                wfType(expr.annType, expr.span)
                context.append(CVar(name, expr.annType))
            } else {
                val t = freshName("t")
                context.append(CVar(name, TForall(t, TVar(t))))
            }
        }

        val errorNames = wfContext()
        val errors = errorNames.map { err ->
            val decl = vals.find { it.name == err } ?: internalError("Could not find defined variable $err.")
            val msg = if (decl.name[0].isUpperCase()) E.duplicatedType(err)
            else E.duplicatedVariable(err)
            CompilerProblem(msg, ProblemContext.TYPECHECK, decl.span, mod.sourceName, mod.name)
        }
        if (errors.isNotEmpty()) throw CompilationError(errors)

        vals.forEach { decl ->
            val ty = inferExpr(decl.exp)
            val name = decl.name
            context.replace<CVar>(name, CVar(name, ty))
            decls[decl.name] = DeclRef(ty, decl.visibility)
        }
        return ModuleEnv(decls, types)
    }

    private fun inferExpr(expr: Expr): Type {
        val mark = context.mark()
        val ty = generalizeFrom(mark, context.apply(infer(expr)))
        //expr.resolveMetas()

        if (!context.isComplete(mark)) inferError(E.INCOMPLETE_CONTEXT, expr.span)
        return ty
    }

    /**
     * The inference mode of the type checker.
     * Synthesizes a type for the given expression
     */
    private fun infer(exp: Expr): Type {
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
                val x = context.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(x.type)
            }
            is Expr.Constructor -> {
                val x = context.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(x.type)
            }
            is Expr.NativeFieldGet -> {
                val ty = context.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(ty.type)
            }
            is Expr.NativeFieldSet -> {
                val ty = context.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(ty.type)
            }
            is Expr.NativeMethod -> {
                val ty = context.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(ty.type)
            }
            is Expr.NativeConstructor -> {
                val ty = context.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(ty.type)
            }
            is Expr.Lambda -> {
                when (val pattern = exp.pattern) {
                    is FunparPattern.Ignored -> {
                        val bodyType = infer(exp.body)
                        val tvar = freshName("t")
                        val ty = TForall(tvar, TArrow(TVar(tvar), bodyType))
                        exp.withType(ty)
                    }
                    is FunparPattern.Unit -> {
                        val bodyType = infer(exp.body)
                        val ty = TArrow(tUnit, bodyType)
                        exp.withType(ty)
                    }
                    is FunparPattern.Bind -> {
                        val binder = pattern.binder.name
                        checkShadow(binder, exp.pattern.span)

                        val a = freshName(binder)
                        val b = freshName(binder)
                        val ta = TMeta(a)
                        val tb = TMeta(b)
                        val marker = context.mark()
                        context.append(CTMeta(a), CTMeta(b), CVar(binder, ta))
                        check(exp.body, tb)

                        val ty = context.apply(TArrow(ta, tb))
                        exp.withType(generalizeFrom(marker, ty))
                    }
                }
            }
            is Expr.App -> {
                val left = infer(exp.fn)
                val right = inferapp(context.apply(left), exp.arg)
                exp.withType(right)
            }
            is Expr.Ann -> {
                val ty = exp.annType
                wfType(ty, exp.span)
                check(exp.exp, ty)
                // set not only the type of the annotation but the type of the inner expression
                exp.exp.withType(ty)
                exp.withType(ty)
            }
            is Expr.If -> {
                check(exp.cond, tBoolean)
                val tt = infer(exp.thenCase)
                val te = infer(exp.elseCase)
                subsume(tt, te, exp.span)
                exp.withType(tt)
            }
            is Expr.Let -> {
                val ld = exp.letDef
                val binder = ld.binder.name
                checkShadow(binder, ld.binder.span)

                val mark = context.fork()
                // infer or check the binding
                if (ld.type != null) {
                    wfType(ld.type, ld.binder.span)
                    check(ld.expr, ld.type)
                    context.append(CVar(binder, ld.type))
                } else {
                    val typ = infer(ld.expr)
                    context.append(CVar(binder, typ))
                }

                // infer the body
                val ty = infer(exp.body)
                context.resetTo(mark)
                exp.withType(ty)
            }
            is Expr.Do -> {
                var ty: Type? = null
                for (e in exp.exps) ty = infer(e)
                exp.withType(ty!!)
            }
            is Expr.Match -> {
                val expType = infer(exp.exp)
                var resType: Type? = null

                exp.cases.forEach { case ->
                    val vars = checkpattern(case.pattern, expType)
                    withEnteringContext(vars) {
                        if (resType == null) resType = infer(case.exp)
                        else check(case.exp, resType!!)
                    }
                }
                exp.withType(resType ?: internalError(E.EMPTY_MATCH))
            }
            is Expr.RecordEmpty -> exp.withType(TRecord(TRowEmpty()))
            else -> TODO()
        }
    }

    /**
     * The check mode of the type checker.
     * Checks expression [exp] has type [ty].
     */
    fun check(exp: Expr, ty: Type) {
        when {
            exp is Expr.IntE && ty == tByte && exp.v >= Byte.MIN_VALUE && exp.v <= Byte.MAX_VALUE -> exp.withType(tByte)
            exp is Expr.IntE && ty == tShort && exp.v >= Short.MIN_VALUE && exp.v <= Short.MAX_VALUE ->
                exp.withType(tShort)
            exp is Expr.IntE && ty == tInt -> exp.withType(tInt)
            exp is Expr.LongE && ty == tLong -> exp.withType(tLong)
            exp is Expr.FloatE && ty == tFloat -> exp.withType(tFloat)
            exp is Expr.DoubleE && ty == tDouble -> exp.withType(tDouble)
            exp is Expr.StringE && ty == tString -> exp.withType(tString)
            exp is Expr.CharE && ty == tChar -> exp.withType(tChar)
            exp is Expr.Bool && ty == tBoolean -> exp.withType(tBoolean)
            exp is Expr.Unit -> exp.withType(tUnit)
            ty is TForall -> {
                val x = freshName(ty.name)
                context.append(CTVar(x))
                check(exp, instantiateForall(ty, TVar(x)))
            }
            ty is TArrow && exp is Expr.Lambda -> {
                when (exp.pattern) {
                    is FunparPattern.Ignored -> {
                        check(exp.body, ty.right)
                        exp.withType(ty)
                    }
                    is FunparPattern.Unit -> {
                        subsume(ty.left, tUnit, exp.span)
                        check(exp.body, ty.right)
                        exp.withType(ty)
                    }
                    is FunparPattern.Bind -> {
                        val x = exp.pattern.binder.name
                        val mark = context.fork()
                        context.append(CVar(x, ty.left))
                        check(exp.body, ty.right)
                        context.resetTo(mark)
                        exp.withType(ty)
                    }
                }
            }
            exp is Expr.If -> {
                check(exp.cond, tBoolean)
                check(exp.thenCase, ty)
                check(exp.elseCase, ty)
                exp.withType(exp.thenCase.type!!)
            }
            else -> {
                val type = infer(exp)
                subsume(context.apply(ty), context.apply(type), exp.span)
            }
        }
    }

    private fun inferapp(type: Type, expr: Expr): Type {
        return when (type) {
            is TForall -> {
                val x = freshName(type.name)
                context.append(CTMeta(x))
                inferapp(instantiateForall(type, TMeta(x)), expr)
            }
            is TMeta -> {
                val x = type.name
                val a = freshName(x)
                val b = freshName(x)
                val ta = TMeta(a)
                val tb = TMeta(b)
                context.replace<CTMeta>(x, CTMeta(b), CTMeta(a), CTMeta(x, TArrow(ta, tb)))
                check(expr, ta)
                tb
            }
            is TArrow -> {
                check(expr, type.left)
                type.right
            }
            else -> inferError(E.NOT_A_FUNCTION, expr.span)
        }
    }

    private fun checkpattern(pat: Pattern, type: Type): List<CVar> {
        tailrec fun peelArgs(args: List<Type>, t: Type): Pair<List<Type>, Type> = when (t) {
            is TArrow -> {
                if (t.right is TArrow) peelArgs(args + t.left, t.right)
                else args + t.left to t.right
            }
            else -> args to t
        }

        return when (pat) {
            is Pattern.LiteralP -> {
                check(pat.lit.e, type)
                listOf()
            }
            is Pattern.Wildcard -> listOf()
            is Pattern.Var -> {
                checkShadow(pat.name, pat.span)
                listOf(CVar(pat.name, type))
            }
            is Pattern.Ctor -> {
                val ctorTyp = infer(pat.ctor)

                val (ctorTypes, ret) = peelArgs(listOf(), instantiateForalls(ctorTyp))
                // TODO: change to unification
                subsume(ret, type, pat.span)

                if (ctorTypes.size - pat.fields.size != 0)
                    internalError("unified two constructors with wrong kinds: $pat")

                if (ctorTypes.isEmpty()) listOf()
                else {
                    val vars = mutableListOf<CVar>()
                    ctorTypes.zip(pat.fields).forEach { (type, pattern) ->
                        vars.addAll(checkpattern(pattern, type))
                    }
                    val varNames = vars.map { it.name }
                    if (varNames.hasDuplicates()) inferError(E.overlappingNamesInBinder(varNames), pat.span)
                    vars
                }
            }
        }
    }

    private fun generalize(unsolved: List<String>, type: Type): Type {
        val ns = type.unsolvedInType(unsolved)
        if (ns.isEmpty()) return type

        val m = mutableMapOf<String, TVar>()
        for (x in ns) m[x] = TVar(freshName(x))

        var res = type.substMetas(m)
        for (i in ns.size - 1 downTo 0) {
            res = TForall(m[ns[i]]!!.name, res)
        }
        return res
    }

    private fun generalizeFrom(index: Long, type: Type): Type {
        val ty = context.leaveWithUnsolved(index)
        return generalize(ty, type)
    }

    /**
     * Return the type defined by this ADT and the context element
     * associated with it.
     */
    private fun dataDeclToType(dd: Decl.DataDecl, moduleName: String): Pair<Type, Elem> {
        val typeName = "$moduleName.${dd.name}"
        val elem = CTVar(typeName)
        if (dd.tyVars.isEmpty()) return TConst(typeName) to elem

        val innerTypes = dd.tyVars.map { TVar(it) }
        return nestApps(TConst(typeName), innerTypes) to elem
    }

    /**
     * Takes a data declaration and returns all variables (constructors)
     * that should be added to the context.
     */
    private fun dataConstructorsToType(dd: Decl.DataDecl, dataType: Type): List<Pair<DataConstructor, Type>> {
        return dd.dataCtors.map { ctor ->
            val type = nestForalls(dd.tyVars, nestArrows(ctor.args + dataType))
            ctor to type
        }
    }

    private fun instantiateForalls(type: Type): Type = when (type) {
        is TForall -> {
            val x = freshName(type.name)
            context.append(CTMeta(x))
            instantiateForalls(instantiateForall(type, TMeta(x)))
        }
        is TArrow -> TArrow(instantiateForalls(type.left), instantiateForalls(type.right))
        is TApp -> TApp(instantiateForalls(type.left), instantiateForalls(type.right))
        is TRecord -> TRecord(instantiateForalls(type.row))
        is TRowExtend -> TRowExtend(type.labels.mapList { instantiateForalls(it) }, instantiateForalls(type.row))
        else -> type
    }

    /**
     * Check if `name` is shadowing some variable
     * and throw an error if that's the case.
     */
    private fun checkShadow(name: String, span: Span) {
        val shadow = context.lookupShadow<CVar>(name)
        if (shadow != null) inferError(E.shadowedVariable(name), span)
    }

    private inline fun <T> withEnteringContext(vars: List<CVar>, f: () -> T): T {
        return if (vars.isEmpty()) f()
        else {
            val mark = context.fork()
            context.append(*vars.toTypedArray())
            val res = f()
            context.resetTo(mark)
            res
        }
    }
}