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

    val context = Context.new()

    fun resetIds() {
        ids.clear()
    }

    fun resetContext() {
        context.resetTo(Context.new())
    }

    private val pat = Regex("\\\$.*")

    fun freshName(name: String): String {
        val rname = name.replace(pat, "")
        val next = ids[rname] ?: 0
        ids[rname] = next + 1
        return "$rname\$$next"
    }

    fun infer(mod: Module): Result<ModuleEnv, List<CompilerProblem>> {
        return try {
            Ok(Inference.infer(mod))
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