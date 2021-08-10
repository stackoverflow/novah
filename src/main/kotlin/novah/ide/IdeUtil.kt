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

import novah.frontend.Span
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
}