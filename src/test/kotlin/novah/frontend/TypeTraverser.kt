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
                e.exps.forEach { visitExpr(it) }
                e.cases.forEach {
                    if (it.guard != null) visitExpr(it.guard!!)
                    visitExpr(it.exp)
                }
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