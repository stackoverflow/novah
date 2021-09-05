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
package novah.frontend

import novah.ast.source.*
import novah.data.Reflection
import novah.data.Reflection.collectType
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.Severity
import novah.frontend.typechecker.*
import novah.main.DeclRef
import novah.main.FullModuleEnv
import novah.main.NovahClassLoader
import novah.main.TypeDeclRef
import novah.ast.source.Type as SType

typealias VarRef = String
typealias ModuleName = String

fun resolveImports(mod: Module, modules: Map<String, FullModuleEnv>, env: Env): List<CompilerProblem> {
    val visible = { (_, tvis): Map.Entry<String, DeclRef> -> tvis.visibility == Visibility.PUBLIC }
    val visibleType = { (_, tvis): Map.Entry<String, TypeDeclRef> -> tvis.visibility == Visibility.PUBLIC }

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, span, mod.sourceName, mod.name.value)
    }

    fun makeWarn(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, span, mod.sourceName, mod.name.value, null, Severity.WARN)
    }

    val resolved = mutableMapOf<VarRef, ModuleName>()
    val resolvedTypealiases = mutableListOf<Decl.TypealiasDecl>()
    val errors = mutableListOf<CompilerProblem>()
    for (imp in mod.imports) {
        val mkError = makeError(imp.span())
        val mkWarn = makeWarn(imp.span())
        val mname = imp.module.value
        val m = if (mname == PRIM) primModuleEnv else modules[mname]?.env
        if (m == null) {
            errors += mkError(Errors.moduleNotFound(mname))
            continue
        }
        val typealiases = modules[mname]?.aliases?.associate { it.name to it } ?: emptyMap()
        when (imp) {
            // Import all declarations, types and type aliases from this module
            is Import.Raw -> {
                if (warnOnRawImport(imp)) errors += mkWarn(Errors.IMPORT_RAW)
                val alias = if (imp.alias != null) "${imp.alias}." else ""
                m.types.filter(visibleType).forEach { name, (type, _) ->
                    resolved["$alias$name"] = mname
                    env.extendType("$mname.$name", type)
                }
                m.decls.filter(visible).forEach { name, (type, _, isInstance) ->
                    resolved["$alias$name"] = mname
                    env.extend("$mname.$name", type)
                    if (isInstance) env.extendInstance("$mname.$name", type)
                }
                typealiases.filter { (_, ta) -> ta.visibility == Visibility.PUBLIC }.forEach { (_, ta) ->
                    resolvedTypealiases += ta
                }
            }
            // Import only defined declarations and types
            is Import.Exposing -> {
                val alias = if (imp.alias != null) "${imp.alias}." else ""
                for (ref in imp.defs) {
                    when (ref) {
                        is DeclarationRef.RefVar -> {
                            val declRef = m.decls[ref.name]
                            if (declRef == null) {
                                errors += mkError(Errors.cannotFindInModule("declaration ${ref.name}", mname))
                                continue
                            }
                            if (declRef.visibility == Visibility.PRIVATE) {
                                errors += mkError(Errors.cannotImportInModule("declaration ${ref.name}", mname))
                                continue
                            }
                            val fname = "$mname.${ref.name}"
                            resolved["$alias${ref.name}"] = mname
                            env.extend(fname, declRef.type)
                            if (declRef.isInstance) env.extendInstance(fname, declRef.type)
                        }
                        is DeclarationRef.RefType -> {
                            val talias = typealiases[ref.name]
                            if (talias != null) {
                                if (talias.visibility == Visibility.PRIVATE) {
                                    errors += mkError(Errors.cannotImportInModule("type ${ref.name}", mname))
                                    continue
                                }
                                resolvedTypealiases += talias
                                continue
                            }
                            val declRef = m.types[ref.name]
                            if (declRef == null) {
                                errors += mkError(Errors.cannotFindInModule("type ${ref.name}", mname))
                                continue
                            }
                            if (declRef.visibility == Visibility.PRIVATE) {
                                errors += mkError(Errors.cannotImportInModule("type ${ref.name}", mname))
                                continue
                            }
                            env.extendType("$mname.${ref.name}", declRef.type)
                            resolved["$alias${ref.name}"] = mname
                            when {
                                ref.ctors == null -> {
                                }
                                ref.ctors.isEmpty() -> {
                                    for (ctor in declRef.ctors) {
                                        val ctorDecl = m.decls[ctor]!! // cannot fail
                                        if (ctorDecl.visibility == Visibility.PRIVATE) {
                                            errors += mkError(Errors.cannotImportInModule("constructor $ctor", mname))
                                            continue
                                        }
                                        env.extend("$mname.$ctor", ctorDecl.type)
                                        resolved["$alias$ctor"] = mname
                                    }
                                }
                                else -> {
                                    for (ctor in ref.ctors) {
                                        val name = ctor.value
                                        val ctorDecl = m.decls[ctor.value]
                                        if (ctorDecl == null) {
                                            errors += mkError(Errors.cannotFindInModule("constructor $name", mname))
                                            continue
                                        }
                                        if (ctorDecl.visibility == Visibility.PRIVATE) {
                                            errors += mkError(Errors.cannotImportInModule("constructor $name", mname))
                                            continue
                                        }
                                        env.extend("$mname.$name", ctorDecl.type)
                                        resolved["$alias$name"] = mname
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    mod.resolvedImports = resolved
    mod.resolvedTypealiases = resolvedTypealiases
    return errors
}

/**
 * Resolves all java imports in this module.
 */
fun resolveForeignImports(mod: Module, cl: NovahClassLoader, tc: Typechecker): List<CompilerProblem> {
    val errors = mutableListOf<CompilerProblem>()

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, span, mod.sourceName, mod.name.value)
    }

    val typealiases = mutableMapOf<String, String>()

    for (type in mod.foreigns) {
        val fqType = type.type
        val clazz = cl.safeFindClass(fqType)
        if (clazz == null) {
            errors += makeError(type.span)(Errors.classNotFound(fqType))
            continue
        }

        tc.env.extendType(fqType, collectType(tc, clazz))
        if (type.alias != null) {
            if (mod.resolvedImports.containsKey(type.alias))
                errors += makeError(type.span)(Errors.duplicatedImport(type.alias))
            typealiases[type.alias] = fqType
        } else {
            val alias = fqType.split('.').last()
            if (mod.resolvedImports.containsKey(alias))
                errors += makeError(type.span)(Errors.duplicatedImport(alias))
            typealiases[alias] = fqType
        }
    }
    typealiases.putAll(resolveImportedTypealiases(mod.resolvedTypealiases))

    mod.foreignTypes = typealiases
    return errors
}

private fun resolveImportedTypealiases(tas: List<Decl.TypealiasDecl>): Map<String, String> {
    return tas.mapNotNull { ta ->
        if (ta.expanded != null) {
            val name = ta.expanded!!.simpleName()
            if (name != null) ta.name to Reflection.novahToJava(name)
            else null
        } else null
    }.toMap()
}

/**
 * Makes sure all types used by a public
 * type alias are also public
 */
fun validatePublicAliases(ast: Module): List<CompilerProblem> {
    val errors = mutableListOf<CompilerProblem>()
    ast.decls.filterIsInstance<Decl.TypealiasDecl>().filter { it.visibility == Visibility.PUBLIC }.forEach { ta ->
        val consts = mutableListOf<String>()
        ta.expanded?.everywhereUnit { t ->
            if (t is SType.TConst) {
                consts += t.name
            }
        }

        val types = ast.decls.filterIsInstance<Decl.TypeDecl>().associateBy { it.name }
        consts.forEach { name ->
            val ty = types[name]
            if (ty != null && ty.visibility == Visibility.PRIVATE) {
                errors += CompilerProblem(
                    Errors.TYPEALIAS_PUB,
                    ta.span,
                    ast.sourceName,
                    ast.name.value
                )
            }
        }
    }
    return errors
}

private val allowedRawModules = setOf(PRIM, CORE_MODULE, TEST_MODULE, COMPUTATION_MODULE)

private fun warnOnRawImport(imp: Import.Raw): Boolean =
    imp.alias == null && imp.module.value !in allowedRawModules