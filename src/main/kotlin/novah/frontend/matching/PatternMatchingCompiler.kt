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
package novah.frontend.matching

import novah.ListMatch
import novah.Util.headTail
import novah.Util.match
import novah.Util.prepend
import novah.ast.canonical.*

data class Ctor(val name: String, val arity: Int, val span: Int)

sealed class Pat {
    data class PVar(val name: String) : Pat()
    data class PCon(val con: Ctor, val pats: List<Pat>) : Pat()
}

sealed class Access {
    object Obj : Access()
    data class Sel(val i: Int, val acc: Access) : Access()
}

/**
 * Decision tree returned by the compiler
 */
sealed class Decision<out T> {
    object Failure : Decision<Nothing>()
    data class Success<T>(val rhs: T) : Decision<T>()
    data class IfEq<T>(val acc: Access, val con: Ctor, val dt: Decision<T>, val df: Decision<T>) : Decision<T>()
}

sealed class Term {
    data class Pos(val con: Ctor, val terms: List<Term>) : Term()
    data class Neg(val cons: List<Ctor>) : Term()
}

data class ContextElem(val con: Ctor, val terms: List<Term>)

typealias Context = List<ContextElem>

data class Work(val pats: List<Pat>, val objs: List<Access>, val dscs: List<Term>)

data class MatchRule<R>(val pat: Pat, val rhs: R)

typealias Match<R> = List<MatchRule<R>>

data class PatternCompilationResult<R>(val exhaustive: Boolean, val redundantMatches: List<R>)

class PatternMatchingCompiler<R> {

    private var inexhaustive = false
    private val redundantMatches = mutableListOf<R>()

    fun compile(match: Match<R>): PatternCompilationResult<R> {
        for (m in match) {
            redundantMatches += m.rhs
        }

        fail(Term.Neg(listOf()), match)
        return PatternCompilationResult(!inexhaustive, redundantMatches)
    }

    /*
     * The main algorithm. Defined by 3 functions:
     * fail
     * succeed
     * match
     */

    private fun fail(dsc: Term, rules: Match<R>): Decision<R> = when (val m = rules.match()) {
        is ListMatch.Nil -> {
            inexhaustive = true
            Decision.Failure
        }
        is ListMatch.HT -> match(m.head.pat, Access.Obj, dsc, listOf(), listOf(), m.head.rhs, m.tail)
    }

    private fun succeed(ctx: Context, work: List<Work>, rhs: R, rules: Match<R>): Decision<R> {
        return when (val wo = work.match()) {
            is ListMatch.Nil -> {
                redundantMatches.remove(rhs)
                Decision.Success(rhs)
            }
            is ListMatch.HT -> {
                val work1 = wo.head
                val workr = wo.tail
                if (work1.pats.isEmpty() && work1.objs.isEmpty() && work1.dscs.isEmpty()) {
                    succeed(norm(ctx), workr, rhs, rules)
                } else {
                    val (pat1, patr) = work1.pats.headTail()
                    val (obj1, objr) = work1.objs.headTail()
                    val (dsc1, dscr) = work1.dscs.headTail()
                    match(pat1, obj1, dsc1, ctx, workr.prepend(Work(patr, objr, dscr)), rhs, rules)
                }
            }
        }
    }

    private fun match(
        pat: Pat,
        obj: Access,
        dsc: Term,
        ctx: Context,
        work: List<Work>,
        rhs: R,
        rules: Match<R>
    ): Decision<R> {
        return when (pat) {
            is Pat.PVar -> succeed(augment(ctx, dsc), work, rhs, rules)
            is Pat.PCon -> {
                val pcon = pat.con
                fun <B> args(f: (Int) -> B) = (0 until pcon.arity).map(f)

                fun getdargs(t: Term): List<Term> = when (t) {
                    is Term.Neg -> args { Term.Neg(listOf()) }
                    is Term.Pos -> t.terms
                }

                fun getoargs(): List<Access> = args { i -> Access.Sel(i + 1, obj) }

                fun succeed1() = succeed(
                    ctx.prepend(ContextElem(pcon, listOf())),
                    work.prepend(Work(pat.pats, getoargs(), getdargs(dsc))),
                    rhs, rules
                )

                fun fail1(newdsc: Term) = fail(builddsc(ctx, newdsc, work), rules)

                when (staticMatch(pcon, dsc)) {
                    is MatchRes.Yes -> succeed1()
                    is MatchRes.No -> fail1(dsc)
                    is MatchRes.Maybe -> Decision.IfEq(obj, pcon, succeed1(), fail1(addneg(dsc, pcon)))
                }
            }
        }
    }

