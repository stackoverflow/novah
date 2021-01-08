package novah.ast

import novah.ast.canonical.*
import novah.ast.source.fullname
import novah.frontend.*
import novah.frontend.typechecker.*
import novah.ast.source.Binder as SBinder
import novah.ast.source.Case as SCase
import novah.ast.source.DataConstructor as SDataConstructor
import novah.ast.source.Decl as SDecl
import novah.ast.source.Expr as SExpr
import novah.ast.source.LetDef as SLetDef
import novah.ast.source.LiteralPattern as SLiteralPattern
import novah.ast.source.Module as SModule
import novah.ast.source.Pattern as SPattern
import novah.ast.source.Type as SType
import novah.frontend.error.Errors as E

/**
 * Converts a source AST to the canonical spanned AST
 */
class Desugar(private val smod: SModule) {

    private val topLevelTypes = smod.decls.filterIsInstance<SDecl.TypeDecl>().map { it.name to it.type }.toMap()
    private val dataCtors = smod.decls.filterIsInstance<SDecl.DataDecl>().map { it.name }
    private val exports = validateExports()
    private val imports = smod.resolvedImports
    private val moduleName = smod.name

    fun desugar(): Module {
        return Module(moduleName, smod.sourceName, smod.decls.map { it.desugar() })
    }

    private fun SDecl.desugar(): Decl = when (this) {
        is SDecl.TypeDecl -> Decl.TypeDecl(name, type.desugar(), span)
        is SDecl.DataDecl -> {
            validateDataConstructorNames(this)
            Decl.DataDecl(name, tyVars, dataCtors.map { it.desugar() }, span, exports.visibility(name))
        }
        is SDecl.ValDecl -> {
            var expr = nestLambdas(binders.map { it.desugar() }, exp.desugar())
            validateTopLevelExpr(name, expr)

            // if the declaration has a type annotation, annotate it
            expr = topLevelTypes[name]?.let { type ->
                Expr.Ann(expr, type.desugar(), span)
            } ?: expr
            Decl.ValDecl(name, expr, span, exports.visibility(name))
        }
    }

    private fun SDataConstructor.desugar(): DataConstructor =
        DataConstructor(name, args.map { it.desugar() }, exports.visibility(name))

    private fun SExpr.desugar(locals: List<String> = listOf()): Expr = when (this) {
        is SExpr.IntE -> Expr.IntE(v, span)
        is SExpr.LongE -> Expr.LongE(v, span)
        is SExpr.FloatE -> Expr.FloatE(v, span)
        is SExpr.DoubleE -> Expr.DoubleE(v, span)
        is SExpr.StringE -> Expr.StringE(v, span)
        is SExpr.CharE -> Expr.CharE(v, span)
        is SExpr.Bool -> Expr.Bool(v, span)
        is SExpr.Var -> {
            if (name in locals) Expr.Var(name.raw(), span)
            else Expr.Var(name.raw(), span, imports[this.toString()])
        }
        is SExpr.Operator -> Expr.Var(name.raw(), span, imports[this.toString()])
        is SExpr.Constructor -> Expr.Constructor(name.raw(), span, imports[this.toString()])
        is SExpr.Lambda -> nestLambdas(binders.map { it.desugar() }, body.desugar(locals + binders.map { it.name }))
        is SExpr.App -> Expr.App(fn.desugar(locals), arg.desugar(locals), span)
        is SExpr.Parens -> exp.desugar(locals)
        is SExpr.If -> Expr.If(cond.desugar(locals), thenCase.desugar(locals), elseCase.desugar(locals), span)
        is SExpr.Let -> nestLets(letDefs, body.desugar(locals + letDefs.map { it.name.name }))
        is SExpr.Match -> Expr.Match(exp.desugar(locals), cases.map { it.desugar(locals) }, span)
        is SExpr.Ann -> Expr.Ann(exp.desugar(locals), type.desugar(), span)
        is SExpr.Do -> Expr.Do(exps.map { it.desugar(locals) }, span)
    }

    private fun SCase.desugar(locals: List<String>): Case = Case(pattern.desugar(locals), exp.desugar())

    private fun SPattern.desugar(locals: List<String>): Pattern = when (this) {
        is SPattern.Wildcard -> Pattern.Wildcard(span)
        is SPattern.LiteralP -> Pattern.LiteralP(lit.desugar(locals), span)
        is SPattern.Var -> Pattern.Var(name.raw(), span)
        is SPattern.Ctor -> Pattern.Ctor(ctor.desugar(locals) as Expr.Constructor, fields.map { it.desugar(locals) }, span)
        is SPattern.Parens -> pattern.desugar(locals)
    }

