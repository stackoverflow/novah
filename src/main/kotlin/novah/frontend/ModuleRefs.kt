package novah.frontend

import novah.Util.internalError
import novah.ast.canonical.Visibility
import novah.ast.source.*
import novah.data.NovahClassLoader
import novah.data.Reflection
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.frontend.typechecker.Elem
import novah.frontend.typechecker.InferContext
import novah.frontend.typechecker.Prim
import novah.frontend.typechecker.Prim.javaToNovah
import novah.frontend.typechecker.Prim.tUnit
import novah.frontend.typechecker.Type
import novah.frontend.typechecker.raw
import novah.main.DeclRef
import novah.main.FullModuleEnv
import novah.main.TypeDeclRef
import java.lang.reflect.Constructor
import java.lang.reflect.Method

data class ExportResult(
    val exports: Set<String>,
    val errors: List<String> = listOf()
) {
    fun visibility(value: String) = if (value in exports) Visibility.PUBLIC else Visibility.PRIVATE
}

typealias VarRef = String
typealias ModuleName = String

fun resolveImports(mod: Module, ictx: InferContext, modules: Map<String, FullModuleEnv>): List<CompilerProblem> {
    val visible = { (_, tvis): Map.Entry<String, DeclRef> -> tvis.visibility == Visibility.PUBLIC }
    val visibleType = { (_, tvis): Map.Entry<String, TypeDeclRef> -> tvis.visibility == Visibility.PUBLIC }

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, ProblemContext.IMPORT, span, mod.sourceName, mod.name)
    }

    val ctx = ictx.context
    val resolved = mutableMapOf<VarRef, ModuleName>()
    val errors = mutableListOf<CompilerProblem>()
    // add the primitive module as import to every module
    val imports = mod.imports + Prim.primImport
    for (imp in imports) {
        val mkError = makeError(imp.span())
        val m = if (imp.module == Prim.PRIM) Prim.moduleEnv else modules[imp.module]?.env
        if (m == null) {
            errors += mkError(Errors.moduleNotFound(imp.module))
            continue
        }
        val mname = imp.module
        when (imp) {
            // Import all declarations and types from this module
            is Import.Raw -> {
                val alias = if (imp.alias != null) "${imp.alias}." else ""
                m.types.filter(visibleType).forEach { name, (type, _) ->
                    resolved["$alias$name"] = mname
                    ctx.add(Elem.CTVar("$mname.$name".raw(), type.kind()))
                }
                m.decls.filter(visible).forEach { name, (type, _) ->
                    resolved["$alias$name"] = mname
                    ctx.add(Elem.CVar("$alias$name".raw(), type))
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
                            ctx.add(Elem.CVar("$alias${ref.name}".raw(), declRef.type))
                        }
                        is DeclarationRef.RefType -> {
                            val declRef = m.types[ref.name]
                            if (declRef == null) {
                                errors += mkError(Errors.cannotFindInModule("type ${ref.name}", mname))
                                continue
                            }
                            if (declRef.visibility == Visibility.PRIVATE) {
                                errors += mkError(Errors.cannotImportInModule("type ${ref.name}", mname))
                                continue
                            }
                            ctx.add(Elem.CTVar("$mname.${ref.name}".raw(), declRef.type.kind()))
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
                                        ctx.add(Elem.CVar("$alias$ctor".raw(), ctorDecl.type))
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
                                        ctx.add(Elem.CVar("$alias$ctor".raw(), ctorDecl.type))
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
    return errors
}

/**
 * Resolves all java imports in this module.
 */
@Suppress("UNCHECKED_CAST")
fun resolveForeignImports(mod: Module, ictx: InferContext): List<CompilerProblem> {
    // TODO: pass the real classpath here
    val cl = NovahClassLoader("")
    val errors = mutableListOf<CompilerProblem>()

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, ProblemContext.FOREIGN_IMPORT, span, mod.sourceName, mod.name)
    }

    val typealiases = mutableMapOf<String, String>()
    val foreigVars = mutableMapOf<String, ForeignRef>()
    val ctx = ictx.context
    val (types, foreigns) = mod.foreigns.partition { it is ForeignImport.Type }
    for (type in (types as List<ForeignImport.Type>)) {
        val fqType = type.type
        if (cl.safeLoadClass(fqType) == null) {
            errors += makeError(type.span)(Errors.classNotFound(fqType))
            continue
        }
        ctx.add(Elem.CTVar(fqType.raw()))
        if (type.alias != null) {
            typealiases[type.alias] = fqType
        } else {
            val alias = fqType.split('.').last()
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
                if (imp.static && !Reflection.isStatic(method)) {
                    errors += error(Errors.nonStaticMethod(imp.name, type))
                    continue
                }
                if (!Reflection.isAccessible(method)) {
                    errors += error(Errors.hiddenMethod(imp.name, type))
                    continue
                }
                val ctxType = methodTofunction(method, type, imp.static)
                val name = imp.alias ?: imp.name
                foreigVars[name] = ForeignRef.MethodRef(method)
                ctx.add(Elem.CVar(name.raw(), ctxType))
            }
            is ForeignImport.Ctor -> {
                val pars = imp.pars.map { typealiases[it] ?: it }
                val ctor = Reflection.findConstructor(clazz, pars)
                if (ctor == null) {
                    errors += error(Errors.ctorNotFound(type))
                    continue
                }
                if (!Reflection.isAccessible(ctor)) {
                    errors += error(Errors.hiddenCtor(type))
                    continue
                }
                val ctxType = ctorToFunction(ctor, type)
                foreigVars[imp.alias] = ForeignRef.CtorRef(ctor)
                ctx.add(Elem.CVar(imp.alias.raw(), ctxType))
            }
            is ForeignImport.Getter -> {
                val field = Reflection.findField(clazz, imp.name)
                if (field == null) {
                    errors += error(Errors.fieldNotFound(imp.name, type))
                    continue
                }
                if (!Reflection.isAccessible(field)) {
                    errors += error(Errors.hiddenField(imp.name, type))
                    continue
                }
                if (imp.static && !Reflection.isStatic(field)) {
                    errors += error(Errors.nonStaticField(imp.name, type))
                    continue
                }
                val name = imp.alias ?: imp.name
                foreigVars[name] = ForeignRef.FieldRef(field, false)
                val ctxType = if (imp.static) {
                    Type.TVar(javaToNovah(field.type.canonicalName).raw())
                } else {
                    Type.TFun(Type.TVar(type.raw()), Type.TVar(javaToNovah(field.type.canonicalName).raw()))
                }
                ctx.add(Elem.CVar(name.raw(), ctxType))
            }
            is ForeignImport.Setter -> {
                val field = Reflection.findField(clazz, imp.name)
                if (field == null) {
                    errors += error(Errors.fieldNotFound(imp.name, type))
                    continue
                }
                if (!Reflection.isAccessible(field)) {
                    errors += error(Errors.hiddenField(imp.name, type))
                    continue
                }
                if (imp.static && !Reflection.isStatic(field)) {
                    errors += error(Errors.nonStaticField(imp.name, type))
                    continue
                }
                if (Reflection.isImutable(field)) {
                    errors += error(Errors.immutableField(imp.name, type))
                    continue
                }
                foreigVars[imp.alias] = ForeignRef.FieldRef(field, true)
                val parType = Type.TVar(javaToNovah(field.type.canonicalName).raw())
                val ctxType = if (imp.static) {
                    Type.TFun(parType, tUnit)
                } else {
                    Type.TFun(Type.TFun(Type.TVar(type.raw()), parType), tUnit)
                }
                ctx.add(Elem.CVar(imp.alias.raw(), ctxType))
            }
            else -> internalError("Got imported type: ${imp.type}")
        }
    }

    mod.foreignTypes = typealiases
    mod.foreignVars = foreigVars
    return errors
}

