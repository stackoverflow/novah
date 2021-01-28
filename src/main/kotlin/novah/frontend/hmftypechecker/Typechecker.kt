package novah.frontend.hmftypechecker

import novah.frontend.Span
import novah.frontend.error.ProblemContext

class Typechecker {
    private var currentId = 0
    
    val env = Env()
    
    private fun nextId(): Int = ++currentId
    fun resetId() {
        currentId = 0
    }
    
    fun newVar(level: Level) = Type.TVar(TypeVar.Unbound(nextId(), level))
    fun newGenVar() = Type.TVar(TypeVar.Generic(nextId()))
    fun newBoundVar(): Pair<Id, Type.TVar> {
        val id = nextId()
        return id to Type.TVar(TypeVar.Bound(id))
    }

    fun substituteWithNewVars(level: Level, ids: List<Id>, type: Type): Pair<List<Type>, Type> {
        val vars = ids.reversed().map { newVar(level) }
        return vars to substituteBoundVars(ids, vars, type)
    }
    
    tailrec fun instantiate(level: Level, type: Type): Type = when {
        type is Type.TForall -> {
            val (_, instType) = substituteWithNewVars(level, type.ids, type.type)
            instType
        }
        type is Type.TVar && type.tvar is TypeVar.Link -> instantiate(level, (type.tvar as TypeVar.Link).type)
        else -> type
    }
    
    fun instantiateTypeAnnotation(level: Level, ids: List<Id>, type: Type): Pair<List<Type>, Type> {
        return if (ids.isEmpty()) emptyList<Type>() to type
        else substituteWithNewVars(level, ids, type)
    }
    
    /*
    let instantiate_ty_ann level = function
	| [], ty -> [], ty
	| var_id_list, ty -> substitute_with_new_vars level var_id_list ty
     */
}

class InferenceError(val msg: String, val span: Span, val ctx: ProblemContext) : RuntimeException(msg)

fun inferError(msg: String, span: Span, ctx: ProblemContext = ProblemContext.TYPECHECK): Nothing =
    throw InferenceError(msg, span, ctx)