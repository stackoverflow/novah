package novah.optimize

import novah.Util.internalError
import novah.ast.canonical.Pattern
import novah.ast.canonical.show
import novah.ast.optimized.*
import novah.frontend.matching.PatternCompilationResult
import novah.frontend.matching.PatternMatchingCompiler
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
import novah.frontend.typechecker.Type as TType

/**
 * Converts the canonical AST to the
 * optimized version, ready for codegen
 */
class Optimizer(private val ast: CModule) {

    private var haslambda = false

    fun convert(): Module {
        PatternMatchingCompiler.addConsToCache(ast)
        return ast.convert()
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
            is CExpr.App -> Expr.App(fn.convert(locals), arg.convert(locals), typ)
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

    companion object {
        private fun reportPatternMatch(res: PatternCompilationResult<Pattern>, expr: CExpr) {
            if (!res.exhaustive) {
                inferError("a case expression could not be determined to cover all inputs.", expr.span)
            }
            if (res.redundantMatches.isNotEmpty()) {
                val unreacheable = res.redundantMatches.joinToString(", ") { it.show() }
                inferError("a case expression contains redundant cases: $unreacheable", expr.span)
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

        private fun internalize(name: String) = name.replace('.', '/')
    }
}