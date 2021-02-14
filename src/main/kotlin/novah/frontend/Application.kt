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

import novah.ast.source.Expr

object Application {

    private sealed class Fixity {
        object Left : Fixity()
        object Right : Fixity()
    }

    private fun <T> MutableList<T>.removeLast() {
        this.removeAt(this.size - 1)
    }

    /**
     * Validates that a list of [Expr]s is well formed.
     * This function expects the list to be already resolved
     * of function applications and only operators are left.
     * Ex:
     *   a + 5 * 9 -> good
     *   a + * 9 -> bad
     *   a b * 6 -> bad
     */
    private fun validateOps(exps: List<Expr>): Boolean {
        if (exps.size % 2 == 0) return false

        var prev: Expr? = null
        for (e in exps) {
            when (prev) {
                null, is Expr.Operator -> if (e is Expr.Operator) return false
                else -> if (e !is Expr.Operator) return false
            }
            prev = e
        }
        return true
    }

    /**
     * Get the precedence of some operator
     * which depends on the first symbol.
     */
    private fun getPrecedence(op: Expr.Operator): Int = when (op.name[0]) {
        '$' -> 1
        '=' -> 2
        '<', '>' -> 3
        '|' -> 4
        '&' -> 5
        '+', '-', ':' -> 6
        '*', '/', '%' -> 7
        '^', '.' -> 8
        else -> 9 // backtick operator: `fun`
    }

    /**
     * Returns the fixity (left/right) of an operator.
     * Operators that start with `$` or `:` are right associative.
     * Operators that end with `<` (as long as they have at least 2 characters)
     * are also right associative.
     * Everything else is left associative.
     */
    private fun getFixity(op: Expr.Operator): Fixity = when (op.name[0]) {
        '$', ':' -> Fixity.Right
        else -> {
            if (op.name.length > 1) {
                if (op.name.last() == '<') Fixity.Right
                else Fixity.Left
            } else Fixity.Left
        }
    }

    /**
     * Resolve all the function applications in a list of expressions
     */
    private fun resolveApps(exps: List<Expr>): List<Expr> {
        val acc = mutableListOf<Expr>()

        for (e in exps) {
            val prev = acc.lastOrNull()
            acc += if (e !is Expr.Operator && prev != null && prev !is Expr.Operator) {
                acc.removeLast()
                Expr.App(prev, e).withSpan(prev.span, e.span)
            } else e
        }

        return acc
    }

    /**
     * Resolve some operator from left to right or right to left
     * depending on the index function.
     */
    private fun resolveOp(exps: List<Expr>, index: (List<Expr>) -> Int): List<Expr> {
        val input = ArrayList(exps)

        var i = index(input)
        while (i != -1) {
            val left = input[i - 1]
            val ope = input[i]
            val right = input[i + 1]
            val innerSpan = Span(ope.span.startLine, ope.span.startColumn, left.span.endLine, left.span.endColumn)
            val outerSpan = Span(ope.span.startLine, ope.span.startColumn, right.span.endLine, left.span.endColumn)
            val app = Expr.App(Expr.App(ope, left).withSpan(innerSpan), right).withSpan(outerSpan)

            input.removeAt(i - 1)
            input.removeAt(i - 1)
            input.removeAt(i - 1)
            input.add(i - 1, app)
            i = index(input)
        }

        return input
    }

    /**
     * Get the operator with the highest precedence in a list of [Expr]s.
     */
    private fun getHighestPrecedenceOp(es: List<Expr>): Expr.Operator? =
        es.filterIsInstance<Expr.Operator>().maxByOrNull(::getPrecedence)

    /**
     * Parse a list of expressions and resolve operator precedence
     * as well as left/right fixity
     */
    fun parseApplication(exps: List<Expr>): Expr? {

        when (exps.size) {
            0 -> return null
            1 -> return exps[0]
        }

        // first resolve function application as it has the highest precedence
        val resExps = resolveApps(exps)
        if (!validateOps(resExps)) {
            return null
        }

        var res = resExps
        var highest = getHighestPrecedenceOp(res)
        while (highest != null) {
            res = when (getFixity(highest)) {
                is Fixity.Left -> resolveOp(res) { it.indexOf(highest!!) }
                is Fixity.Right -> resolveOp(res) { it.lastIndexOf(highest!!) }
            }
            highest = getHighestPrecedenceOp(res)
        }

        return res[0]
    }

    /**
     * Take an application and unnest it based on precedence.
     * The oposite of [parseApplication]
     *
     * Ex:
     * input: (((>> ((>> a) b)) c) 1)
     * output: (a >> b >> c) 1
     */
    fun unparseApplication(app: Expr.App): List<Expr> {
        fun go(exp: Expr): List<Expr> = when (exp) {
            is Expr.App -> {
                val fn = exp.fn
                if (fn is Expr.App && fn.fn is Expr.Operator) {
                    go(fn.arg) + listOf(fn.fn) + go(exp.arg)
                } else {
                    go(exp.fn) + go(exp.arg)
                }
            }
            else -> listOf(exp)
        }
        return go(app)
    }
}