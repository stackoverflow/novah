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
package novah.optimize

import java.lang.StringBuilder

object Names {

    /**
     * Converts a novah name to a valid Java identifier.
     */
    fun convert(name: String): String {
        val b = StringBuilder()
        for (c in name) {
            val str = chars[c]
            if (str != null) b.append(str)
            else b.append(c)
        }
        val newName = b.toString()
        return if (newName in reserved) "\$$newName" else newName
    }

    fun isValidNovahIdent(ident: String) = ident.matches(identRegex)
    
    // novah operators
    private val chars = mapOf(
        '=' to "\$equals",
        '<' to "\$smaller",
        '>' to "\$greater",
        '|' to "\$pipe",
        '&' to "\$and",
        '+' to "\$plus",
        '-' to "\$minus",
        ':' to "\$colon",
        '*' to "\$times",
        '/' to "\$slash",
        '%' to "\$percent",
        '^' to "\$hat",
        '.' to "\$dot",
        '?' to "\$question",
        '!' to "\$bang",
        '@' to "\$at"
    )

    // java reserved words
    private val reserved = setOf(
        "abstract",
        "assert",
        "boolean",
        "break",
        "byte",
        "char",
        "class",
        "const",
        "continue",
        "default",
        "double",
        "enum",
        "exports",
        "extends",
        "final",
        "float",
        "goto",
        "implements",
        "instanceof",
        "int",
        "interface",
        "long",
        "native",
        "new",
        "null",
        "package",
        "private",
        "protected",
        "public",
        "requires",
        "short",
        "static",
        "strictfp",
        "super",
        "switch",
        "synchronized",
        "this",
        "throws",
        "transient",
        "var",
        "void",
        "volatile"
    )

    private val identRegex = Regex("[a-z]\\w*")
}