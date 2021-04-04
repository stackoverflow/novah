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
import novah.frontend.typechecker.*
import novah.frontend.matching.PatternCompilationResult
import novah.frontend.matching.PatternMatchingCompiler
import org.objectweb.asm.Type
import java.util.function.Function
import io.lacuna.bifurcan.List as PList
import novah.ast.canonical.Binder as CBinder
import novah.ast.canonical.DataConstructor as CDataConstructor
import novah.ast.canonical.Decl.TypeDecl as CDataDecl
import novah.ast.canonical.Decl.ValDecl as CValDecl
import novah.ast.canonical.Expr as CExpr
import novah.ast.canonical.Module as CModule
import novah.frontend.error.Errors as E
import novah.frontend.typechecker.Type as TType

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

    private fun CValDecl.convert(): Decl.ValDecl =
        Decl.ValDecl(Names.convert(name), exp.convert(), visibility, span)

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
            is CExpr.Int32 -> when (type) {
                tByte -> Expr.ByteE(v.toByte(), typ, span)
                tInt16 -> Expr.Int16(v.toShort(), typ, span)
                tInt64 -> Expr.Int64(v.toLong(), typ, span)
                else -> Expr.Int32(v, typ, span)
            }
            is CExpr.Int64 -> Expr.Int64(v, typ, span)
            is CExpr.Float32 -> Expr.Float32(v, typ, span)
            is CExpr.Float64 -> Expr.Float64(v, typ, span)
            is CExpr.StringE -> Expr.StringE(v, typ, span)
            is CExpr.CharE -> Expr.CharE(v, typ, span)
            is CExpr.Bool -> Expr.Bool(v, typ, span)
            is CExpr.Var -> {
                val conName = Names.convert(name)
                val vvar = if (name in locals) Expr.LocalVar(conName, typ, span)
                else {
                    val cname = internalize(moduleName ?: ast.name) + "/Module"
                    Expr.Var(conName, cname, typ, span)
                }
                if (implicitContext != null) {
                    var v = vvar
                    val ri = resolvedImplicits()
                    ri.forEach { e ->
                        v = Expr.App(v, e.convert(locals), getReturnType(v.type), span)
                    }
                    v
                } else vvar
            }
            is CExpr.Constructor -> {
                val ctorName = fullname(moduleName ?: ast.name)
                val arity = PatternMatchingCompiler.getFromCache(ctorName)?.arity
                    ?: internalError("Could not find constructor $name")
                Expr.Constructor(internalize(ctorName), arity, typ, span)
            }
            is CExpr.ImplicitVar -> {
                val conName = Names.convert(name)
                if (name in locals) Expr.LocalVar(conName, typ, span)
                else {
                    val cname = internalize(moduleName ?: ast.name) + "/Module"
                    Expr.Var(conName, cname, typ, span)
                }
            }
            is CExpr.Lambda -> {
                haslambda = true
                val bind = Names.convert(binder.convert())
                Expr.Lambda(bind, body.convert(locals + bind), type = typ, span = span)
            }
            is CExpr.App -> {
                val pair = unrollForeignApp(this)
                if (pair != null) {
                    val (exp, pars) = pair
                    when (exp) {
                        // native getter will never be static here
                        is CExpr.NativeFieldGet -> Expr.NativeFieldGet(exp.field, pars[0].convert(locals), typ, span)
                        is CExpr.NativeFieldSet -> {
                            if (exp.isStatic)
                                Expr.NativeStaticFieldSet(exp.field, pars[0].convert(locals), typ, span)
                            else Expr.NativeFieldSet(
                                exp.field,
                                pars[0].convert(locals),
                                pars[1].convert(locals),
                                typ,
                                span
                            )
                        }
                        is CExpr.NativeMethod -> {
                            if (exp.isStatic)
                                Expr.NativeStaticMethod(exp.method, pars.map { it.convert(locals) }, typ, span)
                            else {
                                val nativePars = pars.map { it.convert(locals) }
                                Expr.NativeMethod(exp.method, nativePars[0], nativePars.drop(1), typ, span)
                            }
                        }
                        is CExpr.NativeConstructor -> Expr.NativeCtor(
                            exp.ctor,
                            pars.map { it.convert(locals) },
                            typ,
                            span
                        )
                        else -> internalError("Problem in `unrollForeignApp`, got non-native expression: $exp")
                    }
                } else {
                    if (implicitContext != null) {
                        val app = resolvedImplicits().fold(fn.convert(locals)) { acc, argg ->
                            Expr.App(acc, argg.convert(locals), getReturnType(acc.type), span)
                        }
                        Expr.App(app, arg.convert(locals), typ, span)
                    } else Expr.App(fn.convert(locals), arg.convert(locals), typ, span)
                }
            }
            is CExpr.If -> Expr.If(
                listOf(cond.convert(locals) to thenCase.convert(locals)),
                elseCase.convert(locals),
                typ,
                span
            )
            is CExpr.Let -> {
                val binder = Names.convert(letDef.binder.convert())
                Expr.Let(binder, letDef.expr.convert(locals), body.convert(locals + binder), typ, span)
            }
            is CExpr.Ann -> exp.convert(locals)
            is CExpr.Do -> Expr.Do(exps.map { it.convert(locals) }, typ, span)
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
                Expr.NativeStaticFieldGet(field, typ, span)
            }
            is CExpr.NativeFieldSet -> inferError(E.unkonwnArgsToNative(name, "setter"), span, ProblemContext.FOREIGN)
            is CExpr.NativeMethod -> inferError(E.unkonwnArgsToNative(name, "method"), span, ProblemContext.FOREIGN)
            is CExpr.NativeConstructor -> inferError(
                E.unkonwnArgsToNative(name, "constructor"),
                span,
                ProblemContext.FOREIGN
            )
            is CExpr.Unit -> Expr.Unit(typ, span)
            is CExpr.RecordEmpty -> Expr.RecordEmpty(typ, span)
            is CExpr.RecordExtend -> Expr.RecordExtend(labels.mapList { it.convert(locals) },
                exp.convert(locals), typ, span)
            is CExpr.RecordSelect -> Expr.RecordSelect(exp.convert(locals), label, typ, span)
            is CExpr.RecordRestrict -> Expr.RecordRestrict(exp.convert(locals), label, typ, span)
            is CExpr.VectorLiteral -> Expr.VectorLiteral(exps.map { it.convert(locals) }, typ, span)
            is CExpr.SetLiteral -> Expr.SetLiteral(exps.map { it.convert(locals) }, typ, span)
            is CExpr.Throw -> Expr.Throw(exp.convert(locals), span)
            is CExpr.TryCatch -> {
                val catches = cases.map { 
                    val pat = it.patterns[0] as Pattern.TypeTest
                    val loc = if (pat.alias != null) locals + pat.alias else locals
                    Catch(pat.type.convert(), pat.alias, it.exp.convert(loc), pat.span)
                }
                Expr.TryCatch(tryExp.convert(locals), catches, finallyExp?.convert(locals), typ, span)
            }
            is CExpr.While -> Expr.While(cond.convert(locals), exps.map { it.convert(locals) }, typ, span)
        }
    }

    private fun CBinder.convert(): String = name

    private fun TType.convert(): Clazz = when (this) {
        is TConst -> {
            if (show(false)[0].isLowerCase()) Clazz(OBJECT_TYPE)
            else Clazz(getPrimitiveTypeName(this))
        }
        is TApp -> Clazz(type.convert().type, types.map { it.convert() })
        // the only functions that can have more than 1 argument are native ones
        // but those don't need a type as we get the type from the reflected method/field/constructor
        is TArrow -> Clazz(FUNCTION_TYPE, listOf(args[0].convert(), ret.convert()))
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.convert()
                is TypeVar.Generic -> Clazz(OBJECT_TYPE)
                is TypeVar.Unbound -> {
                    //internalError("got unbound variable after typechecking: ${show(true)} at $span")
                    Clazz(OBJECT_TYPE)
                }
            }
        }
        // records always have the same type
        is TRecord -> row.convert()
        is TRowEmpty -> Clazz(RECORD_TYPE)
        is TRowExtend -> {
            val (rows, _) = collectRows()
            Clazz(RECORD_TYPE, labels = rows.mapList { it.convert() })
        }
        is TImplicit -> type.convert()
    }

    private val stringType = tString.convert()
    private val boolType = tBoolean.convert()
    private val longType = tInt64.convert()

    private fun makeThrow(msg: String): Expr {
        return Expr.Throw(
            Expr.NativeCtor(
                rteCtor,
                listOf(Expr.StringE(msg, stringType, Span.empty())),
                Clazz(Type.getType(java.lang.RuntimeException::class.java)),
                Span.empty()
            ), Span.empty()
        )
    }

    private class VarDef(val name: String, val exp: Expr)
    private data class PatternResult(val exp: Expr, val vars: List<VarDef> = emptyList())
    private data class PatternExp(val exp: Expr, val varName: String?, val original: Expr)

    private fun desugarMatch(m: CExpr.Match, type: Clazz, locals: List<String>): Expr {
        val tru = Expr.Bool(true, boolType, Span.empty())

        fun mkCtorField(
            name: String,
            fieldIndex: Int,
            ctorExpr: Expr,
            ctorType: Clazz,
            fieldType: Clazz,
            expectedFieldType: Clazz?
        ): Expr {
            val castedExpr = Expr.Cast(ctorExpr, ctorType, ctorExpr.span)
            val field = Expr.ConstructorAccess(name, fieldIndex, castedExpr, fieldType, ctorExpr.span)
            return if (expectedFieldType == null || fieldType == expectedFieldType
                || expectedFieldType.type == OBJECT_TYPE) field
            else Expr.Cast(field, expectedFieldType, ctorExpr.span)
        }

        fun simplifyConds(conds: List<Expr>): Expr {
            val simplified = conds.filter { it != tru }
            return when {
                simplified.isEmpty() -> tru
                simplified.size == 1 -> simplified[0]
                else -> Expr.OperatorApp("&&", simplified, boolType, simplified[0].span)
            }
        }

        fun mkVectorSizeCheck(exp: Expr, size: Long): Expr {
            val sizeExp = Expr.NativeMethod(vecSize, exp, emptyList(), longType, exp.span)
            val longe = Expr.Int64(size, longType, exp.span)
            return Expr.NativeStaticMethod(eqLong, listOf(sizeExp, longe), boolType, exp.span)
        }

        fun mkVectorAccessor(exp: Expr, index: Long, type: Clazz): Expr =
            Expr.NativeMethod(vecAccess, exp, listOf(Expr.Int64(index, longType, exp.span)), type, exp.span)

        fun desugarPattern(p: Pattern, exp: Expr): PatternResult = when (p) {
            is Pattern.Wildcard -> PatternResult(tru)
            is Pattern.Var -> PatternResult(tru, listOf(VarDef(Names.convert(p.name), exp)))
            is Pattern.Unit -> PatternResult(tru)
            is Pattern.LiteralP -> {
                val method = when (p.lit) {
                    is LiteralPattern.Int32Literal -> eqInt
                    is LiteralPattern.Int64Literal -> eqLong
                    is LiteralPattern.Float32Literal -> eqFloat
                    is LiteralPattern.Float64Literal -> eqDouble
                    is LiteralPattern.CharLiteral -> eqChar
                    is LiteralPattern.BoolLiteral -> eqBoolean
                    is LiteralPattern.StringLiteral -> eqString
                }
                PatternResult(Expr.NativeStaticMethod(method, listOf(exp, p.lit.e.convert(locals)), boolType, exp.span))
            }
            is Pattern.Ctor -> {
                val conds = mutableListOf<Expr>()
                val vars = mutableListOf<VarDef>()

                val (fieldTypes, _) = peelArgs(p.ctor.type!!)
                val expectedFieldTypes = exp.type.pars

                val ctor = p.ctor.convert(locals) as Expr.Constructor
                val name = p.ctor.fullname(ast.name)
                val ctorType = Clazz(Type.getObjectType(internalize(name)))
                conds += Expr.InstanceOf(exp, ctorType, p.span)

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

                val rows = exp.type.labels ?: internalError("Got wrong type for record: ${exp.type}")

                p.labels.forEachKeyList { label, pat ->
                    val field = Expr.RecordSelect(exp, label, rows.get(label, null).first(), exp.span)
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
                val fieltTy = exp.type.pars.firstOrNull() ?: internalError("Got wrong type for vector: ${exp.type}")
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

                conds += Expr.NativeStaticMethod(vecNotEmpty, listOf(exp), boolType, p.span)
                val fieltTy = exp.type.pars.firstOrNull() ?: internalError("Got wrong type for vector: ${exp.type}")
                val headExp = mkVectorAccessor(exp, 0, fieltTy)
                val sizeExp = Expr.NativeMethod(vecSize, exp, emptyList(), longType, p.span)
                val tailExp = Expr.NativeMethod(vecSlice, exp,
                    listOf(Expr.Int64(1, longType, p.span), sizeExp), type, p.span)

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
                val cond = Expr.InstanceOf(exp, castType, p.span)
                val vs = if (p.alias != null) listOf(VarDef(p.alias, Expr.Cast(exp, castType, p.span))) else emptyList()
                PatternResult(cond, vs)
            }
        }

        tailrec fun varToLet(vdefs: List<VarDef>, exp: Expr): Expr {
            return if (vdefs.isEmpty()) exp
            else {
                val v = vdefs[0]
                varToLet(vdefs.drop(1), Expr.Let(v.name, v.exp, exp, exp.type, exp.span))
            }
        }

        val exps = m.exps.map {
            val exp = it.convert(locals)
            if (needVar(exp)) {
                val name = "case$${genVar++}"
                PatternExp(Expr.LocalVar(name, exp.type, exp.span), name, exp)
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
                else Expr.OperatorApp("&&", listOf(cond, guarded), boolType, case.guard.span)
                // not sure the variables are visible in `caseExpr` better test
                guardCond to caseExp
            } else {
                val expr = varToLet(vars, caseExp)
                cond to expr
            }
        }
        val ifExp = if (pats.size == 1 && pats[0].first == tru) {
            pats[0].second
        } else Expr.If(pats, makeThrow("Failed pattern match at ${m.span}."), type, pats[0].second.span)
        val vars = exps.filter { it.varName != null }.map { it.varName!! to it.original }
        return nestLets(vars, ifExp, type)
    }

    private fun needVar(exp: Expr): Boolean = when (exp) {
        is Expr.Var, is Expr.LocalVar, is Expr.ByteE, is Expr.Int16,
        is Expr.Int32, is Expr.Int64, is Expr.Float32, is Expr.Float64,
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

    private fun peelArgs(type: TType): Pair<List<Clazz>, Clazz> {
        tailrec fun innerPeelArgs(args: List<TType>, t: TType): Pair<List<TType>, TType> = when (t) {
            is TArrow -> {
                if (t.ret is TArrow) innerPeelArgs(args + t.args, t.ret)
                else args + t.args to t.ret
            }
            else -> args to t
        }

        val (pars, ret) = innerPeelArgs(emptyList(), type)
        return pars.map { it.convert() } to ret.convert()
    }
    
    private fun getReturnType(type: Clazz): Clazz =
        if (type.pars.size > 1) type.pars[1] else type

    private fun reportPatternMatch(res: PatternCompilationResult<Pattern>, expr: CExpr.Match) {
        if (!res.exhaustive) {
            val span = if (expr.cases.size == 1) expr.cases[0].patternSpan() else expr.span
            warnings += mkWarn(E.NON_EXHAUSTIVE_PATTERN, span)
        }
        if (res.redundantMatches.isNotEmpty()) {
            val unreacheable = res.redundantMatches.map { it.joinToString { p -> p.show() } }
            warnings += mkWarn(E.redundantMatches(unreacheable), expr.span)
        }
    }

    private fun mkWarn(msg: String, span: Span): CompilerProblem =
        CompilerProblem(
            msg,
            ProblemContext.PATTERN_MATCHING,
            span,
            ast.sourceName,
            ast.name,
            null,
            Severity.WARN
        )

    companion object {

        private fun getPrimitiveTypeName(tvar: TConst): Type = when (tvar.name) {
            primByte -> Type.getType(Byte::class.javaObjectType)
            primInt16 -> Type.getType(Short::class.javaObjectType)
            primInt32 -> Type.getType(Int::class.javaObjectType)
            primInt64 -> LONG_TYPE
            primFloat32 -> Type.getType(Float::class.javaObjectType)
            primFloat64 -> Type.getType(Double::class.javaObjectType)
            primBoolean -> Type.getType(Boolean::class.javaObjectType)
            primChar -> Type.getType(Char::class.javaObjectType)
            primString -> Type.getType(String::class.java)
            primUnit -> OBJECT_TYPE
            primObject -> OBJECT_TYPE
            primArray -> ARRAY_TYPE
            primByteArray -> Type.getType(ByteArray::class.java)
            primInt16Array -> Type.getType(ShortArray::class.java)
            primInt32Array -> Type.getType(IntArray::class.java)
            primInt64Array -> Type.getType(LongArray::class.java)
            primFloat32Array -> Type.getType(FloatArray::class.java)
            primFloat64Array -> Type.getType(DoubleArray::class.java)
            primCharArray -> Type.getType(CharArray::class.java)
            primBooleanArray -> Type.getType(BooleanArray::class.java)
            primVector -> Type.getType(io.lacuna.bifurcan.List::class.java)
            primSet -> Type.getType(io.lacuna.bifurcan.Set::class.java)
            else -> Type.getObjectType(internalize(tvar.name))
        }

        private fun internalize(name: String) = name.replace('.', '/')

        private val OBJECT_TYPE = Type.getType(Object::class.java)
        private val RECORD_TYPE = Type.getType(novah.collections.Record::class.java)
        private val FUNCTION_TYPE = Type.getType(Function::class.java)
        val ARRAY_TYPE = Type.getType(Array::class.java)
        val LONG_TYPE = Type.getType(Long::class.javaObjectType)

        private val rteCtor = RuntimeException::class.java.constructors.find {
            it.parameterCount == 1 && it.parameterTypes[0].canonicalName == "java.lang.String"
        }!!

        val vecSize = PList::class.java.methods.find { it.name == "size" }!!
        private val vecAccess = PList::class.java.methods.find { it.name == "nth" }!!
        private val vecSlice = PList::class.java.methods.find { it.name == "slice" }!!
        private val vecNotEmpty = Core::class.java.methods.find { it.name == "vectorNotEmpty" }!!
        val eqInt = Core::class.java.methods.find {
            it.name == "equivalent" && it.parameterTypes[0] == Int::class.java
        }!!
        val eqLong = Core::class.java.methods.find {
            it.name == "equivalent" && it.parameterTypes[0] == Long::class.java
        }!!
        val eqFloat = Core::class.java.methods.find {
            it.name == "equivalent" && it.parameterTypes[0] == Float::class.java
        }!!
        val eqDouble = Core::class.java.methods.find {
            it.name == "equivalent" && it.parameterTypes[0] == Double::class.java
        }!!
        val eqChar = Core::class.java.methods.find {
            it.name == "equivalent" && it.parameterTypes[0] == Char::class.java
        }!!
        val eqBoolean = Core::class.java.methods.find {
            it.name == "equivalent" && it.parameterTypes[0] == Boolean::class.java
        }!!
        val eqString = String::class.java.methods.find { it.name == "equals" }!!
    }
}