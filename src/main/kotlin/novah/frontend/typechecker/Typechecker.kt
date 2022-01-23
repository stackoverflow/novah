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
import novah.frontend.error.Severity
import novah.main.CompilationError
import novah.main.ModuleEnv
import novah.main.NovahClassLoader
import java.util.*

class Typechecker(classLoader: NovahClassLoader) {
    private var currentId = 0

    val env = Env.new()

    var context: TypingContext? = null
        private set

    val uni = Unification(this)
    val infer = Inference(this, classLoader)
    
    /**
     * A map of the internal type variable ids to
     * user given type variable names.
     */
    private val typeVarMap = mutableMapOf<Int, String>()

    private fun nextId(): Int = ++currentId

    fun newVar(level: Level) = TVar(TypeVar.Unbound(nextId(), level))
    fun newGenVar(name: String? = null): TVar {
        val id = nextId()
        if (name != null) typeVarMap[id] = name
        return TVar(TypeVar.Generic(id))
    }

    fun typeVars() = typeVarMap.toMap()

    fun infer(mod: Module): Result<ModuleEnv, Set<CompilerProblem>> {
        context = TypingContext(mod)
        return try {
            Ok(infer.infer(mod))
        } catch (ie: InferenceError) {
            val sev = Severity.FATAL
            val err = CompilerProblem(ie.msg, ie.span, mod.sourceName, mod.name.value, context?.copy(), severity = sev)
            Err(setOf(err))
        } catch (ce: CompilationError) {
            Err(ce.problems.map { it.copy(typingContext = context?.copy(), severity = Severity.FATAL) }.toSet())
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
                    is TypeVar.Unbound -> inferError(Errors.unusedVariable(ty.show()), span)
                    else -> {}
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
) {
    fun copy() = TypingContext(mod, decl, types.clone())
}

class InferenceError(val msg: String, val span: Span) : RuntimeException(msg)

fun inferError(msg: String, span: Span): Nothing = throw InferenceError(msg, span)