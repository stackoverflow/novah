package novah.ide.features

import novah.ast.canonical.Decl
import novah.frontend.typechecker.*
import novah.ide.EnvResult
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.FullModuleEnv
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.Command
import java.util.concurrent.CompletableFuture

class CodeLensFeature(private val server: NovahServer) {

    fun onCodeLens(params: CodeLensParams): CompletableFuture<MutableList<out CodeLens>> {
        fun run(envRes: EnvResult): MutableList<CodeLens>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = envRes.env
            server.logger().info("got code lenses request for ${file.absolutePath}")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null
            val comms = findSignatureLenses(mod)
            comms += findMain(mod)
            return comms
        }
        return server.runningEnv().thenApply(::run)
    }

    private fun findMain(menv: FullModuleEnv): MutableList<CodeLens> {
        val mainRef = menv.ast.decls.firstOrNull {
            it is Decl.ValDecl && it.visibility.isPublic()
                    && it.name.value == "main" && it.exp.type != null && isMain(it.exp.type!!)
        } as? Decl.ValDecl ?: return mutableListOf()

        val range = IdeUtil.spanToRange(mainRef.span)
        val com = Command("Run main function", "runMain", listOf(menv.ast.name.value))
        return mutableListOf(CodeLens(range, com, null))
    }

    private fun findSignatureLenses(menv: FullModuleEnv): MutableList<CodeLens> {
        val lenses = mutableListOf<CodeLens>()
        for (decl in menv.ast.decls) {
            if (decl is Decl.ValDecl && decl.signature == null && decl.exp.type != null) {
                val range = IdeUtil.spanToRange(decl.name.span)
                val type = decl.exp.type!!.show(qualified = false)
                val title = "${decl.name.value} : $type"
                val com = Command(title, "addTypeSignature", listOf(range.start.line, range.start.character, title))
                lenses += CodeLens(range, com, null)
            }
        }
        return lenses
    }

    private fun isMain(type: Type): Boolean {
        if (type !is TArrow) return false
        val arg = type.args[0]
        return isArrayOf(arg, tString) && type.ret == tUnit
    }
}