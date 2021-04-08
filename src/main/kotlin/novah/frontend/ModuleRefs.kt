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

import novah.Core
import novah.Util.internalError
import novah.ast.source.*
import novah.data.Reflection
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.frontend.error.Severity
import novah.frontend.typechecker.*
import novah.frontend.typechecker.Type
import novah.main.DeclRef
import novah.main.Environment
import novah.main.FullModuleEnv
import novah.main.TypeDeclRef
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import novah.ast.source.Type as SType

typealias VarRef = String
typealias ModuleName = String

fun resolveImports(mod: Module, modules: Map<String, FullModuleEnv>): List<CompilerProblem> {
    val visible = { (_, tvis): Map.Entry<String, DeclRef> -> tvis.visibility == Visibility.PUBLIC }
    val visibleType = { (_, tvis): Map.Entry<String, TypeDeclRef> -> tvis.visibility == Visibility.PUBLIC }

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, ProblemContext.IMPORT, span, mod.sourceName, mod.name)
    }

    fun makeWarn(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, ProblemContext.IMPORT, span, mod.sourceName, mod.name, null, Severity.WARN)
    }

    val env = Typechecker.env
    val resolved = mutableMapOf<VarRef, ModuleName>()
    val resolvedTypealiases = mutableListOf<Decl.TypealiasDecl>()
    val errors = mutableListOf<CompilerProblem>()
    for (imp in mod.imports) {
        val mkError = makeError(imp.span())
        val mkWarn = makeWarn(imp.span())
        val m = if (imp.module == PRIM) primModuleEnv else modules[imp.module]?.env
        if (m == null) {
            errors += mkError(Errors.moduleNotFound(imp.module))
            continue
        }
        val typealiases = modules[imp.module]?.aliases?.associate { it.name to it } ?: emptyMap()
        val mname = imp.module
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
                                        val ctorDecl = m.decls[ctor]
                                        if (ctorDecl == null) {
                                            errors += mkError(Errors.cannotFindInModule("constructor $ctor", mname))
                                            continue
                                        }
                                        if (ctorDecl.visibility == Visibility.PRIVATE) {
                                            errors += mkError(Errors.cannotImportInModule("constructor $ctor", mname))
                                            continue
                                        }
                                        env.extend("$mname.$ctor", ctorDecl.type)
                                        resolved["$alias$ctor"] = mname
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
@Suppress("UNCHECKED_CAST")
fun resolveForeignImports(mod: Module): List<CompilerProblem> {
    val cl = Environment.classLoader()
    val errors = mutableListOf<CompilerProblem>()

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, ProblemContext.FOREIGN_IMPORT, span, mod.sourceName, mod.name)
    }

    val typealiases = mutableMapOf<String, String>()
    val foreigVars = mutableMapOf<String, ForeignRef>()
    val env = Typechecker.env
    addUnsafeCoerce(foreigVars, env)
    
    val (types, foreigns) = mod.foreigns.partition { it is ForeignImport.Type }
    for (type in (types as List<ForeignImport.Type>)) {
        val fqType = type.type
        if (cl.safeLoadClass(fqType) == null) {
            errors += makeError(type.span)(Errors.classNotFound(fqType))
            continue
        }
        env.extendType(fqType, TConst(fqType))
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

    for (imp in foreigns) {
        val error = makeError(imp.span)
        val type = typealiases[imp.type] ?: Reflection.novahToJava(imp.type)
        val clazz = cl.safeLoadClass(type)
        if (clazz == null) {
            errors += error(Errors.classNotFound(type))
            continue
        }

        when (imp) {
            is ForeignImport.Method -> {
                val pars = imp.pars.map { typealiases[it] ?: it }
                val method = Reflection.findMethod(clazz, imp.name, pars)
                if (method == null) {
                    errors += error(Errors.methodNotFound(imp.name, type))
                    continue
                }
                val isStatic = Reflection.isStatic(method)
                if (imp.static && !isStatic) {
                    errors += error(Errors.nonStaticMethod(imp.name, type))
                    continue
                }
                if (!imp.static && isStatic) {
                    errors += error(Errors.staticMethod(imp.name, type))
                    continue
                }
                val ctxType = methodTofunction(method, type, imp.static, pars)
                val name = imp.alias ?: imp.name
                if (mod.resolvedImports.containsKey(name)) {
                    errors += error(Errors.duplicatedImport(name))
                }
                foreigVars[name] = ForeignRef.MethodRef(method)
                env.extend(name, ctxType)
            }
            is ForeignImport.Ctor -> {
                val pars = imp.pars.map { typealiases[it] ?: it }
                val ctor = Reflection.findConstructor(clazz, pars)
                if (ctor == null) {
                    errors += error(Errors.ctorNotFound(type))
                    continue
                }
                val ctxType = ctorToFunction(ctor, type, pars)
                if (mod.resolvedImports.containsKey(imp.alias)) {
                    errors += error(Errors.duplicatedImport(imp.alias))
                }
                foreigVars[imp.alias] = ForeignRef.CtorRef(ctor)
                env.extend(imp.alias, ctxType)
            }
            is ForeignImport.Getter -> {
                val field = Reflection.findField(clazz, imp.name)
                if (field == null) {
                    errors += error(Errors.fieldNotFound(imp.name, type))
                    continue
                }
                val isStatic = Reflection.isStatic(field)
                if (imp.static && !isStatic) {
                    errors += error(Errors.nonStaticField(imp.name, type))
                    continue
                }
                if (!imp.static && isStatic) {
                    errors += error(Errors.staticField(imp.name, type))
                    continue
                }
                val name = imp.alias ?: imp.name
                if (mod.resolvedImports.containsKey(name)) {
                    errors += error(Errors.duplicatedImport(name))
                }
                foreigVars[name] = ForeignRef.FieldRef(field, false)
                val ctxType = if (imp.static) {
                    toNovahType(javaToNovah(field.type.canonicalName))
                } else {
                    TArrow(listOf(toNovahType(type)), toNovahType(javaToNovah(field.type.canonicalName)))
                }
                env.extend(name, ctxType)
            }
            is ForeignImport.Setter -> {
                val field = Reflection.findField(clazz, imp.name)
                if (field == null) {
                    errors += error(Errors.fieldNotFound(imp.name, type))
                    continue
                }
                val isStatic = Reflection.isStatic(field)
                if (imp.static && !isStatic) {
                    errors += error(Errors.nonStaticField(imp.name, type))
                    continue
                }
                if (!imp.static && isStatic) {
                    errors += error(Errors.staticField(imp.name, type))
                    continue
                }
                if (Reflection.isImutable(field)) {
                    errors += error(Errors.immutableField(imp.name, type))
                    continue
                }
                if (mod.resolvedImports.containsKey(imp.alias)) {
                    errors += error(Errors.duplicatedImport(imp.alias))
                }
                foreigVars[imp.alias] = ForeignRef.FieldRef(field, true)
                val parType = toNovahType(javaToNovah(field.type.canonicalName))
                val ctxType = if (imp.static) {
                    TArrow(listOf(parType), tUnit)
                } else {
                    TArrow(listOf(toNovahType(type), parType), tUnit)
                }
                env.extend(imp.alias, ctxType)
            }
            else -> internalError("Got imported type: ${imp.type}")
        }
    }

    mod.foreignTypes = typealiases
    mod.foreignVars = foreigVars
    return errors
}