/**
 * Transforms a [ModuleExports] instance into a list of the actual
 * exported definitions based on all the declarations of a module.
 * Also returns unknown exported/hidden vars and constructor errors.
 */
fun consolidateExports(exps: ModuleExports, decls: List<Decl>): ExportResult {
    val ctorsMap = mutableMapOf<String, List<String>>()
    val varDecls = decls.map { decl ->
        when (decl) {
            is Decl.DataDecl -> {
                ctorsMap[decl.name] = decl.dataCtors.map { it.name }
                decl.name
            }
            is Decl.TypeDecl -> decl.name
            is Decl.ValDecl -> decl.name
        }
    }.toSet()
    val ctors = ctorsMap.values.flatten()

    return when (exps) {
        is ModuleExports.ExportAll -> ExportResult(varDecls + ctors.toSet())
        is ModuleExports.Hiding -> {
            val hides = exps.hides.map { it.name }
            val hiddenCtors = getExportedCtors(exps.hides, ctorsMap)

            val exports = varDecls.filter { it !in hides }
            val ctorExports = ctors.filter { it !in hiddenCtors }
            val errors = hides.filter { it !in varDecls }
            val ctorErrors = hiddenCtors.filter { it !in ctors }
            ExportResult((exports + ctorExports).toSet(), errors + ctorErrors)
        }
        is ModuleExports.Exposing -> {
            val exposes = exps.exports.map { it.name }
            val exposedCtors = getExportedCtors(exps.exports, ctorsMap)

            val exports = varDecls.filter { it in exposes }
            val ctorExports = ctors.filter { it in exposedCtors }
            val errors = exposes.filter { it !in varDecls }
            val ctorErrors = exposedCtors.filter { it !in ctors }
            ExportResult((exports + ctorExports).toSet(), errors + ctorErrors)
        }
    }
}

private fun getExportedCtors(exports: List<DeclarationRef>, ctors: Map<String, List<String>>): List<String> {
    return exports.filterIsInstance<DeclarationRef.RefType>().flatMap {
        when {
            it.ctors == null -> listOf()
            it.ctors.isEmpty() -> ctors[it.name] ?: listOf()
            else -> it.ctors
        }
    }
}

private fun methodTofunction(m: Method, type: String, static: Boolean): Type {
    val mpars = mutableListOf<String>()
    if (!static) mpars += type // `this` is always first paramenter of non-static methods
    if (static && m.parameterTypes.isEmpty()) mpars += "prim.Unit" else mpars.addAll(m.parameterTypes.map { it.canonicalName })
    mpars += if (m.returnType.canonicalName == "void") "prim.Unit" else m.returnType.canonicalName
    val pars = mpars.map { javaToNovah(it) }
    val tpars = pars.map { Type.TVar(it.raw()) } as List<Type>

    return tpars.reduceRight { tVar, acc -> Type.TFun(tVar, acc) }
}

private fun ctorToFunction(c: Constructor<*>, type: String): Type {
    val mpars = if (c.parameterTypes.isEmpty()) listOf("prim.Unit") else c.parameterTypes.map { it.canonicalName }
    val pars = (mpars + type).map { javaToNovah(it) }
    val tpars = pars.map { Type.TVar(it.raw()) } as List<Type>

    return tpars.reduceRight { tVar, acc -> Type.TFun(tVar, acc) }
}