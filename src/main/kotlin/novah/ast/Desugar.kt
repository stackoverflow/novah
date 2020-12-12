package novah.ast

import novah.ast.canonical.*
import novah.frontend.ExportResult
import novah.frontend.ModuleExports
import novah.frontend.ParserError
import novah.frontend.typechecker.Type
import novah.ast.source.Case as SCase
import novah.ast.source.DataConstructor as SDataConstructor
import novah.ast.source.Decl as SDecl
import novah.ast.source.Expr as SExpr
import novah.ast.source.Import as SImport
import novah.ast.source.LetDef as SLetDef
import novah.ast.source.LiteralPattern as SLiteralPattern
import novah.ast.source.Module as SModule
import novah.ast.source.Pattern as SPattern
import novah.ast.source.Type as SType
import novah.frontend.Errors as E

/**
 * Converts a source AST to the canonical spanned AST
 */
class Desugar(private val smod: SModule) {

    private val topLevelTypes = mutableMapOf<String, SType>()
    private val dataCtors = mutableListOf<String>()
    private val exports = validateExports()

    fun desugar(): Module {
        smod.decls.filterIsInstance<SDecl.TypeDecl>().forEach { topLevelTypes[it.name] = it.type }
        smod.decls.filterIsInstance<novah.ast.source.Decl.DataDecl>().forEach { dataCtors += it.name }

        return Module(smod.name, smod.imports.map { it.desugar() }, smod.decls.map { it.desugar() })
    }

    private fun SImport.desugar(): Import = when (this) {
        is SImport.Raw -> Import.Raw(mod, alias)
        is SImport.Exposing -> Import.Exposing(mod, defs, alias)
    }

    private fun SDecl.desugar(): Decl = when (this) {
        is SDecl.TypeDecl -> Decl.TypeDecl(name, type.desugar(), span)
        is SDecl.DataDecl -> Decl.DataDecl(name, tyVars, dataCtors.map { it.desugar() }, span, exports.visibility(name))
        is SDecl.ValDecl -> {
            var expr = nestLambdas(binders, exp.desugar())
            // if the declaration has a type annotation, annotate it
            expr = topLevelTypes[name]?.let { type ->
                Expr.Ann(expr, type.desugar(), span)
            } ?: expr
            Decl.ValDecl(name, expr, span, exports.visibility(name))
        }
    }

    private fun SDataConstructor.desugar(): DataConstructor =
        DataConstructor(name, args.map { it.desugar() }, exports.ctorVisibility(name))

    private fun SExpr.desugar(): Expr = when (this) {
        is SExpr.IntE -> Expr.IntE(i, span)
        is SExpr.FloatE -> Expr.FloatE(f, span)
        is SExpr.StringE -> Expr.StringE(s, span)
        is SExpr.CharE -> Expr.CharE(c, span)
        is SExpr.Bool -> Expr.Bool(b, span)
        is SExpr.Var -> Expr.Var(name, span) // TODO: get the module name
        is SExpr.Operator -> Expr.Var(name, span) // TODO: get the module name
        is SExpr.Lambda -> nestLambdas(binders, body.desugar())
        is SExpr.App -> Expr.App(fn.desugar(), arg.desugar(), span)
        is SExpr.Parens -> exp.desugar()
        is SExpr.If -> Expr.If(cond.desugar(), thenCase.desugar(), elseCase.desugar(), span)
        is SExpr.Let -> nestLets(letDefs, body.desugar())
        is SExpr.Match -> Expr.Match(exp.desugar(), cases.map { it.desugar() }, span)
        is SExpr.Ann -> Expr.Ann(exp.desugar(), type.desugar(), span)
        is SExpr.Do -> Expr.Do(exps.map { it.desugar() }, span)
    }

    private fun SCase.desugar(): Case = Case(pattern.desugar(), exp.desugar())

    private fun SPattern.desugar(): Pattern = when (this) {
        is SPattern.Wildcard -> Pattern.Wildcard
        is SPattern.LiteralP -> Pattern.LiteralP(lit.desugar())
        is SPattern.Var -> Pattern.Var(name)
        is SPattern.Ctor -> Pattern.Ctor(name, fields.map { it.desugar() })
    }

    private fun SLiteralPattern.desugar(): LiteralPattern = when (this) {
        is SLiteralPattern.BoolLiteral -> LiteralPattern.BoolLiteral(b)
        is SLiteralPattern.CharLiteral -> LiteralPattern.CharLiteral(c)
        is SLiteralPattern.StringLiteral -> LiteralPattern.StringLiteral(s)
        is SLiteralPattern.IntLiteral -> LiteralPattern.IntLiteral(i)
        is SLiteralPattern.FloatLiteral -> LiteralPattern.FloatLiteral(f)
    }

    private fun SLetDef.desugar(): LetDef {
        return if (binders.isEmpty()) LetDef(name, expr.desugar(), type?.desugar())
        else {
            fun go(binders: List<String>, exp: Expr): Expr {
                return if (binders.size == 1) Expr.Lambda(binders[0], exp, exp.span)
                else go(binders.drop(1), Expr.Lambda(binders[0], exp, exp.span))
            }
            LetDef(name, go(binders, expr.desugar()), type?.desugar())
        }
    }

    private fun SType.desugar(): Type = when (this) {
        is SType.TVar -> {
            // at this point we know if a type is a normal type
            // or a no-parameter ADT
            if (name in dataCtors) Type.TConstructor(name)
            else Type.TVar(name)
        }
        is SType.TFun -> Type.TFun(arg.desugar(), ret.desugar())
        is SType.TForall -> nestForalls(names, type.desugar())
        is SType.TParens -> type.desugar()
        is SType.TConstructor -> Type.TConstructor(name, types.map { it.desugar() })
    }

    private fun nestForalls(names: List<String>, type: Type): Type {
        return if (names.isEmpty()) type
        else Type.TForall(names[0], nestForalls(names.drop(1), type))
    }

    private fun nestLambdas(binders: List<String>, exp: Expr): Expr {
        return if (binders.isEmpty()) exp
        else Expr.Lambda(binders[0], nestLambdas(binders.drop(1), exp), exp.span)
    }

    private fun nestLets(defs: List<SLetDef>, exp: Expr): Expr {
        return if (defs.isEmpty()) exp
        else Expr.Let(defs[0].desugar(), nestLets(defs.drop(1), exp), exp.span)
    }

    /**
     * Make sure all exports are valid and return them
     */
    private fun validateExports(): ExportResult {
        val res = ModuleExports.consolidate(smod.exports, smod.decls)
        // TODO: better error reporting with context
        if (res.varErrors.isNotEmpty()) {
            throw ParserError(E.exportError(res.varErrors[0]))
        }
        if (res.ctorErrors.isNotEmpty()) {
            throw ParserError(E.exportCtorError(res.ctorErrors[0]))
        }
        return res
    }

    private fun validateImportAlias() {
        TODO()
    }
}