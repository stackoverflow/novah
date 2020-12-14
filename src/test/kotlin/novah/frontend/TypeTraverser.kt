package novah.frontend

import novah.ast.canonical.Decl
import novah.ast.canonical.Expr
import novah.ast.canonical.Module
import novah.frontend.typechecker.Type

/**
 * A class that receives a AST and a functions
 * and visits every type in the ast.
 */
class TypeTraverser<T>(private val ast: Module, private val action: (String, Type?) -> T) {
    
    fun run() {
        ast.decls.filterIsInstance<Decl.ValDecl>().forEach { visitValDecl(it) }
    }
    
    fun visitValDecl(d: Decl.ValDecl) {
        action(d.name, d.exp.type)
        visitExpr(d.exp)
    }
    
    fun visitExpr(e: Expr) {
        when (e) {
            is Expr.Lambda -> {
                action("Lambda \\${e.binder} at ${e.span}", e.type)
                visitExpr(e.body)
            }
            is Expr.App -> {
                action("App at ${e.span}", e.type)
                visitExpr(e.fn)
                visitExpr(e.arg)
            }
            is Expr.If -> {
                action("If at ${e.span}", e.type)
                visitExpr(e.cond)
                visitExpr(e.thenCase)
                visitExpr(e.elseCase)
            }
            is Expr.Let -> {
                action("Let at ${e.span}", e.type)
                visitExpr(e.letDef.expr)
                visitExpr(e.body)
            }
            is Expr.Match -> {
                action("Match at ${e.span}", e.type)
                visitExpr(e.exp)
                e.cases.forEach { visitExpr(it.exp) }
            }
            is Expr.Ann -> {
                action("Ann at ${e.span}", e.type)
                visitExpr(e.exp)
            }
            is Expr.Do -> {
                action("Do at ${e.span}", e.type)
                e.exps.forEach { visitExpr(it) }
            }
            else -> action("$e", e.type)
        }
    }
}