private val unsafeCoerce = Core::class.java.methods.find { it.name == "unsafeCoerce" }!!

private fun addUnsafeCoerce(vars: MutableMap<String, ForeignRef>, env: Env) {
    vars["unsafeCoerce"] = ForeignRef.MethodRef(unsafeCoerce)
    env.extend("unsafeCoerce", tUnsafeCoerce)
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

private fun methodTofunction(m: Method, type: String, static: Boolean, novahPars: List<String>): Type {
    val mpars = mutableListOf<String>()
    if (!static) mpars += type // `this` is always first paramenter of non-static methods

    if (static && m.parameterTypes.isEmpty()) mpars += primUnit
    else mpars.addAll(novahPars.map { Reflection.novahToJava(it) })

    mpars += if (m.returnType.canonicalName == "void") primUnit else m.returnType.canonicalName
    val pars = mpars.map { javaToNovah(it) }
    val tpars = pars.map { toNovahType(it) }

    return tpars.reduceRight { tVar, acc -> TArrow(listOf(tVar), acc) }
}

private fun ctorToFunction(c: Constructor<*>, type: String, novahPars: List<String>): Type {
    val mpars = if (c.parameterTypes.isEmpty()) listOf(primUnit) else novahPars.map { Reflection.novahToJava(it) }
    val pars = (mpars + type).map { javaToNovah(it) }
    val tpars = pars.map { toNovahType(it) }

    return tpars.reduceRight { tVar, acc -> TArrow(listOf(tVar), acc) }
}

private fun toNovahType(ty: String): Type = when (ty) {
    primArray, primVector, primSet -> TApp(TConst(ty), listOf(tObject))
    else -> TConst(ty)
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
                    ProblemContext.FOREIGN_IMPORT,
                    ta.span,
                    ast.sourceName,
                    ast.name
                )
            }
        }
    }
    return errors
}

private fun warnOnRawImport(imp: Import.Raw): Boolean =
    imp.alias == null && imp.module != PRIM && imp.module != CORE_MODULE