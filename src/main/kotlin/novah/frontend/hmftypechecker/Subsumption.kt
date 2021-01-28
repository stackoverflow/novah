package novah.frontend.hmftypechecker

import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext

class Subsumption(private val tc: Typechecker, private val uni: Unification) {

    fun subsume(level: Level, t1: Type, t2: Type) {
        val instT2 = tc.instantiate(level, t2)
        val unt1 = t1.unlink()
        if (unt1 is Type.TForall) {
            val genVars = unt1.ids.reversed().map { tc.newGenVar() }
            val genT1 = substituteBoundVars(unt1.ids, genVars, unt1.type)
            uni.unify(genT1, instT2)
            if (escapeCheck(genVars, t1, t2)) {
                inferError(Errors.typeIsNotInstance(t2.show(), t1.show()), Span.empty(), ProblemContext.SUBSUMPTION)
            }
        } else {
            uni.unify(unt1, instT2)
        }
    }
}