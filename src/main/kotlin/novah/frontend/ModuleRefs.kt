package novah.frontend

import novah.ast.canonical.Visibility
import novah.ast.source.*

data class ExportResult(
    val exports: List<String>,
    val ctorExports: List<String>,
    val varErrors: List<String> = listOf(),
    val ctorErrors: List<String> = listOf()
) {
    fun visibility(value: String) = if (value in exports) Visibility.PUBLIC else Visibility.PRIVATE

    fun ctorVisibility(ctor: String) = if (ctor in ctorExports) Visibility.PUBLIC else Visibility.PRIVATE
}

typealias VarRef = String
typealias Modulename = String

data class ImportResult(
    val aliases: Map<VarRef, Modulename>,
    val imports: Map<VarRef, Modulename>,
    val errors: List<VarRef>
) {
    fun resolve(v: Expr.Var): Modulename? {
        return if (v.alias != null) aliases[v.name]
        else imports[v.name]
    }

    fun resolve(v: Expr.Constructor): Modulename? {
        return if (v.alias != null) aliases[v.name]
        else imports[v.name]
    }

    fun resolve(op: Expr.Operator): Modulename? {
        return if (op.alias != null) aliases[op.name]
        else imports[op.name]
    }

    private fun resolve(name: String): Modulename? {
        return imports[name]
    }

    fun fullname(name: String, moduleName: String): String {
        val resolved = resolve(name)
        return if (resolved != null) "$resolved.$name" else "$moduleName.$name"
    }
}

/**
 * Transforms a list of [Import] into a searcheable structure
 * so we can resolve imports at desugar time.
 */
fun consolidateImports(imports: List<Import>): ImportResult {
    val aliases = mutableMapOf<VarRef, Modulename>()
    val imps = mutableMapOf<VarRef, Modulename>()
    val errors = mutableListOf<VarRef>()

    fun putAlias(k: VarRef, v: Modulename) {
        if (aliases.containsKey(k)) errors += k
        aliases[k] = v
    }

    fun putImport(k: VarRef, v: Modulename) {
        if (imps.containsKey(k)) errors += k
        imps[k] = v
    }

    imports.forEach { imp ->
        val name = imp.module.joinToString(".")
        when (imp) {
            is Import.Raw -> {
                if (imp.alias != null) putAlias(imp.alias, name)
                // TODO: collect all exports from this module and resolve them here somehow
            }
            is Import.Exposing -> {
                if (imp.alias != null) putAlias(imp.alias, name)
                imp.defs.forEach { def ->
                    when (def) {
                        is DeclarationRef.RefVar -> putImport(def.name, name)
                        is DeclarationRef.RefType -> {
                            putImport(def.name, name)
                            if (def.ctors != null && def.ctors.isEmpty()) {
                                // TODO: resolve the constructors of this type
                            } else if (def.ctors != null) {
                                def.ctors.forEach { putImport(it, name) }
                            }
                        }
                    }
                }
            }
        }
    }
    return ImportResult(aliases, imps, errors)
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
        is ModuleExports.ExportAll -> ExportResult(varDecls.toList(), ctors)
        is ModuleExports.Hiding -> {
            val hides = exps.hides.map { it.name }
            val hiddenCtors = getExportedCtors(exps.hides, ctorsMap)

            val exports = varDecls.filter { it !in hides }
            val ctorExports = ctors.filter { it !in hiddenCtors }
            val errors = hides.filter { it !in varDecls }
            val ctorErrors = hiddenCtors.filter { it !in ctors }
            ExportResult(exports, ctorExports, errors, ctorErrors)
        }
        is ModuleExports.Exposing -> {
            val exposes = exps.exports.map { it.name }
            val exposedCtors = getExportedCtors(exps.exports, ctorsMap)

            val exports = varDecls.filter { it in exposes }
            val ctorExports = ctors.filter { it in exposedCtors }
            val errors = exposes.filter { it !in varDecls }
            val ctorErrors = exposedCtors.filter { it !in ctors }
            ExportResult(exports, ctorExports, errors, ctorErrors)
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