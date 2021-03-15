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
package novah.optimize

import novah.Util.internalError
import novah.ast.canonical.*
import novah.ast.optimized.*
import novah.ast.optimized.Decl
import novah.data.*
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.frontend.error.Severity
import novah.frontend.hmftypechecker.*
import novah.frontend.matching.PatternCompilationResult
import novah.frontend.matching.PatternMatchingCompiler
import novah.ast.canonical.Binder as CBinder
import novah.ast.canonical.DataConstructor as CDataConstructor
import novah.ast.canonical.Decl.TypeDecl as CDataDecl
import novah.ast.canonical.Decl.ValDecl as CValDecl
import novah.ast.canonical.Expr as CExpr
import novah.ast.canonical.Module as CModule
import novah.frontend.error.Errors as E
import novah.frontend.hmftypechecker.Type as TType

/**
 * Converts the canonical AST to the
 * optimized version, ready for codegen
 */
class Optimizer(private val ast: CModule) {

    private var haslambda = false
    private val warnings = mutableListOf<CompilerProblem>()

    fun getWarnings(): List<CompilerProblem> = warnings

    fun convert(): Result<Module, CompilerProblem> {
        return try {
            PatternMatchingCompiler.addConsToCache(ast)
            Ok(ast.convert())
        } catch (ie: InferenceError) {
            Err(CompilerProblem(ie.msg, ie.ctx, ie.span, ast.sourceName, ast.name))
        }
    }

    private fun CModule.convert(): Module {
        val ds = mutableListOf<Decl>()
        for (d in decls) {
            if (d is CDataDecl) ds += d.convert()
            if (d is CValDecl) ds += d.convert()
        }
        return Module(internalize(name), sourceName, haslambda, ds)
    }

    private fun CValDecl.convert(): Decl.ValDecl = Decl.ValDecl(name, exp.convert(), visibility, span)

    private fun CDataDecl.convert(): Decl.TypeDecl =
        Decl.TypeDecl(name, tyVars, dataCtors.map { it.convert() }, visibility, span)

    private fun CDataConstructor.convert(): DataConstructor =
        DataConstructor(name, args.map { it.convert() }, visibility)

