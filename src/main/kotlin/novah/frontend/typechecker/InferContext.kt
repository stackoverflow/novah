package novah.frontend.typechecker

import novah.data.Result
import novah.data.Ok
import novah.data.Err
import novah.ast.canonical.Module
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.main.CompilationError
import novah.main.ModuleEnv
import java.util.ArrayDeque

class InferenceError(val msg: String, val span: Span, val ctx: ProblemContext) : RuntimeException(msg)

fun inferError(msg: String, span: Span, ctx: ProblemContext = ProblemContext.TYPECHECK): Nothing =
    throw InferenceError(msg, span, ctx)

class InferContext {
    var context = Context()
    private val store = GenNameStore()

    private val wellFormed = WellFormed(store, this)
    private val subsumption = Subsumption(wellFormed, store, this)
    private val infer = Inference(subsumption, wellFormed, store, this)

    private val ctxStack = ArrayDeque<Context>()

    fun infer(mod: Module): Result<ModuleEnv, List<CompilerProblem>> {
        return try {
            Ok(infer.infer(mod))
        } catch (ie: InferenceError) {
            Err(listOf(CompilerProblem(ie.msg, ie.ctx, ie.span, mod.sourceName, mod.name)))
        } catch (ce: CompilationError) {
            Err(ce.problems)
        }
    }

    fun store() {
        ctxStack.push(context.clone())
    }

    fun restore() {
        context = if (ctxStack.isNotEmpty()) ctxStack.pop() else context
    }

    fun discard() {
        if (ctxStack.isNotEmpty()) ctxStack.pop()
    }

    fun apply(type: Type, ctx: Context = context): Type {
        return when (type) {
            is Type.TVar -> type
            is Type.TMeta -> {
                val t = ctx.lookup<Elem.CTMeta>(type.name)
                if (t?.type != null) apply(t.type, ctx) else type
            }
            is Type.TFun -> {
                val left = apply(type.arg, ctx)
                val right = apply(type.ret, ctx)
                if (type.arg == left && type.ret == right) {
                    type
                } else {
                    Type.TFun(left, right)
                }
            }
            is Type.TForall -> {
                val body = apply(type.type, ctx)
                if (type.type == body) type else Type.TForall(type.name, body)
            }
            is Type.TConstructor -> {
                val body = type.types.map { apply(it, ctx) }
                if (type.types == body) type else Type.TConstructor(type.name, body)
            }
        }
    }
}