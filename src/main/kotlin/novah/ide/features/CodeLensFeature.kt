package novah.ide.features

import novah.ast.canonical.Decl
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
            //getMain(mod)
            return findSignatureLenses(mod)
        }
        return server.runningEnv().thenApply(::run)
    }

    fun getMain(menv: FullModuleEnv) {
        val mainRef = menv.ast.decls.firstOrNull {
            it is Decl.ValDecl && it.visibility.isPublic() && it.name.value == "main"
        } ?: return
        server.client().notify("custom/setMain", listOf((mainRef as Decl.ValDecl).name.span.startLine))
    }

    private fun findSignatureLenses(menv: FullModuleEnv): MutableList<CodeLens> {
        val lenses = mutableListOf<CodeLens>()
        for (decl in menv.ast.decls) {
            if (decl is Decl.ValDecl && decl.signature == null) {
                val range = IdeUtil.spanToRange(decl.name.span)
                val type = decl.exp.type!!.show(qualified = false)
                val title = "${decl.name.value} : $type"
                val com = Command(title, "addTypeSignature", listOf(range.start.line, range.start.character, title))
                lenses += CodeLens(range, com, null)
            }
        }
        return lenses
    }
}