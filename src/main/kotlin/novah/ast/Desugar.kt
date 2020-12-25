package novah.ast

import novah.ast.canonical.*
import novah.ast.source.fullName
import novah.frontend.*
import novah.frontend.typechecker.Name
import novah.frontend.typechecker.Prim
import novah.frontend.typechecker.Type
import novah.frontend.typechecker.raw
import novah.ast.source.Case as SCase
import novah.ast.source.Binder as SBinder
import novah.ast.source.DataConstructor as SDataConstructor
import novah.ast.source.Decl as SDecl
import novah.ast.source.Expr as SExpr
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

    private val topLevelTypes = smod.decls.filterIsInstance<SDecl.TypeDecl>().map { it.name to it.type }.toMap()
    private val dataCtors = smod.decls.filterIsInstance<SDecl.DataDecl>().map { it.name }
    private val exports = validateExports()
    private val imports = validateImports()
    private val moduleName = smod.name.joinToString(".")

    fun desugar(): Module {
        return Module(smod.fullName(), smod.sourceName, smod.decls.map { it.desugar() })
    }

    private fun SDecl.desugar(): Decl = when (this) {
        is SDecl.TypeDecl -> Decl.TypeDecl(name, type.desugar(), span)
        is SDecl.DataDecl -> Decl.DataDecl(name, tyVars, dataCtors.map { it.desugar() }, span, exports.visibility(name))
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
        DataConstructor(name, args.map { it.desugar() }, exports.ctorVisibility(name))

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
            else Expr.Var(name.raw(), span, imports.resolve(this))
        }
        is SExpr.Operator -> Expr.Var(name.raw(), span, imports.resolve(this))
        is SExpr.Lambda -> nestLambdas(binders.map { it.desugar() }, body.desugar(locals + binders.map { it.name }))
        is SExpr.App -> Expr.App(fn.desugar(locals), arg.desugar(locals), span)
        is SExpr.Parens -> exp.desugar(locals)
        is SExpr.If -> Expr.If(cond.desugar(locals), thenCase.desugar(locals), elseCase.desugar(locals), span)
        is SExpr.Let -> nestLets(letDefs, body.desugar(locals + letDefs.map { it.name.name }))
        is SExpr.Match -> Expr.Match(exp.desugar(locals), cases.map { it.desugar() }, span)
        is SExpr.Ann -> Expr.Ann(exp.desugar(locals), type.desugar(), span)
        is SExpr.Do -> Expr.Do(exps.map { it.desugar(locals) }, span)
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
        return if (binders.isEmpty()) LetDef(name.desugar(), expr.desugar(), type?.desugar())
        else {
            fun go(binders: List<Binder>, exp: Expr): Expr {
                return if (binders.size == 1) Expr.Lambda(binders[0], exp, exp.span)
                else go(binders.drop(1), Expr.Lambda(binders[0], exp, exp.span))
            }
            LetDef(name.desugar(), go(binders.map { it.desugar() }, expr.desugar()), type?.desugar())
        }
    }

    private fun SBinder.desugar(): Binder {
        return Binder(name.raw(), span)
    }

    private fun SType.desugar(): Type = when (this) {
        is SType.TVar -> {
            if (name[0].isLowerCase()) Type.TVar(name.raw())
            else {
                // at this point we know if a type is a normal type
                // or a no-parameter ADT
                if (name in dataCtors) Type.TConstructor(imports.fullname(name, moduleName).raw(), listOf())
                else Type.TVar(imports.fullname(name, moduleName).raw())
            }
        }
        is SType.TFun -> Type.TFun(arg.desugar(), ret.desugar())
        is SType.TForall -> nestForalls(names.map(Name::Raw), type.desugar())
        is SType.TParens -> type.desugar()
        is SType.TConstructor -> Type.TConstructor(imports.fullname(name, moduleName).raw(), types.map { it.desugar() })
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
     * Only lambdas and primitives vars be defined at the top level,
     * otherwise we throw an error.
     */
    private fun validateTopLevelExpr(declName: String, e: Expr) {
        fun report() {
            throw ParserError("only lambdas and primitives can be defined at the top level for declaration $declName at ${e.span}")
        }

        when (e) {
            is Expr.Var, is Expr.App, is Expr.If, is Expr.Let, is Expr.Match, is Expr.Do -> report()
            is Expr.Ann -> validateTopLevelExpr(declName, e.exp)
            else -> {
            }
        }
    }

    /**
     * Make sure all exports are valid and return them
     */
    private fun validateExports(): ExportResult {
        val res = consolidateExports(smod.exports, smod.decls)
        // TODO: better error reporting with context
        if (res.varErrors.isNotEmpty()) {
            throw ParserError(E.exportError(res.varErrors[0]))
        }
        if (res.ctorErrors.isNotEmpty()) {
            throw ParserError(E.exportCtorError(res.ctorErrors[0]))
        }
        return res
    }

    /**
     * Make sure all imports are valid and return them
     */
    private fun validateImports(): ImportResult {
        val imps = smod.imports + Prim.primImport
        val res = consolidateImports(imps)
        // TODO: better error reporting with context
        if (res.errors.isNotEmpty()) {
            throw ParserError(E.importError(res.errors[0]))
        }
        return res
    }
}