    private fun CExpr.convert(locals: List<String> = listOf()): Expr {
        val typ = if (type != null) {
            type!!.convert()
        } else {
            internalError("Received expression without type after type checking: $this")
        }
        return when (this) {
            is CExpr.IntE -> when (type) {
                tByte -> Expr.ByteE(v.toByte(), typ)
                tShort -> Expr.ShortE(v.toShort(), typ)
                tLong -> Expr.LongE(v.toLong(), typ)
                else -> Expr.IntE(v, typ)
            }
            is CExpr.LongE -> Expr.LongE(v, typ)
            is CExpr.FloatE -> Expr.FloatE(v, typ)
            is CExpr.DoubleE -> Expr.DoubleE(v, typ)
            is CExpr.StringE -> Expr.StringE(v, typ)
            is CExpr.CharE -> Expr.CharE(v, typ)
            is CExpr.Bool -> Expr.Bool(v, typ)
            is CExpr.Var -> {
                if (name in locals) Expr.LocalVar(name, typ)
                else {
                    val cname = internalize(moduleName ?: ast.name) + "/Module"
                    Expr.Var(name, cname, typ)
                }
            }
            is CExpr.Constructor -> {
                val ctorName = fullname(moduleName ?: ast.name)
                val arity = PatternMatchingCompiler.getFromCache(ctorName)?.arity
                    ?: internalError("Could not find constructor $name")
                Expr.Constructor(internalize(ctorName), arity, typ)
            }
            is CExpr.ImplicitVar -> {
                if (name in locals) Expr.LocalVar(name, typ)
                else {
                    val cname = internalize(moduleName ?: ast.name) + "/Module"
                    Expr.Var(name, cname, typ)
                }
            }
            is CExpr.Lambda -> {
                haslambda = true
                val bind = pattern.convert()
                val ls = if (bind != null) locals + bind else locals
                Expr.Lambda(bind, body.convert(ls), type = typ)
            }
            is CExpr.App -> {
                val pair = unrollForeignApp(this)
                if (pair != null) {
                    val (exp, pars) = pair
                    when (exp) {
                        // native getter will never be static here
                        is CExpr.NativeFieldGet -> Expr.NativeFieldGet(exp.field, pars[0].convert(locals), typ)
                        is CExpr.NativeFieldSet -> {
                            if (exp.isStatic)
                                Expr.NativeStaticFieldSet(exp.field, pars[0].convert(locals), typ)
                            else Expr.NativeFieldSet(exp.field, pars[0].convert(locals), pars[1].convert(locals), typ)
                        }
                        is CExpr.NativeMethod -> {
                            if (exp.isStatic)
                                Expr.NativeStaticMethod(exp.method, pars.map { it.convert(locals) }, typ)
                            else {
                                val nativePars = pars.map { it.convert(locals) }
                                Expr.NativeMethod(exp.method, nativePars[0], nativePars.drop(1), typ)
                            }
                        }
                        is CExpr.NativeConstructor -> Expr.NativeCtor(exp.ctor, pars.map { it.convert(locals) }, typ)
                        else -> internalError("Problem in `unrollForeignApp`, got non-native expression: $exp")
                    }
                } else {
                    if (implicitContext != null) {
                        val app = resolvedImplicits().fold(fn.convert(locals)) { acc, argg ->
                            // the argument and return type of the function doesn't matter here
                            Expr.App(acc, argg.convert(locals), Type.TFun(typ, typ))
                        }
                        Expr.App(app, arg.convert(locals), typ)
                    } else Expr.App(fn.convert(locals), arg.convert(locals), typ)
                }
            }
            is CExpr.If -> Expr.If(
                listOf(cond.convert(locals) to thenCase.convert(locals)),
                elseCase.convert(locals),
                typ
            )
            is CExpr.Let -> {
                val binder = letDef.binder.convert()
                Expr.Let(binder, letDef.expr.convert(locals), body.convert(locals + binder), typ)
            }
            is CExpr.Ann -> exp.convert(locals)
            is CExpr.Do -> Expr.Do(exps.map { it.convert(locals) }, typ)
            is CExpr.Match -> {
                val match = cases.map { PatternMatchingCompiler.convert(it, ast.name) }
                val compRes = PatternMatchingCompiler<Pattern>().compile(match)
                reportPatternMatch(compRes, this)
                desugarMatch(this, typ, locals)
            }
            is CExpr.NativeFieldGet -> {
                if (!Reflection.isStatic(field))
                    inferError(E.unkonwnArgsToNative(name, "getter"), span, ProblemContext.FOREIGN)
                Expr.NativeStaticFieldGet(field, typ)
            }
            is CExpr.NativeFieldSet -> inferError(E.unkonwnArgsToNative(name, "setter"), span, ProblemContext.FOREIGN)
            is CExpr.NativeMethod -> inferError(E.unkonwnArgsToNative(name, "method"), span, ProblemContext.FOREIGN)
            is CExpr.NativeConstructor -> inferError(
                E.unkonwnArgsToNative(name, "constructor"),
                span,
                ProblemContext.FOREIGN
            )
            is CExpr.Unit -> Expr.Unit(typ)
            is CExpr.RecordEmpty -> Expr.RecordEmpty(typ)
            is CExpr.RecordExtend -> Expr.RecordExtend(labels.mapList { it.convert(locals) }, exp.convert(locals), typ)
            is CExpr.RecordSelect -> Expr.RecordSelect(exp.convert(locals), label, typ)
            is CExpr.RecordRestrict -> Expr.RecordRestrict(exp.convert(locals), label, typ)
            is CExpr.VectorLiteral -> Expr.VectorLiteral(exps.map { it.convert(locals) }, typ)
            is CExpr.SetLiteral -> Expr.SetLiteral(exps.map { it.convert(locals) }, typ)
        }
    }

    private fun CBinder.convert(): String = name

    private fun FunparPattern.convert(): String? = when (this) {
        is FunparPattern.Ignored -> null
        is FunparPattern.Unit -> null
        is FunparPattern.Bind -> binder.convert()
    }

