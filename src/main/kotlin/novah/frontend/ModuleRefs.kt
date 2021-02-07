package novah.frontend

import novah.Util.internalError
import novah.ast.source.*
import novah.ast.source.Type as SType
import novah.data.NovahClassLoader
import novah.data.Reflection
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.frontend.hmftypechecker.*
import novah.frontend.hmftypechecker.Type
import novah.main.DeclRef
import novah.main.FullModuleEnv
import novah.main.TypeDeclRef
import java.lang.reflect.Constructor
import java.lang.reflect.Method

typealias VarRef = String
typealias ModuleName = String

fun resolveImports(mod: Module, tc: Typechecker, modules: Map<String, FullModuleEnv>): List<CompilerProblem> {
    val visible = { (_, tvis): Map.Entry<String, DeclRef> -> tvis.visibility == Visibility.PUBLIC }
    val visibleType = { (_, tvis): Map.Entry<String, TypeDeclRef> -> tvis.visibility == Visibility.PUBLIC }

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, ProblemContext.IMPORT, span, mod.sourceName, mod.name)
    }

    val env = tc.env
    val resolved = mutableMapOf<VarRef, ModuleName>()
    val resolvedTypealiases = mutableListOf<Decl.TypealiasDecl>()
    val errors = mutableListOf<CompilerProblem>()
    // add the primitive module as import to every module
    val imports = mod.imports + primImport
    for (imp in imports) {
        val mkError = makeError(imp.span())
        val m = if (imp.module == "prim") primModuleEnv else modules[imp.module]?.env
        if (m == null) {
            errors += mkError(Errors.moduleNotFound(imp.module))
            continue
        }
        val typealiases = modules[imp.module]?.aliases?.map { it.name to it }?.toMap() ?: emptyMap()
        val mname = imp.module
        when (imp) {
            // Import all declarations, types and type aliases from this module
            is Import.Raw -> {
                val alias = if (imp.alias != null) "${imp.alias}." else ""
                m.types.filter(visibleType).forEach { name, (type, _) ->
                    resolved["$alias$name"] = mname
                    env.extendType("$mname.$name", type)
                }
                m.decls.filter(visible).forEach { name, (type, _) ->
                    resolved["$alias$name"] = mname
                    env.extend("$alias$name", type)
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
                            resolved["$alias${ref.name}"] = mname
                            env.extend("$alias${ref.name}", declRef.type)
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
                                        env.extend("$alias$ctor", ctorDecl.type)
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
                                        env.extend("$alias$ctor", ctorDecl.type)
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
fun resolveForeignImports(mod: Module, tc: Typechecker): List<CompilerProblem> {
    // TODO: pass the real classpath here, for now it only works for stdlib types
    val cl = NovahClassLoader("")
    val errors = mutableListOf<CompilerProblem>()

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, ProblemContext.FOREIGN_IMPORT, span, mod.sourceName, mod.name)
    }

    val typealiases = mutableMapOf<String, String>()
    val foreigVars = mutableMapOf<String, ForeignRef>()
    val env = tc.env
    val (types, foreigns) = mod.foreigns.partition { it is ForeignImport.Type }
    for (type in (types as List<ForeignImport.Type>)) {
        val fqType = type.type
        if (cl.safeLoadClass(fqType) == null) {
            errors += makeError(type.span)(Errors.classNotFound(fqType))
            continue
        }
        env.extendType(fqType, Type.TConst(fqType))
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
                    Type.TConst(javaToNovah(field.type.canonicalName))
                } else {
                    Type.TArrow(listOf(Type.TConst(type)), Type.TConst(javaToNovah(field.type.canonicalName)))
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
                val parType = Type.TConst(javaToNovah(field.type.canonicalName))
                val ctxType = if (imp.static) {
                    Type.TArrow(listOf(parType), tUnit)
                } else {
                    Type.TArrow(listOf(Type.TConst(type), parType), tUnit)
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

private fun methodTofunction(m: Method, type: String, static: Boolean, novahPars: List<String>): Type {
    val mpars = mutableListOf<String>()
    if (!static) mpars += type // `this` is always first paramenter of non-static methods

    if (static && m.parameterTypes.isEmpty()) mpars += primUnit
    else mpars.addAll(novahPars.map { Reflection.novahToJava(it) })

    mpars += if (m.returnType.canonicalName == "void") primUnit else m.returnType.canonicalName
    val pars = mpars.map { javaToNovah(it) }
    val tpars = pars.map { Type.TConst(it) } as List<Type>

    return tpars.reduceRight { tVar, acc -> Type.TArrow(listOf(tVar), acc) }
}

private fun ctorToFunction(c: Constructor<*>, type: String, novahPars: List<String>): Type {
    val mpars = if (c.parameterTypes.isEmpty()) listOf(primUnit) else novahPars.map { Reflection.novahToJava(it) }
    val pars = (mpars + type).map { javaToNovah(it) }
    val tpars = pars.map { Type.TConst(it) } as List<Type>

    return tpars.reduceRight { tVar, acc -> Type.TArrow(listOf(tVar), acc) }
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

        val types = ast.decls.filterIsInstance<Decl.DataDecl>().map { it.name to it }.toMap()
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