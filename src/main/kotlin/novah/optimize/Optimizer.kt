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
package novah.optimize

import io.lacuna.bifurcan.Map
import novah.Core
import novah.RecFunction
import novah.Util.internalError
import novah.ast.Desugar
import novah.ast.canonical.*
import novah.ast.optimized.*
import novah.ast.optimized.Decl
import novah.ast.source.Visibility
import novah.backend.TypeUtil.wrapper
import novah.collections.Record
import novah.data.allList
import novah.data.forEachKeyList
import novah.data.mapList
import novah.frontend.Span
import novah.frontend.error.Action
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Severity
import novah.frontend.matching.Ctor
import novah.frontend.matching.PatternCompilationResult
import novah.frontend.matching.PatternMatchingCompiler
import novah.frontend.typechecker.*
import novah.function.Function
import novah.main.Environment
import novah.range.Range
import org.objectweb.asm.Type
import java.lang.reflect.Constructor
import kotlin.collections.List
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.plus
import kotlin.collections.plusAssign
import io.lacuna.bifurcan.List as PList
import novah.ast.canonical.Binder as CBinder
import novah.ast.canonical.DataConstructor as CDataConstructor
import novah.ast.canonical.Decl.TypeDecl as CTypeDecl
import novah.ast.canonical.Decl.ValDecl as CValDecl
import novah.ast.canonical.Expr as CExpr
import novah.ast.canonical.Module as CModule
import novah.frontend.error.Errors as E
import novah.frontend.typechecker.Type as TType

/**
 * Converts the canonical AST to the
 * optimized version, ready for codegen
 */
class Optimizer(private val ast: CModule, private val ctorCache: MutableMap<String, Ctor>) {

    private var haslambda = false
    private val errors = mutableListOf<CompilerProblem>()
    private val unusedImports: MutableMap<String, Span> = ast.unusedImports.toMutableMap()
    private val patternCompiler = PatternMatchingCompiler<Pattern>(ctorCache)

    private var genVar = 0

    fun errors(): List<CompilerProblem> = errors

    fun convert(): Module {
        patternCompiler.addConsToCache(ast)
        val optimized = ast.convert()
        if (!ast.metadata.isTrue(Metadata.NO_WARN)) reportUnusedImports()
        return optimized
    }

    private fun CModule.convert(): Module {
        val metaExpr = mutableListOf<Pair<String, Metadata>>()
        val ds = mutableListOf<Decl>()
        for (d in decls) {
            meta = d.metadata
            if (d.metadata != null) metaExpr += d.rawName() to d.metadata!!
            if (d is CTypeDecl) ds += d.convert()
            if (d is CValDecl && !d.typeError) ds += d.convert()
        }
        val allDecls = if (metaExpr.isEmpty()) ds
        else ds + makeMetaExpr(metaExpr)
        return Module(internalize(name.value), sourceName, haslambda, allDecls)
    }

    private var meta: Metadata? = null

    private fun CValDecl.convert(): Decl.ValDecl {
        val newExp = if (recursive && exp.isTailcall(name.value)) {
            // optimize tail calls
            val fullname = internalize(ast.name.value + ".\$Module") + ".${name.value}"
            tcoToLoop(fullname, exp.convert(), isLet = false)
        } else exp.convert()
        return Decl.ValDecl(Names.convert(name.value), newExp, visibility, span)
    }

    private fun CTypeDecl.convert(): Decl.TypeDecl =
        Decl.TypeDecl(name.value, tyVars, dataCtors.map { it.convert() }, visibility, span)

