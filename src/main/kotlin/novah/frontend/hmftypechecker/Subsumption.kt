package novah.frontend.hmftypechecker

import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext

class Subsumption(private val tc: Typechecker, private val uni: Unification) {

    fun subsume(level: Level, t1: Type, t2: Type, span: Span) {
        val instT2 = tc.instantiate(level, t2)
        val unt1 = t1.unlink()
        if (unt1 is Type.TForall) {
            val genVars = unt1.ids.map { tc.newGenVar() }.reversed()
            val genT1 = substituteBoundVars(unt1.ids, genVars, unt1.type)
            uni.unify(genT1, instT2, span)
            if (escapeCheck(genVars, unt1, t2)) {
                inferError(Errors.typeIsNotInstance(t2.show(false), unt1.show(false)), span, ProblemContext.SUBSUMPTION)
            }
        } else {
            uni.unify(unt1, instT2, span)
        }
    }
}