    // Helpers

    sealed class MatchRes {
        object Yes : MatchRes()
        object No : MatchRes()
        object Maybe : MatchRes()
    }

    private fun staticMatch(pcon: Ctor, dsc: Term): MatchRes = when (dsc) {
        is Term.Pos -> {
            if (dsc.con.name == pcon.name) MatchRes.Yes
            else MatchRes.No
        }
        is Term.Neg -> {
            when {
                dsc.cons.any { it == pcon } -> MatchRes.No
                pcon.span == dsc.cons.size + 1 -> MatchRes.Yes
                else -> MatchRes.Maybe
            }
        }
    }

    private fun augment(ctx: Context, term: Term): Context {
        val head = ctx.firstOrNull() ?: return listOf()
        val tail = ctx.drop(1)
        return listOf(ContextElem(head.con, listOf(term) + head.terms)) + tail
    }

    private fun addneg(term: Term, con: Ctor): Term = when (term) {
        is Term.Neg -> Term.Neg(listOf(con) + term.cons)
        else -> throw RuntimeException("got a non negative term $term")
    }

    private fun norm(ctx: Context): Context {
        val head = ctx.first()
        return augment(ctx.drop(1), Term.Pos(head.con, head.terms.reversed()))
    }

    private tailrec fun builddsc(ctx: Context, dsc: Term, works: List<Work>): Term {
        if (ctx.isEmpty() && works.isEmpty()) return dsc
        val (ce, rest) = ctx.headTail()
        val (w, work) = works.headTail()
        return builddsc(rest, Term.Pos(ce.con, ce.terms.reversed() + w.dscs.prepend(dsc)), work)
    }

    companion object {
        private val trueCtor = Ctor("true", 0, 2)
        private val falseCtor = Ctor("false", 0, 2)
        private val unitCtor = Ctor("unit", 0, 2)
        private fun mkPrimCtor(name: String) = Ctor(name, 0, Integer.MAX_VALUE)
        private fun mkRecordCtor(arity: Int) = Ctor("record", arity, 1)
        private fun mkVectorCtor(arity: Int) = Ctor("vector", arity, Integer.MAX_VALUE)

        private val ctorCache = mutableMapOf<String, Ctor>()

        fun addConsToCache(mod: Module) {
            for (dd in mod.decls.filterIsInstance<Decl.TypeDecl>()) {
                val span = dd.dataCtors.size
                for (c in dd.dataCtors) {
                    val name = "${mod.name}.${c.name}"
                    if (ctorCache.containsKey(name)) break
                    ctorCache[name] = Ctor(c.name, c.args.size, span)
                }
            }
        }

        fun getFromCache(name: String): Ctor? = ctorCache[name]

        fun convert(c: Case, modName: String): MatchRule<Pattern> =
            MatchRule(convertPattern(c.pattern, modName), c.pattern)

        private fun convertPattern(p: Pattern, modName: String): Pat = when (p) {
            is Pattern.Wildcard -> Pat.PVar("_")
            is Pattern.Var -> Pat.PVar(p.name)
            is Pattern.Ctor -> {
                val name = p.ctor.fullname(modName)
                Pat.PCon(ctorCache[name]!!, p.fields.map { convertPattern(it, modName) })
            }
            is Pattern.LiteralP -> {
                val con = when (val l = p.lit) {
                    is LiteralPattern.BoolLiteral -> if (l.e.v) trueCtor else falseCtor
                    is LiteralPattern.CharLiteral -> mkPrimCtor(l.e.v.toString())
                    is LiteralPattern.StringLiteral -> mkPrimCtor(l.e.v)
                    is LiteralPattern.IntLiteral -> mkPrimCtor(l.e.v.toString())
                    is LiteralPattern.LongLiteral -> mkPrimCtor(l.e.v.toString())
                    is LiteralPattern.FloatLiteral -> mkPrimCtor(l.e.v.toString())
                    is LiteralPattern.DoubleLiteral -> mkPrimCtor(l.e.v.toString())
                }
                Pat.PCon(con, emptyList())
            }
            is Pattern.Record -> {
                val pats = p.labels.values().flatten().map { convertPattern(it, modName) }
                Pat.PCon(mkRecordCtor(p.labels.size().toInt()), pats)
            }
            is Pattern.Vector -> Pat.PCon(mkVectorCtor(p.elems.size), p.elems.map { convertPattern(it, modName) })
            is Pattern.As -> convertPattern(p.pat, modName)
            is Pattern.Unit -> Pat.PCon(unitCtor, emptyList())
        }
    }
}