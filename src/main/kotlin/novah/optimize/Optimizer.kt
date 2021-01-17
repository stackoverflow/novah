package novah.optimize

import novah.Util.internalError
import novah.ast.canonical.Pattern
import novah.ast.canonical.show
import novah.ast.optimized.*
import novah.data.Err
import novah.data.Ok
import novah.data.Reflection
import novah.data.Result
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.frontend.matching.PatternCompilationResult
import novah.frontend.matching.PatternMatchingCompiler
import novah.frontend.typechecker.InferenceError
import novah.frontend.typechecker.Name
import novah.frontend.typechecker.Prim.tBoolean
import novah.frontend.typechecker.Prim.tByte
import novah.frontend.typechecker.Prim.tLong
import novah.frontend.typechecker.Prim.tShort
import novah.frontend.typechecker.inferError
import novah.ast.canonical.Binder as CBinder
import novah.ast.canonical.DataConstructor as CDataConstructor
import novah.ast.canonical.Decl.DataDecl as CDataDecl
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

    private fun CValDecl.convert(): Decl.ValDecl = Decl.ValDecl(name, exp.convert(), visibility, span.startLine)

    private fun CDataDecl.convert(): Decl.DataDecl =
        Decl.DataDecl(name, tyVars, dataCtors.map { it.convert() }, visibility, span.startLine)

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
                val vname = name.toString()
                if (vname in locals) Expr.LocalVar(vname, typ)
                else {
                    val cname = internalize(if (moduleName != null) "$moduleName" else ast.name) + "/Module"
                    Expr.Var(vname, cname, typ)
                }
            }
            is CExpr.Constructor -> {
                val ctorName = internalize(if (moduleName != null) "$moduleName" else ast.name) + "/$name"
                val arity = PatternMatchingCompiler.getFromCache(name.toString())?.arity
                    ?: internalError("Could not find constructor $name")
                Expr.Constructor(ctorName, arity, typ)
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
                            if (Reflection.isStatic(exp.field))
                                Expr.NativeStaticFieldSet(exp.field, pars[0].convert(locals), typ)
                            else Expr.NativeFieldSet(exp.field, pars[0].convert(locals), pars[1].convert(locals), typ)
                        }
                        is CExpr.NativeMethod -> {
                            if (Reflection.isStatic(exp.method))
                                Expr.NativeStaticMethod(exp.method, pars.map { it.convert(locals) }, typ)
                            else {
                                val nativePars = pars.map { it.convert(locals) }
                                Expr.NativeMethod(exp.method, nativePars[0], nativePars.drop(1), typ)
                            }
                        }
                        is CExpr.NativeConstructor -> Expr.NativeCtor(exp.ctor, pars.map { it.convert(locals) }, typ)
                        else -> internalError("Problem in `unrollForeignApp`, got non-native expression: $exp")
                    }
                } else Expr.App(fn.convert(locals), arg.convert(locals), typ)
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
                val match = cases.map { PatternMatchingCompiler.convert(it) }
                val compRes = PatternMatchingCompiler<Pattern>().compile(match)
                reportPatternMatch(compRes, this)
                //desugarMatch(this, locals)
                Expr.IntE(1, typ)
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
        }
    }

    private fun CBinder.convert(): String = name.toString()

    private fun TType.convert(): Type = when (this) {
        is TType.TVar -> {
            if (simpleName()[0].isLowerCase()) Type.TVar("$name".toUpperCase(), true)
            else Type.TVar(getPrimitiveTypeName(this))
        }
        is TType.TConstructor -> Type.TConstructor(internalize("$name"), types.map { it.convert() })
        is TType.TFun -> Type.TFun(arg.convert(), ret.convert())
        is TType.TForall -> type.convert()
        is TType.TMeta -> internalError("got TMeta after type checking: $this")
    }

    private fun desugarMatch(m: CExpr.Match, locals: List<String> = listOf()): Expr {
        val boolType = tBoolean.convert()
        val tru = Expr.Bool(true, boolType)

        fun desugarPattern(p: Pattern, exp: Expr): Pair<Expr, List<Expr>> = when (p) {
            is Pattern.Wildcard -> tru to emptyList()
            is Pattern.Var -> tru to listOf(Expr.LocalVar(p.name.rawName(), exp.type))
            is Pattern.LiteralP -> Expr.OperatorApp("==", listOf(exp, p.lit.e.convert(locals)), boolType) to emptyList()
            is Pattern.Ctor -> {
//                val ctor = p.ctor.convert(locals) as Expr.Constructor
//                val typeCheck = Expr.InstanceOf(ctor, exp.type)
//                val fields = p.fields.mapIndexed { i, pat ->
//                    val type = dataConstructors[ctor.fullName]!!.args[i]
//                    desugarPattern(pat, Expr.ConstructorAccess(ctor.fullName, i + 1, type))
//                }
                TODO()
            }
        }
        //val let = Expr.Let("x", m.exp.convert())
        TODO()
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
            is CExpr.NativeFieldGet -> {
                if (Reflection.isStatic(exp.field)) null
                else {
                    if (depth != 1) throwArgs(exp.name, exp.span, "getter", 1, depth)
                    exp to pars
                }
            }
            is CExpr.NativeFieldSet -> {
                val should = if (Reflection.isStatic(exp.field)) 1 else 2
                if (depth != should) throwArgs(exp.name, exp.span, "setter", should, depth)
                exp to pars
            }
            is CExpr.NativeMethod -> {
                var count = exp.method.parameterCount
                if (!Reflection.isStatic(exp.method)) count++
                if (depth != count) {
                    throwArgs(exp.name, exp.span, "method", count, depth)
                }
                exp to pars
            }
            is CExpr.NativeConstructor -> {
                if (depth != exp.ctor.parameterCount) {
                    throwArgs(exp.name, exp.span, "constructor", exp.ctor.parameterCount, depth)
                }
                exp to pars
            }
            else -> null
        }
    }

    companion object {
        private fun reportPatternMatch(res: PatternCompilationResult<Pattern>, expr: CExpr) {
            if (!res.exhaustive) {
                inferError(E.NON_EXHAUSTIVE_PATTERN, expr.span, ProblemContext.PATTERN_MATCHING)
            }
            if (res.redundantMatches.isNotEmpty()) {
                val unreacheable = res.redundantMatches.map { it.show() }
                inferError(E.redundantMatches(unreacheable), expr.span, ProblemContext.PATTERN_MATCHING)
            }
        }

        // TODO: use primitive types and implement autoboxing
        private fun getPrimitiveTypeName(tvar: TType.TVar): String = when (tvar.name.toString()) {
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
            else -> internalize(tvar.name.toString())
        }

        private fun throwArgs(name: Name, span: Span, ctx: String, should: Int, got: Int): Nothing {
            inferError(
                E.wrongArgsToNative(name, ctx, should, got),
                span,
                ProblemContext.FOREIGN
            )
        }

        private fun internalize(name: String) = name.replace('.', '/')
    }
}