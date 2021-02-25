package novah.frontend.typechecker

import novah.ast.canonical.Module
import novah.data.Err
import novah.data.Ok
import novah.data.Result
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.main.CompilationError
import novah.main.ModuleEnv

object Typechecker {
    private var ids = mutableMapOf<String, Int>()

    fun resetIds() {
        ids.clear()
    }

    fun freshName(name: String): String {
        val next = ids[name] ?: 0
        ids[name] = next + 1
        return "$name\$$next"
    }

    fun infer(ctx: Context, mod: Module): Result<ModuleEnv, List<CompilerProblem>> {
        return try {
            Ok(Inference.infer(ctx, mod))
        } catch (ie: InferenceError) {
            Err(listOf(CompilerProblem(ie.msg, ie.ctx, ie.span, mod.sourceName, mod.name)))
        } catch (ce: CompilationError) {
            Err(ce.problems)
        }
    }
}

class InferenceError(val msg: String, val span: Span, val ctx: ProblemContext) : RuntimeException(msg)

fun inferError(msg: String, span: Span, ctx: ProblemContext = ProblemContext.TYPECHECK): Nothing =
    throw InferenceError(msg, span, ctx)