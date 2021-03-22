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

import novah.Core
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
import io.lacuna.bifurcan.List as PList
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

    private var genVar = 0

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
                val bind = binder.convert()
                Expr.Lambda(bind, body.convert(locals + bind), type = typ)
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
                // guarded matches are ignored
                val matches = match.filter { !it.isGuarded }
                val compRes = PatternMatchingCompiler<Pattern>().compile(matches)
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
        is TRecord -> row.convert()
        is TRowEmpty -> Type.TVar(RECORD_TYPE)
        is TRowExtend -> {
            val (rows, _) = collectRows()
            Type.TVar(RECORD_TYPE, labels = rows.mapList { it.convert() })
        }
        is TImplicit -> type.convert()
    }

    private val rteCtor = RuntimeException::class.java.constructors.find {
        it.parameterCount == 1 && it.parameterTypes[0].canonicalName == "java.lang.String"
    }!!

    private val vectorSize = PList::class.java.methods.find { it.name == "size" }!!
    private val vectorAccess = PList::class.java.methods.find { it.name == "nth" }!!
    private val vectorSlice = PList::class.java.methods.find { it.name == "slice" }!!
    private val equivalentInt = Core::class.java.methods.find {
        it.name == "equivalent" && it.parameterTypes[0] == Int::class.java
    }!!
    private val equivalentLong = Core::class.java.methods.find {
        it.name == "equivalent" && it.parameterTypes[0] == Long::class.java
    }!!
    private val equivalentFloat = Core::class.java.methods.find {
        it.name == "equivalent" && it.parameterTypes[0] == Float::class.java
    }!!
    private val equivalentDouble = Core::class.java.methods.find {
        it.name == "equivalent" && it.parameterTypes[0] == Double::class.java
    }!!
    private val equivalentChar = Core::class.java.methods.find {
        it.name == "equivalent" && it.parameterTypes[0] == Char::class.java
    }!!
    private val equivalentBoolean = Core::class.java.methods.find {
        it.name == "equivalent" && it.parameterTypes[0] == Boolean::class.java
    }!!
    private val equivalent = Core::class.java.methods.find {
        it.name == "equivalent" && it.parameterTypes[0] == Object::class.java
    }!!

    private val stringType = tString.convert()
    private val boolType = tBoolean.convert()
    private val longType = tLong.convert()
    private val objectType = tObject.convert()

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
    private data class PatternResult(val exp: Expr, val vars: List<VarDef> = emptyList())
    private data class PatternExp(val exp: Expr, val varName: String?, val original: Expr)

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

        fun simplifyConds(conds: List<Expr>): Expr {
            val simplified = conds.filter { it != tru }
            return when {
                simplified.isEmpty() -> tru
                simplified.size == 1 -> simplified[0]
                else -> Expr.OperatorApp("&&", simplified, boolType)
            }
        }

        fun mkVectorSizeCheck(exp: Expr, size: Long): Expr {
            val sizeExp = Expr.NativeMethod(vectorSize, exp, emptyList(), longType)
            val longe = Expr.LongE(size, longType)
            return Expr.NativeStaticMethod(equivalentLong, listOf(sizeExp, longe), boolType)
        }

        fun mkVectorAccessor(exp: Expr, index: Long, type: Type): Expr =
            Expr.NativeMethod(vectorAccess, exp, listOf(Expr.LongE(index, longType)), type)

        fun desugarPattern(p: Pattern, exp: Expr): PatternResult = when (p) {
            is Pattern.Wildcard -> PatternResult(tru)
            is Pattern.Var -> PatternResult(tru, listOf(VarDef(p.name, exp)))
            is Pattern.Unit -> {
                val cond = Expr.NativeStaticMethod(equivalent, listOf(exp, Expr.Unit(objectType)), boolType)
                PatternResult(cond)
            }
            is Pattern.LiteralP -> {
                val method = when (p.lit) {
                    is LiteralPattern.IntLiteral -> equivalentInt
                    is LiteralPattern.LongLiteral -> equivalentLong
                    is LiteralPattern.FloatLiteral -> equivalentFloat
                    is LiteralPattern.DoubleLiteral -> equivalentDouble
                    is LiteralPattern.CharLiteral -> equivalentChar
                    is LiteralPattern.BoolLiteral -> equivalentBoolean
                    is LiteralPattern.StringLiteral -> equivalent
                }
                PatternResult(Expr.NativeStaticMethod(method, listOf(exp, p.lit.e.convert(locals)), boolType))
            }
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

                PatternResult(simplifyConds(conds), vars)
            }
            is Pattern.Record -> {
                val conds = mutableListOf<Expr>()
                val vars = mutableListOf<VarDef>()

                val rows = (exp.type as? Type.TVar)?.labels ?: internalError("Got wrong type for record: ${exp.type}")

                p.labels.forEachKeyList { label, pat ->
                    val field = Expr.RecordSelect(exp, label, rows.get(label, null).first())
                    val (cond, vs) = desugarPattern(pat, field)
                    conds += cond
                    vars += vs
                }

                PatternResult(simplifyConds(conds), vars)
            }
            is Pattern.Vector -> {
                val conds = mutableListOf<Expr>()
                val vars = mutableListOf<VarDef>()

                conds += mkVectorSizeCheck(exp, p.elems.size.toLong())
                val fieltTy = (exp.type as? Type.TConstructor)?.types?.first()
                    ?: internalError("Got wrong type for vector: ${exp.type}")
                p.elems.forEachIndexed { i, pat ->
                    val field = mkVectorAccessor(exp, i.toLong(), fieltTy)
                    val (cond, vs) = desugarPattern(pat, field)
                    conds += cond
                    vars += vs
                }

                PatternResult(simplifyConds(conds), vars)
            }
            is Pattern.VectorHT -> {
                val conds = mutableListOf<Expr>()
                val vars = mutableListOf<VarDef>()

                val fieltTy = (exp.type as? Type.TConstructor)?.types?.first()
                    ?: internalError("Got wrong type for vector: ${exp.type}")
                val headExp = mkVectorAccessor(exp, 0, fieltTy)
                val sizeExp = Expr.NativeMethod(vectorSize, exp, emptyList(), longType)
                val tailExp = Expr.NativeMethod(vectorSlice, exp, listOf(Expr.LongE(1, longType), sizeExp), type)

                val (hCond, hVs) = desugarPattern(p.head, headExp)
                val (tCond, tVs) = desugarPattern(p.tail, tailExp)
                conds += hCond
                conds += tCond
                vars += hVs
                vars += tVs

                PatternResult(simplifyConds(conds), vars)
            }
            is Pattern.Named -> {
                val (cond, vars) = desugarPattern(p.pat, exp)
                PatternResult(cond, vars + VarDef(p.name, exp))
            }
            is Pattern.TypeTest -> {
                val castType = p.type.convert()
                val cond = Expr.InstanceOf(exp, castType)
                val vs = if (p.alias != null) listOf(VarDef(p.alias, Expr.Cast(exp, castType))) else emptyList()
                PatternResult(cond, vs)
            }
        }

        tailrec fun varToLet(vdefs: List<VarDef>, exp: Expr): Expr {
            return if (vdefs.isEmpty()) exp
            else {
                val v = vdefs[0]
                varToLet(vdefs.drop(1), Expr.Let(v.name, v.exp, exp, exp.type))
            }
        }

        val exps = m.exps.map {
            val exp = it.convert(locals)
            if (needVar(exp)) {
                val name = "case$${genVar++}"
                PatternExp(Expr.LocalVar(name, exp.type), name, exp)
            } else PatternExp(exp, null, exp)
        }
        val pats = m.cases.map { case ->
            val (cond, vars) = if (case.patterns.size == 1) {
                desugarPattern(case.patterns[0], exps[0].exp)
            } else {
                val results = case.patterns.mapIndexed { i, pat -> desugarPattern(pat, exps[i].exp) }
                val cond = simplifyConds(results.map { it.exp })
                PatternResult(cond, results.flatMap { it.vars })
            }
            val introducedVariables = vars.map { it.name }
            val caseExp = case.exp.convert(locals + introducedVariables)
            if (case.guard != null) {
                val guarded = varToLet(vars, case.guard.convert(locals + introducedVariables))
                val guardCond = if (cond == tru) guarded
                else Expr.OperatorApp("&&", listOf(cond, guarded), boolType)
                // not sure the variables are visible in `caseExpr` better test
                guardCond to caseExp
            } else {
                val expr = varToLet(vars, caseExp)
                cond to expr
            }
        }
        val ifExp = Expr.If(pats, makeThrow("Failed pattern match at ${m.span}."), type)
        val vars = exps.filter { it.varName != null }.map { it.varName!! to it.original }
        return nestLets(vars, ifExp, type)
    }

    private fun needVar(exp: Expr): Boolean = when (exp) {
        is Expr.Var, is Expr.LocalVar, is Expr.ByteE, is Expr.ShortE,
        is Expr.IntE, is Expr.LongE, is Expr.FloatE, is Expr.DoubleE,
        is Expr.StringE, is Expr.CharE, is Expr.Bool, is Expr.Constructor -> false
        else -> true
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
            val unreacheable = res.redundantMatches.map { it.joinToString { p -> p.show() } }
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
            "prim.Vector" -> "io/lacuna/bifurcan/List"
            "prim.Set" -> "io/lacuna/bifurcan/Set"
            else -> internalize(tvar.name)
        }

        private fun internalize(name: String) = name.replace('.', '/')

        const val RECORD_TYPE = "novah/collections/Record"
    }
}