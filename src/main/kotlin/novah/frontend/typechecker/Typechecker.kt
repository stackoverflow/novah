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

import novah.ast.canonical.Decl
import novah.ast.canonical.Module
import novah.data.*
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.main.CompilationError
import novah.main.ModuleEnv
import java.util.*

object Typechecker {
    private var currentId = 0

    var env = Env.new()
        private set

    var context: TypingContext? = null
        private set

    private fun nextId(): Int = ++currentId

    fun reset() {
        env = Env.new()
        currentId = 0
    }

    fun newVar(level: Level) = TVar(TypeVar.Unbound(nextId(), level))
    fun newGenVar() = TVar(TypeVar.Generic(nextId()))

    fun infer(mod: Module): Result<ModuleEnv, List<CompilerProblem>> {
        context = TypingContext(mod)
        return try {
            Ok(Inference.infer(mod))
        } catch (ie: InferenceError) {
            Err(listOf(CompilerProblem(ie.msg, ie.ctx, ie.span, mod.sourceName, mod.name.value, context)))
        } catch (ce: CompilationError) {
            Err(ce.problems.map { it.copy(typingContext = context) })
        }
    }

    fun instantiate(level: Level, type: Type): Type {
        val idVarMap = mutableMapOf<Id, Type>()
        fun f(ty: Type): Type = when (ty) {
            is TConst -> ty
            is TVar -> {
                when (val tv = ty.tvar) {
                    is TypeVar.Link -> f(tv.type)
                    is TypeVar.Generic -> {
                        val v = idVarMap[tv.id]
                        if (v != null) v
                        else {
                            val va = newVar(level)
                            idVarMap[tv.id] = va
                            va
                        }
                    }
                    is TypeVar.Unbound -> ty
                }
            }
            is TApp -> ty.copy(type = f(ty.type), types = ty.types.map(::f))
            is TArrow -> ty.copy(args = ty.args.map(::f), ret = f(ty.ret))
            is TImplicit -> ty.copy(f(ty.type))
            is TRecord -> ty.copy(f(ty.row))
            is TRowEmpty -> ty
            is TRowExtend -> ty.copy(labels = ty.labels.mapList(::f), row = f(ty.row))
        }
        return f(type)
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
            is TVar -> {
                when (val tv = ty.tvar) {
                    is TypeVar.Link -> checkWellFormed(tv.type, span)
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

class TypingContext(
    val mod: Module,
    var decl: Decl.ValDecl? = null,
    val types: ArrayDeque<Type> = ArrayDeque()
)

class InferenceError(val msg: String, val span: Span, val ctx: ProblemContext) : RuntimeException(msg)

fun inferError(msg: String, span: Span, ctx: ProblemContext = ProblemContext.TYPECHECK): Nothing =
    throw InferenceError(msg, span, ctx)