    private fun CDataConstructor.convert(): DataConstructor =
        DataConstructor(name.value, args.map { it.convert() }, visibility)

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
                val vvar = if (moduleName == null && conName in locals) {
                    Expr.LocalVar(conName, typ, span)
                } else {
                    val cname = internalize(moduleName ?: ast.name.value) + "/\$Module"
                    Expr.Var(conName, cname, typ, span)
                }
                if (implicitContext != null) {
                    var v = vvar
                    resolvedImplicits().forEach { instance ->
                        checkUseImport(instance)
                        v = Expr.App(v, instance.convert(locals), getReturnType(v.type), span)
                    }
                    v
                } else vvar
            }
            is CExpr.Constructor -> {
                val ctorName = fullname(moduleName ?: ast.name.value)
                val arity = ctorCache[ctorName]?.arity
                    ?: internalError("Could not find constructor $name")
                when (ctorName) {
                    "prim.None" -> Expr.Null(typ, span)
                    "prim.Some" -> Expr.NativeStaticFieldGet(some, typ, span)
                    else -> Expr.Constructor(internalize(ctorName), arity, typ, span)
                }
            }
            is CExpr.ImplicitVar -> {
                val conName = Names.convert(name)
                if (conName in locals) Expr.LocalVar(conName, typ, span)
                else {
                    val cname = internalize(moduleName ?: ast.name.value) + "/\$Module"
                    Expr.Var(conName, cname, typ, span)
                }
            }
            is CExpr.Lambda -> {
                haslambda = true
                val bind = Names.convert(binder.convert())
                Expr.Lambda(bind, body.convert(locals + bind), type = typ, span = span)
            }
            is CExpr.App -> {
                if (implicitContext != null) {
                    val app = resolvedImplicits().fold(fn.convert(locals)) { acc, instance ->
                        checkUseImport(instance)
                        Expr.App(acc, instance.convert(locals), getReturnType(acc.type), span)
                    }
                    Expr.App(app, arg.convert(locals), typ, span)
                } else Expr.App(fn.convert(locals), arg.convert(locals), typ, span)
            }
            is CExpr.If -> Expr.If(
                listOf(cond.convert(locals) to thenCase.convert(locals)),
                elseCase.convert(locals),
                typ,
                span
            )
            is CExpr.Let -> {
                val binder = Names.convert(letDef.binder.convert())
                val lcs = locals + binder
                if (letDef.recursive && letDef.expr.isTailcall(letDef.binder.name)) {
                    // optimize tail calls
                    val tcoed = tcoToLoop(binder, letDef.expr.convert(lcs), isLet = true)
                    Expr.Let(binder, tcoed, body.convert(lcs), typ, span)
                } else {
                    val let = Expr.Let(binder, letDef.expr.convert(lcs), body.convert(lcs), typ, span)
                    if (letDef.recursive) makeRecursiveLet(let) else let
                }
            }
            is CExpr.Ann -> exp.convert(locals)
            is CExpr.Do -> Expr.Do(exps.map { it.convert(locals) }, typ, span)
            is CExpr.Match -> {
                // don't report warnings on pattern matches when a #[noWarn] attribute is present
                // so we can do things like `let [x :: xs] = 1 .. 10`
                if (!meta.isTrue(Metadata.NO_WARN)) {
                    val match = cases.map { patternCompiler.convert(it, ast.name.value) }
                    // guarded matches are ignored
                    val matches = match.filter { !it.isGuarded }
                    val compRes = patternCompiler.compile(matches)
                    reportPatternMatch(compRes, this)
                }
                desugarMatch(this, typ, locals)
            }
            is CExpr.Unit -> Expr.Unit(typ, span)
            is CExpr.RecordEmpty -> Expr.RecordEmpty(typ, span)
            is CExpr.RecordExtend -> Expr.RecordExtend(
                labels.mapList { it.convert(locals) },
                exp.convert(locals), typ, span
            )
            is CExpr.RecordSelect -> Expr.RecordSelect(exp.convert(locals), label.value, typ, span)
            is CExpr.RecordRestrict -> Expr.RecordRestrict(exp.convert(locals), label, typ, span)
            is CExpr.RecordUpdate -> {
                Expr.RecordUpdate(exp.convert(locals), label.value, value.convert(locals), isSet, typ, span)
            }
            is CExpr.RecordMerge -> Expr.RecordMerge(exp1.convert(locals), exp2.convert(locals), typ, span)
            is CExpr.ListLiteral -> Expr.ListLiteral(exps.map { it.convert(locals) }, typ, span)
            is CExpr.SetLiteral -> Expr.SetLiteral(exps.map { it.convert(locals) }, typ, span)
            is CExpr.Index -> {
                Expr.NativeStaticMethod(method!!, listOf(index.convert(locals), exp.convert(locals)), typ, span)
            }
            is CExpr.Throw -> Expr.Throw(exp.convert(locals), span)
            is CExpr.TryCatch -> {
                val catches = cases.map {
                    val pat = it.patterns[0] as Pattern.TypeTest
                    val alias = if (pat.alias != null) Names.convert(pat.alias) else null
                    val loc = if (alias != null) locals + alias else locals
                    Catch(pat.test.convert(), alias, it.exp.convert(loc), pat.span)
                }
                Expr.TryCatch(tryExp.convert(locals), catches, finallyExp?.convert(locals), typ, span)
            }
            is CExpr.While -> Expr.While(cond.convert(locals), exps.map { it.convert(locals) }, typ, span)
            is CExpr.Null -> Expr.Null(typ, span)
            is CExpr.TypeCast -> Expr.Cast(exp.convert(locals), cast.convert(), span)
            is CExpr.ClassConstant -> Expr.ClassConstant(internalize(clazz.value), typ, span)
            is CExpr.ForeignStaticField -> {
                val f = field ?: internalError("got null for java field")
                Expr.NativeStaticFieldGet(f, typ, span)
            }
            is CExpr.ForeignField -> {
                val f = field ?: internalError("got null for java field")
                Expr.NativeFieldGet(f, exp.convert(locals), typ, span)
            }
            is CExpr.ForeignStaticMethod -> when {
                method != null -> Expr.NativeStaticMethod(method!!, args.map { it.convert(locals) }, typ, span)
                ctor != null -> Expr.NativeCtor(ctor!!, args.map { it.convert(locals) }, typ, span)
                else -> internalError("got null for java method")
            }
            is CExpr.ForeignMethod -> {
                val m = method ?: internalError("got null for java method")
                Expr.NativeMethod(m, exp.convert(locals), args.map { it.convert(locals) }, typ, span)
            }
            is CExpr.ForeignStaticFieldSetter -> {
                val f = field.field ?: internalError("got null for java field")
                Expr.NativeStaticFieldSet(f, value.convert(locals), typ, span)
            }
            is CExpr.ForeignFieldSetter -> {
                val f = field.field ?: internalError("got null for java field")
                Expr.NativeFieldSet(f, field.exp.convert(locals), value.convert(locals), typ, span)
            }
        }
    }

    private fun CBinder.convert(): String = name

    private fun TType.convert(): Clazz = when (this) {
        is TConst -> {
            if (show(false)[0].isLowerCase()) Clazz(OBJECT_TYPE)
            else Clazz(getPrimitiveTypeName(this))
        }
        is TApp -> {
            if (type is TConst && type.name == primArray) {
                val of = types[0].convert()
                Clazz(getPrimitiveArrayTypeName(of.type))
            } else if (type is TConst && type.name == primOption) {
                val of = types[0].convert()
                Clazz(getPrimitiveOptionType(of.type))
            } else {
                val conv = type.convert()
                if (conv.type.className == primNullable) {
                    val ty = types[0].convert()
                    ty.copy(type = ty.type.wrapper())
                } else Clazz(conv.type, types.map { it.convert() })
            }
        }
        // the only functions that can have more than 1 argument are native ones
        // but those don't need a type as we get the type from the reflected method/field/constructor
        is TArrow -> Clazz(FUNCTION_TYPE, listOf(args[0].convert(), ret.convert()))
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.convert()
                is TypeVar.Generic -> Clazz(OBJECT_TYPE)
                is TypeVar.Unbound -> Clazz(OBJECT_TYPE)
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

    /**
     * Uses a fixpoint operator to make recursive lets
     * work at runtime.
     */
    private fun makeRecursiveLet(let: Expr.Let): Expr {
        val bindTy = let.bindExpr.type
        // should not happen
        if (!bindTy.isFunction()) return let

        val binder = let.binder + "\$rec"
        val recFunTy = Clazz(Type.getObjectType("novah/RecFunction"), bindTy.pars)
        val recCtor = Expr.NativeCtor(newRecFun, emptyList(), recFunTy, let.bindExpr.span)
        val recVar = Expr.LocalVar(binder, recFunTy, let.bindExpr.span)
        val getRec = Expr.NativeFieldGet(recFunField, recVar, bindTy, let.bindExpr.span)

        val newBinderExpr = let.bindExpr.everywhere { e ->
            if (e is Expr.LocalVar && e.name == let.binder) getRec else e
        }

        val innerLet = Expr.Let(let.binder, getRec, let.body, let.type, let.span)
        val fieldSet = Expr.NativeFieldSet(recFunField, recVar, newBinderExpr, newBinderExpr.type, newBinderExpr.span)
        val recBody = Expr.Do(listOf(fieldSet, innerLet), let.type, let.span)
        return Expr.Let(binder, recCtor, recBody, let.type, let.span)
    }

    /**
     * Transforms this tail recursion into a loop.
     */
    private fun tcoToLoop(name: String, exp: Expr, isLet: Boolean): Expr {
        val tru = Expr.Bool(true, boolType, exp.span)
        fun isVar(e: Expr) = e is Expr.Var && e.fullname() == name
        fun isVarLet(e: Expr) = e is Expr.LocalVar && e.name == name
        val varCheck = if (isLet) ::isVarLet else ::isVar

        fun updateLambdaBody(e: Expr, pars: List<Pair<String, Clazz>> = emptyList()): Expr = when (e) {
            is Expr.Lambda -> {
                val par = e.binder to (e.type.pars.getOrNull(0) ?: e.type)
                e.copy(body = updateLambdaBody(e.body, pars + par))
            }
            else -> {
                val parNames = pars.map { it.first }
                val newBinds = pars.map { (par, clazz) -> "tco$$par" to Expr.LocalVar(par, clazz, e.span) }
                val parMap = parNames.associateWith { "tco$$it" }
                val replExp = e.everywhere {
                    if (it is Expr.LocalVar && parMap.containsKey(it.name))
                        it.copy(name = parMap[it.name]!!)
                    else it
                }

                val tcoed = replExp.toLoop(name, newBinds.map { it.first }, varCheck)
                val whil = Expr.While(tru, listOf(tcoed), e.type, e.span)
                nestLets(newBinds, whil, e.type)
            }
        }
        return try {
            updateLambdaBody(exp)
        } catch (e: TCOError) {
            errors += mkWarn(E.cannotTCO(e.msg), exp.span)
            exp
        }
    }

    private fun Expr.toLoop(name: String, pars: List<String>, varCheck: (Expr) -> Boolean): Expr = when (this) {
        is Expr.Lambda -> copy(body = body.toLoop(name, pars, varCheck))
        is Expr.Do -> {
            val ex = exps.toMutableList()
            ex[ex.lastIndex] = ex[ex.lastIndex].toLoop(name, pars, varCheck)
            copy(exps = ex)
        }
        is Expr.Let -> copy(body = body.toLoop(name, pars, varCheck))
        is Expr.If ->
            copy(
                elseCase = elseCase.toLoop(name, pars, varCheck),
                conds = conds.map { it.first to it.second.toLoop(name, pars, varCheck) }
            )
        is Expr.Throw -> this
        is Expr.App -> {
            var hasCall = false
            everywherUnit { if (varCheck(it)) hasCall = true }
            if (hasCall) {
                // rewrite this recursive call to just set the loop variables
                val args = mutableListOf<Expr>()
                var app = this
                while (app is Expr.App) {
                    args += app.arg
                    app = app.fn
                }
                args.reverse()

                if (pars.size != args.size) {
                    val rname = name.split(".").last()
                    throw TCOError(rname)
                }
                val newBinds = args.mapIndexed { i, arg -> "tmp$$i" to arg }
                val body = pars.zip(newBinds).map { (par, v) ->
                    Expr.SetLocalVar(par, Expr.LocalVar(v.first, v.second.type, v.second.span), Clazz(UNIT_TYPE))
                }
                nestLets(newBinds, Expr.Do(body, type, span), type)
            } else Expr.Return(this)
        }
        else -> Expr.Return(this)
    }

    /**
     * Returns true if this expression is a tail recursive function.
     */
    private fun CExpr.isTailcall(name: String, tail: Boolean = true): Boolean = when (this) {
        is CExpr.Lambda -> body.isTailcall(name, tail)
        is CExpr.Do -> {
            exps.dropLast(1).all { it.isTailcall(name, false) } && exps.last().isTailcall(name, tail)
        }
        is CExpr.Let -> letDef.expr.isTailcall(name, false) && body.isTailcall(name, tail)
        is CExpr.If -> {
            cond.isTailcall(name, false) && thenCase.isTailcall(name, tail) && elseCase.isTailcall(name, tail)
        }
        is CExpr.Ann -> exp.isTailcall(name, tail)
        is CExpr.TypeCast -> exp.isTailcall(name, tail)
        is CExpr.While -> false
        is CExpr.TryCatch -> false
        is CExpr.Throw -> false
        is CExpr.Match -> exps.all { it.isTailcall(name, false) } && cases.all { it.exp.isTailcall(name, tail) }
        is CExpr.App -> fn.isTailcall(name, tail) && arg.isTailcall(name, false)
        is CExpr.Var -> if (fullname() == name) tail else true
        is CExpr.RecordExtend -> exp.isTailcall(name, false) && labels.allList { it.isTailcall(name, false) }
        is CExpr.RecordMerge -> exp1.isTailcall(name, false) && exp2.isTailcall(name, false)
        is CExpr.RecordRestrict -> exp.isTailcall(name, false)
        is CExpr.RecordUpdate -> exp.isTailcall(name, false) && value.isTailcall(name, false)
        is CExpr.ListLiteral -> exps.all { it.isTailcall(name, false) }
        is CExpr.SetLiteral -> exps.all { it.isTailcall(name, false) }
        is CExpr.ForeignField -> exp.isTailcall(name, false)
        is CExpr.ForeignFieldSetter -> field.isTailcall(name, false) && value.isTailcall(name, false)
        is CExpr.ForeignStaticFieldSetter -> value.isTailcall(name, false)
        is CExpr.ForeignStaticMethod -> args.all { it.isTailcall(name, false) }
        is CExpr.ForeignMethod -> exp.isTailcall(name, false) && args.all { it.isTailcall(name, false) }
        else -> true
    }

    private val stringType = tString.convert()
    private val boolType = tBoolean.convert()
    private val longType = tInt64.convert()
    private val intType = tInt32.convert()

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
                || expectedFieldType.type == OBJECT_TYPE
            ) field
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

        fun mkListSizeCheck(exp: Expr, size: Int): Expr {
            val sizeExp = Expr.NativeMethod(listSize, exp, emptyList(), longType, exp.span)
            val longe = Expr.Int64(size.toLong(), longType, exp.span)
            return Expr.OperatorApp("==", listOf(sizeExp, longe), boolType, exp.span)
        }

        fun mkListMinSizeCheck(exp: Expr, size: Int): Expr {
            val minSize = Expr.Int32(size, intType, exp.span)
            return Expr.NativeStaticMethod(listMinSize, listOf(minSize, exp), boolType, exp.span)
        }

        fun mkListTail(exp: Expr, fieldTy: Clazz, size: Int): Expr {
            val listClass = Clazz(LIST_TYPE, listOf(fieldTy))
            val howMany = Expr.Int32(size, intType, exp.span)
            return Expr.NativeStaticMethod(listDrop, listOf(howMany, exp), listClass, exp.span)
        }

        fun mkListAccessor(exp: Expr, index: Long, type: Clazz): Expr =
            Expr.NativeMethod(listAccess, exp, listOf(Expr.Int64(index, longType, exp.span)), type, exp.span)

        fun desugarPattern(p: Pattern, exp: Expr): PatternResult = when (p) {
            is Pattern.Wildcard -> PatternResult(tru)
            is Pattern.Var -> PatternResult(tru, listOf(VarDef(Names.convert(p.v.name), exp)))
            is Pattern.Unit -> PatternResult(tru)
            is Pattern.LiteralP -> {
                val lit = p.lit.e.convert(locals)
                if (p.lit is LiteralPattern.StringLiteral) {
                    PatternResult(Expr.NativeMethod(eqString, lit, listOf(exp), boolType, exp.span))
                } else {
                    PatternResult(Expr.OperatorApp("==", listOf(lit, exp), boolType, exp.span))
                }
            }
            is Pattern.Regex -> {
                val regex = Expr.StringE(p.regex, stringType, p.span)
                val method = Expr.NativeStaticMethod(patternMatches, listOf(regex, exp), boolType, exp.span)
                PatternResult(method)
            }
            is Pattern.Ctor -> {
                val conds = mutableListOf<Expr>()
                val vars = mutableListOf<VarDef>()

                when (val name = p.ctor.fullname(p.ctor.moduleName ?: ast.name.value)) {
                    "prim.Some" -> {
                        conds += Expr.OperatorApp("!=null", listOf(exp), boolType, exp.span)
                        val (cond, vs) = desugarPattern(p.fields[0], Expr.Unbox(exp, toPrimitive(exp.type)))
                        conds += cond
                        vars += vs
                    }
                    "prim.None" -> {
                        conds += Expr.OperatorApp("=null", listOf(exp), boolType, exp.span)
                    }
                    else -> {
                        val ctor = p.ctor.convert(locals) as Expr.Constructor
                        val ctorType = Clazz(Type.getObjectType(internalize(name)), pars = ctor.type.pars.dropLast(1))
                        conds += Expr.InstanceOf(exp, ctorType, p.span)
                        val (fieldTypes, _) = peelArgs(Environment.findConstructor(name)!!)
                        val expectedFieldTypes = p.fields.map { it.type!!.convert() }

                        p.fields.forEachIndexed { i, pat ->
                            val idx = i + 1
                            val field =
                                mkCtorField(ctor.fullName, idx, exp, ctorType, fieldTypes[i], expectedFieldTypes.getOrNull(i))
                            val (cond, vs) = desugarPattern(pat, field)
                            conds += cond
                            vars += vs
                        }
                    }
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
            is Pattern.ListP -> {
                val conds = mutableListOf<Expr>()
                val vars = mutableListOf<VarDef>()

                val elemSize = p.elems.size
                conds += if (p.tail == null) mkListSizeCheck(exp, elemSize) else mkListMinSizeCheck(exp, elemSize)
                val fieltTy = exp.type.pars.firstOrNull() ?: internalError("Got wrong type for list: ${exp.type}")
                p.elems.forEachIndexed { i, pat ->
                    val field = mkListAccessor(exp, i.toLong(), fieltTy)
                    val (cond, vs) = desugarPattern(pat, field)
                    conds += cond
                    vars += vs
                }
                if (p.tail != null) {
                    val (tCond, tVs) = desugarPattern(p.tail, mkListTail(exp, fieltTy, elemSize))
                    conds += tCond
                    vars += tVs
                }

                PatternResult(simplifyConds(conds), vars)
            }
            is Pattern.Named -> {
                val (cond, vars) = desugarPattern(p.pat, exp)
                PatternResult(cond, vars + VarDef(p.name.value, exp))
            }
            is Pattern.TypeTest -> {
                val castType = p.test.convert()
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
                // we have to duplicate the variable definitions here because
                // they are not visible in the expression
                val expr = varToLet(vars, caseExp)
                guardCond to expr
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

    private fun makeMetaExpr(meta: List<Pair<String, Metadata>>): Decl.ValDecl {
        fun register(decl: String, exp: CExpr.RecordExtend): Expr {
            val modExpr = Expr.StringE(ast.name.value, stringType, Span.empty())
            val declExpr = Expr.StringE(decl, stringType, Span.empty())
            val retur = Clazz(Type.VOID_TYPE)
            return Expr.NativeStaticMethod(registerMetas, listOf(modExpr, declExpr, exp.convert()), retur, Span.empty())
        }

        val expr = Expr.Do(meta.map { (decl, attr) -> register(decl, attr.data) }, Clazz(UNIT_TYPE), Span.empty())
        return Decl.ValDecl("\$meta", expr, Visibility.PRIVATE, Span.empty())
    }

    private fun needVar(exp: Expr): Boolean = when (exp) {
        is Expr.Var, is Expr.LocalVar, is Expr.ByteE, is Expr.Int16,
        is Expr.Int32, is Expr.Int64, is Expr.Float32, is Expr.Float64,
        is Expr.StringE, is Expr.CharE, is Expr.Bool, is Expr.Constructor -> false
        else -> true
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
            errors += mkWarn(E.NON_EXHAUSTIVE_PATTERN, span)
        }
        if (res.redundantMatches.isNotEmpty()) {
            val unreacheable = res.redundantMatches.map { it.joinToString { p -> p.show() } }
            errors += mkWarn(E.redundantMatches(unreacheable), expr.span)
        }
    }

    private fun checkUseImport(e: CExpr) {
        if (unusedImports.isEmpty()) return
        Desugar.collectVars(e).forEach { unusedImports.remove(it) }
    }

    private fun reportUnusedImports() {
        if (unusedImports.isEmpty()) return
        val errs = unusedImports.map { (vvar, span) ->
            CompilerProblem(
                E.unusedImport(vvar),
                span,
                ast.sourceName,
                ast.name.value,
                severity = Severity.WARN,
                action = Action.UnusedImport(vvar)
            )
        }
        errors += errs
    }

    private fun mkWarn(msg: String, span: Span): CompilerProblem =
        CompilerProblem(
            msg,
            span,
            ast.sourceName,
            ast.name.value,
            severity = Severity.WARN
        )

    companion object {

        class TCOError(val msg: String) : java.lang.RuntimeException(msg)

        private fun getPrimitiveTypeName(tvar: TConst): Type = when (tvar.name) {
            primByte -> Type.getType(Byte::class.java)
            primInt16 -> Type.getType(Short::class.java)
            primInt32 -> Type.getType(Int::class.java)
            primInt64 -> Type.getType(Long::class.java)
            primFloat32 -> Type.getType(Float::class.java)
            primFloat64 -> Type.getType(Double::class.java)
            primBoolean -> Type.getType(Boolean::class.java)
            primChar -> Type.getType(Char::class.java)
            primString -> Type.getType(String::class.java)
            primUnit -> UNIT_TYPE
            primObject -> OBJECT_TYPE
            primArray -> ARRAY_TYPE
            primByteArray -> Type.getType(ByteArray::class.java)
            primInt16Array -> Type.getType(ShortArray::class.java)
            primInt32Array -> Type.getType(IntArray::class.java)
            primInt64Array -> Type.getType(LongArray::class.java)
            primFloat32Array -> Type.getType(FloatArray::class.java)
            primFloat64Array -> Type.getType(DoubleArray::class.java)
            primBooleanArray -> Type.getType(BooleanArray::class.java)
            primCharArray -> Type.getType(CharArray::class.java)
            primList -> LIST_TYPE
            primMap -> Type.getType(Map::class.java)
            primSet -> Type.getType(io.lacuna.bifurcan.Set::class.java)
            primRange -> Type.getType(Range::class.java)
            else -> Type.getObjectType(internalize(tvar.name))
        }

        private fun getPrimitiveOptionType(of: Type): Type = when (of.sort) {
            1 -> Type.getType(Boolean::class.javaObjectType)
            2 -> Type.getType(Char::class.javaObjectType)
            3 -> Type.getType(Byte::class.javaObjectType)
            4 -> Type.getType(Short::class.javaObjectType)
            5 -> Type.getType(Int::class.javaObjectType)
            6 -> Type.getType(Float::class.javaObjectType)
            7 -> Type.getType(Long::class.javaObjectType)
            8 -> Type.getType(Double::class.javaObjectType)
            else -> of
        }

        private fun toPrimitive(ty: Clazz): Clazz = when (ty.type.className) {
            "java.lang.Byte" -> Clazz(Type.getType(Byte::class.java))
            "java.lang.Short" -> Clazz(Type.getType(Short::class.java))
            "java.lang.Integer" -> Clazz(Type.getType(Int::class.java))
            "java.lang.Long" -> Clazz(Type.getType(Long::class.java))
            "java.lang.Float" -> Clazz(Type.getType(Float::class.java))
            "java.lang.Double" -> Clazz(Type.getType(Double::class.java))
            "java.lang.Character" -> Clazz(Type.getType(Char::class.java))
            "java.lang.Boolean" -> Clazz(Type.getType(Boolean::class.java))
            else -> ty
        }

        private fun getPrimitiveArrayTypeName(of: Type): Type {
            return Type.getType("[${of.wrapper().descriptor}")
        }

        private fun internalize(name: String) = name.replace('.', '/')

        private val RECORD_TYPE = Type.getType(Record::class.java)
        private val FUNCTION_TYPE = Type.getType(Function::class.java)
        private val LIST_TYPE = Type.getType(PList::class.java)
        private val UNIT_TYPE = Type.getObjectType(internalize("novah.Unit"))
        val OBJECT_TYPE = Type.getType(Object::class.java)!!
        val ARRAY_TYPE = Type.getType(Array::class.java)!!

        private val rteCtor = RuntimeException::class.java.constructors.find {
            it.parameterCount == 1 && it.parameterTypes[0].canonicalName == "java.lang.String"
        }!!

        val listSize = PList::class.java.methods.find { it.name == "size" }!!
        private val listAccess = PList::class.java.methods.find { it.name == "nth" }!!
        private val listDrop = Core::class.java.methods.find { it.name == "listDrop" }!!
        private val listMinSize = Core::class.java.methods.find { it.name == "listMinSize" }!!
        private val some = Core::class.java.fields.find { it.name == "some" }!!
        private val eqString = String::class.java.methods.find { it.name == "equals" }!!
        val newRecFun: Constructor<*> = RecFunction::class.java.constructors.first()
        val recFunField = RecFunction::class.java.fields.find { it.name == "fun" }!!
        val patternMatches = java.util.regex.Pattern::class.java.methods.find { it.name == "matches" }!!
        val registerMetas = novah.Metadata::class.java.methods.find { it.name == "registerMetas" }!!
    }
}