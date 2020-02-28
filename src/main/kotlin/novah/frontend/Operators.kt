package novah.frontend

object Operators {

    private sealed class Fixity {
        object Left : Fixity()
        object Right : Fixity()
    }

    private fun <T> MutableList<T>.removeLast() {
        this.removeAt(this.size - 1)
    }

    /**
     * Validates that a list of [Expression]s is well formed.
     * This function expects the list to be already resolved
     * of function applications and only operators are left.
     * Ex:
     *   a + 5 * 9 -> good
     *   a + * 9 -> bad
     *   a b * 6 -> bad
     */
    private fun validateOps(exps: List<Expression>): Boolean {
        if (exps.size % 2 == 0) return false

        var prev: Expression? = null
        for (e in exps) {
            when (prev) {
                null, is Expression.Operator -> if (e is Expression.Operator) return false
                else -> if (e !is Expression.Operator) return false
            }
            prev = e
        }
        return true
    }

    /**
     * Get the precedence of some operator
     * which depends on the first symbol.
     */
    private fun getPrecedence(op: Expression.Operator): Int = when (op.name[0]) {
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
    private fun getFixity(op: Expression.Operator): Fixity = when(op.name[0]) {
        '$', ':' -> Fixity.Right
        else -> {
            if(op.name.length > 1) {
                if(op.name.last() == '<') Fixity.Right
                else Fixity.Left
            } else Fixity.Left
        }
    }

    /**
     * Resolve all the function applications in a list of expressions
     */
    private fun resolveApps(exps: List<Expression>): List<Expression> {
        val acc = mutableListOf<Expression>()

        for (e in exps) {
            val prev = acc.lastOrNull()
            if (e !is Expression.Operator && prev != null && prev !is Expression.Operator) {
                acc.removeLast()
                acc += Expression.App(prev, e)
            } else {
                acc += e
            }
        }

        return acc
    }

    /**
     * Resolve some operator from left to right or right to left
     * depending on the index function.
     */
    private fun resolveOp(exps: List<Expression>, index: (List<Expression>) -> Int): List<Expression> {
        val input = ArrayList(exps)

        var i = index(input)
        while(i != -1) {
            val left = input[i - 1]
            val ope = input[i]
            val right = input[i + 1]
            val app = Expression.App(Expression.App(ope, left), right)

            input.removeAt(i - 1)
            input.removeAt(i - 1)
            input.removeAt(i - 1)
            input.add(i - 1, app)
            i = index(input)
        }

        return input
    }

    /**
     * Get the operator with the highest precedence in a list of [Expression]s.
     */
    private fun getHighestPrecedenceOp(es: List<Expression>): Expression.Operator? =
        es.filterIsInstance<Expression.Operator>().maxBy(::getPrecedence)

    /**
     * Parse a list of expressions and resolve operator precedence
     * as well as left/right fixity
     */
    fun parseApplication(exps: List<Expression>): Expression? {

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
            res = when(getFixity(highest)) {
                is Fixity.Left -> resolveOp(res) { it.indexOf(highest!!) }
                is Fixity.Right -> resolveOp(res) { it.lastIndexOf(highest!!) }
            }
            highest = getHighestPrecedenceOp(res)
        }

        return res[0]
    }
}