package novah.frontend

import novah.ast.source.Decl

sealed class ModuleExports {
    object ExportAll : ModuleExports()
    data class Hiding(val hides: List<String>) : ModuleExports()
    data class Exposing(val exports: List<String>) : ModuleExports()

    companion object {

        /**
         * Transforms a [ModuleExports] instance into a list of the actual
         * exported definitions based on all the declarations of a module.
         */
        fun consolidate(exps: ModuleExports, decls: List<Decl>): List<String> {
            val varDecls = decls.map { decl ->
                when(decl) {
                    is Decl.DataDecl -> decl.name
                    is Decl.TypeDecl -> decl.name
                    is Decl.ValDecl -> decl.name
                }
            }.toSet()

            return when(exps) {
                is ExportAll -> varDecls.toList()
                is Hiding -> varDecls.filter { it !in exps.hides }
                is Exposing -> varDecls.filter { it in exps.exports }
            }
        }
    }
}