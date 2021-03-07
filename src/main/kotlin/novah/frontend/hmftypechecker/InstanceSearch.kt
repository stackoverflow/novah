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

import novah.ast.canonical.Expr
import novah.frontend.error.Errors

object InstanceSearch {

    fun instanceSearch(apps: List<Expr.App>) {
        for (app in apps) {
            for (imp in app.implicitContexts) {
                var exp: Expr? = null
                imp.env.forEachInstance { name, type ->
                    val found = try {
                        Unification.unify(type, imp.type, app.span)
                        true
                    } catch (_: InferenceError) {
                        false
                    }
                    if (found) {
                        if (exp != null)
                            inferError(Errors.duplicatedInstance(imp.type.show()), app.span)
                        else {
                            // TODO: what about imported/aliased vars?
                            exp = Expr.Var(name, app.span).apply { this.type = type }
                        }
                    }

                }
                if (exp == null) inferError(Errors.noInstanceFound(imp.type.show()), app.span)
                imp.resolved = exp
                //println("resolved instance " + imp.type.show(false) + " with expression $exp")
            }
        }
    }
}