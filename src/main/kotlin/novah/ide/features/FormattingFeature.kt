package novah.ide.features

import novah.ast.source.Module
import novah.data.unwrapOrElse
import novah.formatter.Formatter
import novah.frontend.Lexer
import novah.frontend.Parser
import novah.ide.IdeUtil
import novah.ide.NovahServer
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import java.util.concurrent.CompletableFuture

class FormattingFeature(private val server: NovahServer) {

    fun onFormat(params: DocumentFormattingParams): CompletableFuture<MutableList<out TextEdit>> {
        val file = IdeUtil.uriToFile(params.textDocument.uri)
        server.logger().log("formatting ${file.absolutePath}")

        fun formatFile(): MutableList<TextEdit>? {
            val txt = file.readText()
            val ast = parseFile(txt)
            return if (ast == null) null
            else {
                val str = Formatter().format(ast)
                val range = Range(Position(0, 0), endPosition(txt.lines()))
                mutableListOf(TextEdit(range, str))
            }
        }

        return CompletableFuture.supplyAsync(::formatFile)
    }

    private fun endPosition(lines: List<String>): Position {
        return if (lines.isEmpty()) Position(0, 0)
        else Position(lines.size - 1, lines.last().length)
    }

    private fun parseFile(txt: String): Module? {
        val lex = Lexer(txt.iterator())
        return Parser(lex, false).parseFullModule().unwrapOrElse { null }
    }
}