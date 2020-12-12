package novah.frontend

import novah.ast.source.Decl
import novah.ast.source.DeclarationRef

data class ExportResult(
    val exports: List<String>,
    val varErrors: List<String> = listOf(),
    val ctorErrors: List<String> = listOf()
)

sealed class ModuleExports {
    object ExportAll : ModuleExports()
    data class Hiding(val hides: List<DeclarationRef>) : ModuleExports()
    data class Exposing(val exports: List<DeclarationRef>) : ModuleExports()

    companion object {

        /**
         * Transforms a [ModuleExports] instance into a list of the actual
         * exported definitions based on all the declarations of a module.
         * Also returns unknown exported/hidden vars and constructor errors.
         */
        fun consolidate(exps: ModuleExports, decls: List<Decl>): ExportResult {
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
                is ExportAll -> ExportResult(varDecls.toList())
                is Hiding -> {
                    val hides = exps.hides.map { it.name }
                    val hiddenCtors = getExportedCtors(exps.hides, ctorsMap)

                    val exports = varDecls.filter { it !in hides } + ctors.filter { it !in hiddenCtors }
                    val errors = hides.filter { it !in varDecls }
                    val ctorErrors = hiddenCtors.filter { it !in ctors }
                    ExportResult(exports, errors, ctorErrors)
                }
                is Exposing -> {
                    val exposes = exps.exports.map { it.name }
                    val exposedCtors = getExportedCtors(exps.exports, ctorsMap)

                    val exports = varDecls.filter { it in exposes } + ctors.filter { it in exposedCtors }
                    val errors = exposes.filter { it !in varDecls }
                    val ctorErrors = exposedCtors.filter { it !in ctors }
                    ExportResult(exports, errors, ctorErrors)
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
    }
}