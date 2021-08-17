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
package novah.ide

import novah.ast.source.Module
import novah.data.Result
import novah.frontend.Lexer
import novah.frontend.Parser
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.io.File
import java.net.URI

object IdeUtil {

    fun uriToFile(uri: String) = File(URI(uri).path)

    fun fileToUri(file: String) = File(file).toURI().toString()

    fun spanToRange(s: Span): Range {
        val start = Position(s.startLine - 1, s.startColumn - 1)
        val end = Position(s.endLine - 1, s.endColumn - 1)
        return Range(start, end)
    }

    fun rangeToSpan(r: Range) =
        Span(r.start.line + 1, r.start.character + 1, r.end.line + 1, r.end.character + 1)

    fun parseCode(code: String): Result<Module, CompilerProblem> {
        val lexer = Lexer(code.iterator())
        return Parser(lexer, false).parseFullModule()
    }

    /**
     * Get the module out of a fully qualified name.
     * Ex: some.module.Name
     * -> some.module
     */
    fun getModule(fqn: String): String? {
        val idx = fqn.lastIndexOf(".")
        return if (idx == -1) null
        else fqn.substring(0, idx)
    }

    /**
     * The oposite of `getModule`.
     * Returns the simple name of this fully qualified identifier
     */
    fun getName(fqn: String): String {
        val idx = fqn.lastIndexOf(".")
        return if (idx == -1) fqn
        else fqn.substring(idx + 1)
    }

    private val symbolRegex = Regex("""[\w_][\w\d_]*""")

    fun isValidIdentifier(ident: String) = symbolRegex.matches(ident)
}