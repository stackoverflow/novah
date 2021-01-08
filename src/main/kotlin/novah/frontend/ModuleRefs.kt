package novah.frontend

import novah.ast.canonical.Visibility
import novah.ast.source.*
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.frontend.typechecker.*

data class ExportResult(
    val exports: Set<String>,
    val errors: List<String> = listOf()
) {
    fun visibility(value: String) = if (value in exports) Visibility.PUBLIC else Visibility.PRIVATE
}

typealias VarRef = String
typealias ModuleName = String

fun resolveImports(mod: Module, ctx: Context, modules: Map<String, FullModuleEnv>): List<CompilerProblem> {
    val visible = { (_, tvis): Map.Entry<String, DeclRef> -> tvis.visibility == Visibility.PUBLIC }
    val visibleType = { (_, tvis): Map.Entry<String, TypeDeclRef> -> tvis.visibility == Visibility.PUBLIC }

    fun makeError(span: Span): (String) -> CompilerProblem = { msg ->
        CompilerProblem(msg, ProblemContext.IMPORT, span, mod.sourceName, mod.name)
    }

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