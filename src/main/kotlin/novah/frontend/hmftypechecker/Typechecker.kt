package novah.frontend.hmftypechecker

import novah.ast.canonical.Module
import novah.data.Err
import novah.data.Ok
import novah.data.Result
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.main.CompilationError
import novah.main.ModuleEnv

class Typechecker {
    private var currentId = 0
    
    var env = Env()
    private val uni = Unification(this)
    private val sub = Subsumption(this, uni)
    private val infer = Inference(this, uni, sub)
    
    private fun nextId(): Int = ++currentId
    fun resetId() {
        currentId = 0
    }
    
    fun resetEnv() {
        env = Env()
    }
    
    fun newVar(level: Level) = Type.TVar(TypeVar.Unbound(nextId(), level))
    fun newGenVar() = Type.TVar(TypeVar.Generic(nextId()))
    fun newBoundVar(): Pair<Id, Type.TVar> {
        val id = nextId()
        return id to Type.TVar(TypeVar.Bound(id))
    }

    fun infer(mod: Module): Result<ModuleEnv, List<CompilerProblem>> {
        return try {
            Ok(infer.infer(mod))
        } catch (ie: InferenceError) {
            Err(listOf(CompilerProblem(ie.msg, ie.ctx, ie.span, mod.sourceName, mod.name)))
        } catch (ce: CompilationError) {
            Err(ce.problems)
        }
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
    
    fun checkWellFormed(ty: Type, span: Span) {
        when (ty) {
            is Type.TConst -> {
                if (env.lookupType(ty.name) == null)
                    inferError(Errors.undefinedType(ty.show(false)), span)
            }
            is Type.TApp -> {
                checkWellFormed(ty.type, span)
                ty.types.forEach { checkWellFormed(it, span) }
            }
            is Type.TArrow -> {
                ty.args.forEach { checkWellFormed(it, span) }
                checkWellFormed(ty.ret, span)
            }
            is Type.TForall -> {
                val type = instantiate(0, ty)
                checkWellFormed(type, span)
            }
            is Type.TVar -> {
                when (val tv = ty.tvar) {
                    is TypeVar.Link -> checkWellFormed(tv.type, span)
                }
            }
        }
    }
}

class InferenceError(val msg: String, val span: Span, val ctx: ProblemContext) : RuntimeException(msg)

fun inferError(msg: String, span: Span, ctx: ProblemContext = ProblemContext.TYPECHECK): Nothing =
    throw InferenceError(msg, span, ctx)