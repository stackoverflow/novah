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

import novah.data.mapList
import novah.frontend.Span
import novah.frontend.error.Errors
import novah.frontend.typechecker.Typechecker.context
import novah.frontend.typechecker.Typechecker.freshName

object WellFormed {

    fun wfType(t: Type, span: Span) {
        when (t) {
            is TConst -> {
                if (context.lookup<CTVar>(t.name) == null) inferError(Errors.undefinedVar(t.name), span)
            }
            is TVar -> {
                if (context.lookup<CTVar>(t.name) == null) inferError(Errors.undefinedVar(t.name), span)
            }
            is TMeta -> {
                if (context.lookup<CTMeta>(t.name) == null) inferError(Errors.undefinedVar(t.name), span)
            }
            is TArrow -> {
                wfType(t.left, span)
                wfType(t.right, span)
            }
            is TApp -> {
                wfType(t.left, span)
                wfType(t.right, span)
            }
            is TForall -> {
                val fork = context.fork()
                val x = freshName(t.name)
                context.append(CTVar(x))
                wfType(Type.instantiateForall(t, TVar(x)), span)
                context.resetTo(fork)
            }
            is TRecord -> wfType(t.row, span)
            is TRowEmpty -> {}
            is TRowExtend -> {
                wfType(t.row, span)
                t.labels.mapList { wfType(it, span) }
            }
        }
    }

    private fun wfElem(ctx: Context, elem: Elem): String? {
        when {
            elem is CTVar && ctx.contains<CTVar>(elem.name) -> return elem.name
            elem is CTMeta && ctx.contains<CTMeta>(elem.name) -> return elem.name
            elem is CVar && ctx.contains<CVar>(elem.name) -> return elem.name
        }
        return null
    }

    fun wfContext(): List<String> {
        val errors = mutableListOf<String>()
        val ctx = context.fork()
        while (ctx.size() > 0) {
            val elem = ctx.removeLast()
            val name = wfElem(ctx, elem)
            if (name != null) errors += name
        }
        return errors
    }
}