    private fun TType.convert(): Type = when (this) {
        is TConst -> {
            if (show(false)[0].isLowerCase()) Type.TVar(name.toUpperCase(), true)
            else
                Type.TVar(getPrimitiveTypeName(this))
        }
        is TApp -> {
            val ty = type.convert() as Type.TVar
            Type.TConstructor(ty.name, types.map { it.convert() })
        }
        // the only functions that can have more than 1 argument are native ones
        // but those don't need a type as we get the type from the reflected method/field/constructor
        is TArrow -> Type.TFun(args[0].convert(), ret.convert())
        is TForall -> type.convert()
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.convert()
                is TypeVar.Bound -> Type.TVar("T${tv.id}", true)
                is TypeVar.Generic ->
                    internalError("got unbound generic variable after typechecking: ${show(true)} at $span")
                is TypeVar.Unbound ->
                    internalError("got unbound variable after typechecking: ${show(true)} at $span")
            }
        }
        // records always have the same type
        is TRowEmpty -> Type.TVar(RECORD_TYPE)
        is TRecord -> Type.TVar(RECORD_TYPE)
        is TRowExtend -> Type.TVar(RECORD_TYPE)
        is TImplicit -> type.convert()
    }

    private val rteCtor = RuntimeException::class.java.constructors.find {
        it.parameterCount == 1 && it.parameterTypes[0].canonicalName == "java.lang.String"
    }!!

    private val stringType = tString.convert()
    private val boolType = tBoolean.convert()

    private fun makeThrow(msg: String): Expr {
        return Expr.Throw(
            Expr.NativeCtor(
                rteCtor,
                listOf(Expr.StringE(msg, stringType)),
                Type.TVar("java/lang/RuntimeException")
            )
        )
    }

    private class VarDef(val name: String, val exp: Expr)

    private fun desugarMatch(m: CExpr.Match, type: Type, locals: List<String>): Expr {
        val tru = Expr.Bool(true, boolType)

        fun mkCtorField(
            name: String,
            fieldIndex: Int,
            ctorExpr: Expr,
            ctorType: Type,
            fieldType: Type,
            expectedFieldType: Type?
        ): Expr {
            val castedExpr = Expr.Cast(ctorExpr, ctorType)
            val field = Expr.ConstructorAccess(name, fieldIndex, castedExpr, fieldType)
            return if (expectedFieldType == null || fieldType == expectedFieldType || fieldType.isMono()) field
            else Expr.Cast(field, expectedFieldType)
        }

        fun desugarPattern(p: Pattern, exp: Expr): Pair<Expr, List<VarDef>> = when (p) {
            is Pattern.Wildcard -> tru to emptyList()
            is Pattern.Var -> tru to listOf(VarDef(p.name, exp))
            is Pattern.LiteralP -> Expr.OperatorApp("==", listOf(exp, p.lit.e.convert(locals)), boolType) to emptyList()
            is Pattern.Ctor -> {
                val conds = mutableListOf<Expr>()
                val vars = mutableListOf<VarDef>()

                val (fieldTypes, _) = peelArgs(p.ctor.type!!)
                val expectedFieldTypes = (exp.type as? Type.TConstructor)?.types ?: emptyList()

                val ctor = p.ctor.convert(locals) as Expr.Constructor
                val name = p.ctor.fullname(ast.name)
                val ctorType = Type.TConstructor(internalize(name), emptyList())
                conds += Expr.InstanceOf(exp, ctorType)

                p.fields.forEachIndexed { i, pat ->
                    val idx = i + 1
                    val field =
                        mkCtorField(ctor.fullName, idx, exp, ctorType, fieldTypes[i], expectedFieldTypes.getOrNull(i))
                    val (cond, vs) = desugarPattern(pat, field)
                    conds += cond
                    vars += vs
                }

                // remove redundant checks
                val simplified = conds.filter { it != tru }
                when {
                    simplified.isEmpty() -> tru
                    simplified.size == 1 -> simplified[0]
                    else -> Expr.OperatorApp("&&", conds, boolType)
                } to vars
            }
        }

        tailrec fun varToLet(vdefs: List<VarDef>, exp: Expr): Expr {
            return if (vdefs.isEmpty()) exp
            else {
                val v = vdefs[0]
                varToLet(vdefs.drop(1), Expr.Let(v.name, v.exp, exp, exp.type))
            }
        }

        val exp = m.exp.convert(locals)
        val pats = m.cases.map { case ->
            val (cond, vars) = desugarPattern(case.pattern, exp)
            val introducedVariables = vars.map { it.name }
            val caseExp = case.exp.convert(locals + introducedVariables)
            val expr = if (vars.isEmpty()) caseExp else varToLet(vars, caseExp)
            cond to expr
        }
        return Expr.If(pats, makeThrow("Failed pattern match at ${m.span}."), type)
    }

    /**
     * Unrolls a native java call, as native
     * calls can't be partially applied.
     * @return the native call and it's parameters or null
     */
    private fun unrollForeignApp(app: CExpr.App): Pair<CExpr, List<CExpr>>? {
        var depth = 0
        val pars = mutableListOf<CExpr>()
        var exp: CExpr = app
        while (exp is CExpr.App) {
            depth++
            pars += exp.arg
            exp = exp.fn
        }
        pars.reverse()
        return when (exp) {
            is CExpr.NativeFieldGet, is CExpr.NativeFieldSet, is CExpr.NativeMethod, is CExpr.NativeConstructor -> exp to pars
            else -> null
        }
    }

    private fun peelArgs(type: TType): Pair<List<Type>, Type> {
        tailrec fun innerPeelArgs(args: List<TType>, t: TType): Pair<List<TType>, TType> = when (t) {
            is TArrow -> {
                if (t.ret is TArrow) innerPeelArgs(args + t.args, t.ret)
                else args + t.args to t.ret
            }
            else -> args to t
        }

        var rawType = type
        while (rawType is TForall) {
            rawType = rawType.type
        }
        val (pars, ret) = innerPeelArgs(emptyList(), rawType)
        return pars.map { it.convert() } to ret.convert()
    }

    private fun reportPatternMatch(res: PatternCompilationResult<Pattern>, expr: CExpr) {
        if (!res.exhaustive) {
            warnings += mkWarn(E.NON_EXHAUSTIVE_PATTERN, expr.span, ProblemContext.PATTERN_MATCHING)
        }
        if (res.redundantMatches.isNotEmpty()) {
            val unreacheable = res.redundantMatches.map { it.show() }
            warnings += mkWarn(E.redundantMatches(unreacheable), expr.span, ProblemContext.PATTERN_MATCHING)
        }
    }

    private fun mkWarn(msg: String, span: Span, ctx: ProblemContext): CompilerProblem =
        CompilerProblem(
            msg,
            ctx,
            span,
            ast.sourceName,
            ast.name,
            null,
            Severity.WARN
        )

    companion object {

        // TODO: use primitive types and implement autoboxing
        private fun getPrimitiveTypeName(tvar: TConst): String = when (tvar.name) {
            "prim.Byte" -> "java/lang/Byte"
            "prim.Short" -> "java/lang/Short"
            "prim.Int" -> "java/lang/Integer"
            "prim.Long" -> "java/lang/Long"
            "prim.Float" -> "java/lang/Float"
            "prim.Double" -> "java/lang/Double"
            "prim.Boolean" -> "java/lang/Boolean"
            "prim.Char" -> "java/lang/Character"
            "prim.String" -> "java/lang/String"
            "prim.Unit" -> "java/lang/Object"
            "prim.Object" -> "java/lang/Object"
            "prim.Array" -> "Array"
            "prim.ByteArray" -> "byte[]"
            "prim.ShortArray" -> "short[]"
            "prim.IntArray" -> "int[]"
            "prim.LongArray" -> "long[]"
            "prim.FloatArray" -> "float[]"
            "prim.DoubleArray" -> "double[]"
            "prim.CharArray" -> "char[]"
            "prim.BooleanArray" -> "boolean[]"
            "prim.Vector" -> "io/lacuna/bifurcan/IList"
            "prim.Set" -> "io/lacuna/bifurcan/ISet"
            else -> internalize(tvar.name)
        }

        private fun internalize(name: String) = name.replace('.', '/')

        const val RECORD_TYPE = "novah/collections/Record"
    }
}