    private fun SLiteralPattern.desugar(locals: List<String>): LiteralPattern = when (this) {
        is SLiteralPattern.BoolLiteral -> LiteralPattern.BoolLiteral(e.desugar(locals) as Expr.Bool)
        is SLiteralPattern.CharLiteral -> LiteralPattern.CharLiteral(e.desugar(locals) as Expr.CharE)
        is SLiteralPattern.StringLiteral -> LiteralPattern.StringLiteral(e.desugar(locals) as Expr.StringE)
        is SLiteralPattern.IntLiteral -> LiteralPattern.IntLiteral(e.desugar(locals) as Expr.IntE)
        is SLiteralPattern.LongLiteral -> LiteralPattern.LongLiteral(e.desugar(locals) as Expr.LongE)
        is SLiteralPattern.FloatLiteral -> LiteralPattern.FloatLiteral(e.desugar(locals) as Expr.FloatE)
        is SLiteralPattern.DoubleLiteral -> LiteralPattern.DoubleLiteral(e.desugar(locals) as Expr.DoubleE)
    }

    private fun SLetDef.desugar(): LetDef {
        return if (binders.isEmpty()) LetDef(name.desugar(), expr.desugar(), type?.desugar())
        else {
            fun go(binders: List<Binder>, exp: Expr): Expr {
                return if (binders.size == 1) Expr.Lambda(binders[0], exp, exp.span)
                else go(binders.drop(1), Expr.Lambda(binders[0], exp, exp.span))
            }
            LetDef(name.desugar(), go(binders.map { it.desugar() }, expr.desugar()), type?.desugar())
        }
    }

    private fun SBinder.desugar(): Binder = Binder(name.raw(), span)

    private fun SType.desugar(): Type = when (this) {
        is SType.TVar -> {
            if (name[0].isLowerCase()) Type.TVar(name.raw())
            else {
                val module = imports[fullname()] ?: moduleName
                // at this point we know if a type is a normal type
                // or a no-parameter constructor
                // TODO: this is wrong, only finds constructors defined in this module, not imported
                if (name in dataCtors) Type.TConstructor("$module.$name".raw(), listOf())
                else Type.TVar("$module.$name".raw())
            }
        }
        is SType.TFun -> Type.TFun(arg.desugar(), ret.desugar())
        is SType.TForall -> nestForalls(names.map(Name::Raw), type.desugar())
        is SType.TParens -> type.desugar()
        is SType.TConstructor -> {
            val module = imports[fullname()] ?: moduleName
            Type.TConstructor("$module.$name".raw(), types.map { it.desugar() })
        }
    }

    private fun nestForalls(names: List<Name>, type: Type): Type {
        return if (names.isEmpty()) type
        else Type.TForall(names[0], nestForalls(names.drop(1), type))
    }

    private fun nestLambdas(binders: List<Binder>, exp: Expr): Expr {
        return if (binders.isEmpty()) exp
        else Expr.Lambda(binders[0], nestLambdas(binders.drop(1), exp), exp.span)
    }

    private fun nestLets(defs: List<SLetDef>, exp: Expr): Expr {
        return if (defs.isEmpty()) exp
        else Expr.Let(defs[0].desugar(), nestLets(defs.drop(1), exp), exp.span)
    }

    /**
     * Only lambdas and primitives vars can be defined at the top level,
     * otherwise we throw an error.
     */
    private fun validateTopLevelExpr(declName: String, e: Expr) {
        fun report() {
            parserError(E.topLevelDisallowed(declName), e.span)
        }

        when (e) {
            is Expr.IntE, is Expr.LongE, is Expr.FloatE, is Expr.DoubleE,
            is Expr.StringE, is Expr.CharE, is Expr.Bool, is Expr.Lambda -> {
            }
            is Expr.Ann -> validateTopLevelExpr(declName, e.exp)
            else -> report()
        }
    }

    /**
     * To avoid confusion, constructors can't have the same name as
     * their type unless there's only one constructor for the type.
     */
    private fun validateDataConstructorNames(dd: SDecl.DataDecl) {
        if (dd.dataCtors.size > 1) {
            val typeName = dd.name
            dd.dataCtors.forEach { dc ->
                if (dc.name == typeName)
                    parserError(E.wrongConstructorName(typeName), dd.span)
            }
        }
    }

    /**
     * Make sure all exports are valid and return them
     */
    private fun validateExports(): ExportResult {
        val res = consolidateExports(smod.exports, smod.decls)
        // TODO: better error reporting with context
        if (res.errors.isNotEmpty()) {
            parserError(E.exportError(res.errors[0]), smod.span)
        }
        return res
    }

    private fun parserError(msg: String, span: Span): Nothing = throw ParserError(msg, span)
}