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
package novah.frontend.hmftypechecker

import novah.ast.canonical.Module
import novah.data.Err
import novah.data.Ok
import novah.data.Result
import novah.data.forEachList
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.frontend.hmftypechecker.Unification.substituteBoundVars
import novah.main.CompilationError
import novah.main.ModuleEnv

object Typechecker {
    private var currentId = 0

    var env = Env.new()

    private fun nextId(): Int = ++currentId
    fun resetId() {
        currentId = 0
    }

    fun resetEnv() {
        env = Env.new()
    }

    fun newVar(level: Level) = TVar(TypeVar.Unbound(nextId(), level))
    fun newGenVar() = TVar(TypeVar.Generic(nextId()))
    fun newBoundVar(): Pair<Id, TVar> {
        val id = nextId()
        return id to TVar(TypeVar.Bound(id))
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

    private fun substituteWithNewVars(level: Level, ids: List<Id>, type: Type): Pair<List<Type>, Type> {
        val vars = ids.reversed().map { newVar(level) }
        return vars to substituteBoundVars(ids, vars, type)
    }

    tailrec fun instantiate(level: Level, type: Type): Type = when {
        type is TForall -> {
            val (_, instType) = substituteWithNewVars(level, type.ids, type.type)
            instType
        }
        type is TVar && type.tvar is TypeVar.Link -> instantiate(level, (type.tvar as TypeVar.Link).type)
        else -> type
    }

    fun instantiateTypeAnnotation(level: Level, ids: List<Id>, type: Type): Pair<List<Type>, Type> {
        return if (ids.isEmpty()) emptyList<Type>() to type
        else substituteWithNewVars(level, ids, type)
    }

    fun checkWellFormed(ty: Type, span: Span) {
        when (ty) {
            is TConst -> {
                val envType = env.lookupType(ty.name) ?: inferError(Errors.undefinedType(ty.show()), span)

                if (ty.kind != envType.kind()) {
                    inferError(Errors.wrongKind(ty.kind.toString(), envType.kind().toString()), span)
                }
            }
            is TApp -> {
                checkWellFormed(ty.type, span)
                ty.types.forEach { checkWellFormed(it, span) }
            }
            is TArrow -> {
                ty.args.forEach { checkWellFormed(it, span) }
                checkWellFormed(ty.ret, span)
            }
            is TForall -> checkWellFormed(ty.type, span)
            is TVar -> {
                when (val tv = ty.tvar) {
                    is TypeVar.Link -> checkWellFormed(tv.type, span)
                    is TypeVar.Generic -> inferError(Errors.unusedVariables(listOf(ty.show())), span)
                    is TypeVar.Unbound -> inferError(Errors.unusedVariables(listOf(ty.show())), span)
                }
            }
            is TRecord -> checkWellFormed(ty.row, span)
            is TRowExtend -> {
                checkWellFormed(ty.row, span)
                ty.labels.forEachList { checkWellFormed(it, span) }
            }
            is TImplicit -> checkWellFormed(ty.type, span)
            is TRowEmpty -> {
            }
        }
    }
}

class InferenceError(val msg: String, val span: Span, val ctx: ProblemContext) : RuntimeException(msg)

fun inferError(msg: String, span: Span, ctx: ProblemContext = ProblemContext.TYPECHECK): Nothing =
    throw InferenceError(msg, span, ctx)