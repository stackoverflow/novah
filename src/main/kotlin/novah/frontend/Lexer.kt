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
package novah.frontend

import novah.frontend.Token.*

data class Comment(val comment: String, val span: Span, val isMulti: Boolean = false)

sealed class Token {

    object EOF : Token()
    object LParen : Token()
    object RParen : Token()
    object LSBracket : Token()
    object RSBracket : Token()
    object LBracket : Token()
    object RBracket : Token()
    object SetBracket : Token()
    object Hash : Token()
    object HashDash : Token()
    object Dot : Token()
    object Comma : Token()
    object Colon : Token()
    object Equals : Token()
    object Backslash : Token()
    object Arrow : Token()
    object Underline : Token()
    object Pipe : Token()
    object ModuleT : Token()
    object ImportT : Token()
    object TypeT : Token()
    object TypealiasT : Token()
    object As : Token()
    object IfT : Token()
    object Then : Token()
    object Else : Token()
    object LetT : Token()
    object LetBang : Token()
    object CaseT : Token()
    object Of : Token()
    object In : Token()
    object Do : Token()
    object DoDot : Token()
    object DoBang : Token()
    object ForeignT : Token()
    object PublicT : Token()
    object PublicPlus : Token()
    object Instance : Token()
    object Opaque : Token()
    object ThrowT : Token()
    object TryT : Token()
    object CatchT : Token()
    object FinallyT : Token()
    object WhileT : Token()
    object Null : Token()
    object Return : Token()
    object Yield : Token()
    object For : Token()

    data class BoolT(val b: Boolean) : Token()
    data class CharT(val c: Char, val raw: String) : Token()
    data class StringT(val s: String, val raw: String) : Token()
    data class MultilineStringT(val s: String) : Token()
    data class IntT(val v: Int, val text: String) : Token()
    data class LongT(val v: Long, val text: String) : Token()
    data class FloatT(val v: Float, val text: String) : Token()
    data class DoubleT(val v: Double, val text: String) : Token()
    data class Ident(val v: String) : Token()
    data class UpperIdent(val v: String) : Token()
    data class Op(val op: String, val isPrefix: Boolean = false) : Token()

    fun isDoubleColon() = this is Op && op == "::"

    fun isDotStart() = this is Op && op.startsWith(".")

    override fun toString(): String = this.javaClass.simpleName
}

data class Position(val line: Int, val column: Int) {
    override fun toString(): String = "$line:$column"

    fun span() = Span(line, column, line, column)
}

data class Span(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int) {
    override fun toString(): String = "$startLine:$startColumn - $endLine:$endColumn"

    fun isMultiline() = startLine < endLine

    fun length() = endColumn - startColumn

    fun matches(line: Int, col: Int) = !(before(line, col) || after(line, col))

    fun matchesLine(line: Int) = line in startLine..endLine

    private fun before(line: Int, col: Int) =
        line < startLine || (line == startLine && col < startColumn)

    fun after(line: Int, col: Int) =
        line > endLine || (line == endLine && col > endColumn)

    companion object {
        private val emptySpan = Span(-1, -1, -1, -1)
        fun empty() = emptySpan

        fun new(begin: Span, end: Span) = Span(begin.startLine, begin.startColumn, end.endLine, end.endColumn)
        fun new(begin: Position, end: Position) = Span(begin.line, begin.column, end.line, end.column)
        fun new(startLine: Int, startColumn: Int, endPos: Position) =
            Span(startLine, startColumn, endPos.line, endPos.column)
    }
}

data class Spanned<out T>(val span: Span, val value: T, val comment: Comment? = null) {
    override fun toString(): String = "$value($span)"

    fun offside() = span.startColumn

    companion object {
        fun <T> empty(value: T) = Spanned(Span.empty(), value)
    }
}

class LexError(val msg: String, val span: Span) : RuntimeException("$msg at $span")

class CharPositionIterator(private val chars: Iterator<Char>) : Iterator<Char> {

    private var lookahead: Char? = null
    var line = 1
    var column = 1

    override fun hasNext(): Boolean = lookahead != null || chars.hasNext()

    override fun next(): Char {
        return if (lookahead != null) {
            val temp = lookahead!!
            lookahead = null
            advancePos(temp)
        } else {
            if (!chars.hasNext()) {
                throw LexError("Unexpected end of file", Position(line, column).span())
            }
            advancePos(chars.next())
        }
    }

    fun peek(): Char {
        if (!chars.hasNext() && lookahead == null) {
            throw LexError("Unexpected end of file", Position(line, column).span())
        }
        lookahead = lookahead ?: chars.next()
        return lookahead!!
    }

    fun position() = Position(line, column)

    private fun advancePos(c: Char): Char {
        if (c == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }
}

class Lexer(input: Iterator<Char>) : Iterator<Spanned<Token>> {

    private val iter = CharPositionIterator(input)

    override fun hasNext(): Boolean = iter.hasNext()

    override fun next(): Spanned<Token> {
        consumeAllWhitespace()

        val startLine = iter.line
        val startColumn = iter.column
        if (!iter.hasNext()) return Spanned(Span(startLine, startColumn, startLine, startColumn), EOF)

        var comment: Comment? = null

        val c = iter.next()

        when (c) {
            '/' -> when (iter.peek()) {
                '/' -> {
                    val comm = lineComment()
                    val endSpan = Span(startLine, startColumn, iter.line, iter.column)
                    consumeAllWhitespace()
                    val next = next()
                    if (next.comment != null) return next
                    comment = Comment(comm, endSpan)
                    return next.copy(comment = comment)
                }
                '*' -> {
                    val comm = multiLineComment()
                    val endSpan = Span(startLine, startColumn, iter.line, iter.column)
                    consumeAllWhitespace()
                    val next = next()
                    if (next.comment != null) return next
                    comment = Comment(comm, endSpan, isMulti = true)
                    return next.copy(comment = comment)
                }
            }
        }

        val token = when (c) {
            '(' -> LParen
            ')' -> RParen
            '[' -> LSBracket
            ']' -> RSBracket
            '{' -> LBracket
            '}' -> RBracket
            '#' -> {
                when (iter.peek()) {
                    '{' -> {
                        iter.next()
                        SetBracket
                    }
                    '-' -> {
                        iter.next()
                        HashDash
                    }
                    else -> Hash
                }
            }
            ',' -> Comma
            '\\' -> Backslash
            '\'' -> char()
            '"' -> string()
            '-' -> {
                val tk = iter.peek()
                if (tk.isDigit()) {
                    iter.next()
                    number(tk, true)
                } else operator(c)
            }
            '`' -> {
                val op = ident(null)
                if (op is Ident) {
                    if (op.v.isEmpty()) {
                        lexError("Expected identifier after `.", Span.new(startLine, startColumn, iter.position()))
                    }
                    if (accept("`") == null) {
                        lexError(
                            "Expected closing ` after operator application.",
                            Span.new(startLine, startColumn, iter.position())
                        )
                    }
                    Op(op.v, isPrefix = true)
                } else lexError("Invalid operator application.", Span.new(startLine, startColumn, iter.position()))
            }
            else -> {
                when {
                    c.isDigit() -> number(c)
                    c.isValidOperator() -> operator(c)
                    c.isValidIdentifierStart() -> ident(c)
                    else -> lexError("Unexpected Identifier: `$c`")
                }
            }
        }

        return Spanned(Span(startLine, startColumn, iter.line, iter.column), token, comment)
    }

    private val validEscapes = setOf('t', 'b', 'n', 'r', 'u', '\'', '\"', '\\')

    private fun char(): Token {
        val c = iter.next()
        val ch = if (c == '\\') {
            val (esc, str) = readEscape()
            CharT(esc, str)
        } else {
            CharT(c, "$c")
        }
        val n = iter.next()
        if (n != '\'') lexError("Expected ' after char literal")
        return ch
    }

    private fun ident(init: Char?): Token {
        val builder = StringBuilder(init?.toString() ?: "")

        while (iter.hasNext() && iter.peek().isValidIdentifier()) {
            builder.append(iter.next())
        }

        return when (val id = builder.toString()) {
            "" -> lexError("Identifiers cannot be empty")
            "true" -> BoolT(true)
            "false" -> BoolT(false)
            "if" -> IfT
            "then" -> Then
            "else" -> Else
            "_" -> Underline
            "module" -> ModuleT
            "import" -> ImportT
            "case" -> CaseT
            "of" -> Of
            "type" -> TypeT
            "typealias" -> TypealiasT
            "as" -> As
            "in" -> In
            "foreign" -> ForeignT
            "instance" -> Instance
            "opaque" -> Opaque
            "throw" -> ThrowT
            "try" -> TryT
            "catch" -> CatchT
            "finally" -> FinallyT
            "while" -> WhileT
            "null" -> Null
            "return" -> Return
            "yield" -> Yield
            "for" -> For
            "do" -> {
                when (iter.peek()) {
                    '.' -> {
                        iter.next()
                        DoDot
                    }
                    '!' -> {
                        iter.next()
                        DoBang
                    }
                    else -> Do
                }
            }
            "let" -> {
                if (iter.peek() == '!') {
                    iter.next()
                    LetBang
                } else LetT
            }
            "pub" -> {
                if (iter.peek() == '+') {
                    iter.next()
                    PublicPlus
                } else PublicT
            }
            else ->
                if (id[0].isUpperCase()) UpperIdent(id)
                else Ident(id)
        }
    }

    private fun string(): Token {
        val bld = StringBuilder()
        val raw = StringBuilder()
        var c = iter.next()
        if (c == '"') {
            if (!iter.hasNext() || iter.peek() != '"') return StringT("", "")
            iter.next()
            return multilineString()
        }

        while (c != '\"') {
            if (c == '\n') {
                lexError("Newline is not allowed inside strings.")
            }
            if (c == '\\') {
                val (esc, str) = readEscape()
                c = esc
                raw.append(str)
            } else raw.append(c)
            bld.append(c)
            c = iter.next()
        }
        val str = bld.toString()
        return StringT(str, raw.toString())
    }

    private fun multilineString(): Token {
        val bld = StringBuilder()
        var last1 = ' '
        var last0 = ' '
        var c = iter.next()
        while (c != '"' || last1 != '"' || last0 != '"') {
            bld.append(c)
            last1 = last0
            last0 = c
            c = iter.next()
        }
        val str = bld.toString()
        return MultilineStringT(str.substring(0, str.length - 2))
    }

    private fun number(init: Char, negative: Boolean = false): Token {
        val startPos = iter.position()
        fun readE(): String {
            val e = iter.next().toString()
            val sign = accept("+-")?.toString() ?: ""
            val exp = acceptMany("0123456789")
            if (exp.isEmpty()) {
                val span = Span.new(startPos, iter.position())
                lexError("Invalid number format: expected number after `e`.", span)
            }
            return "$e$sign$exp"
        }

        fun genNumIntToken(n: Long, text: String): Token = when {
            n >= Int.MIN_VALUE && n <= Int.MAX_VALUE -> IntT(n.toInt(), text)
            else -> lexError("Number out of range for integer.", Span.new(startPos, iter.position()))
        }

        val n = if (negative) -1 else 1
        val pref = if (negative) "-" else ""
        if (!iter.hasNext()) {
            return genNumIntToken("$init".toSafeLong(10) * n, "$pref$init")
        }
        return if (init == '0' && iter.peek() != '.') {
            when (val c = iter.peek()) {
                // binary numbers
                'b', 'B' -> {
                    iter.next()
                    val bin = acceptMany("01")
                    if (bin.isEmpty()) lexError("Binary number cannot be empty")
                    val l = accept("L")
                    if (l != null) LongT(bin.toSafeLong(2) * n, "${pref}0$c$bin")
                    else genNumIntToken(bin.toSafeLong(2) * n, "${pref}0$c$bin")
                }
                // hex numbers
                'x', 'X' -> {
                    iter.next()
                    val hex = acceptMany("0123456789abcdefABCDEF")
                    if (hex.isEmpty()) lexError("Hexadecimal number cannot be empty")
                    val l = accept("L")
                    if (l != null) LongT(hex.toSafeLong(16) * n, "${pref}0$c$hex")
                    else genNumIntToken(hex.toSafeLong(16) * n, "${pref}0$c$hex")
                }
                else -> {
                    if (c.isDigit()) lexError("Number 0 can only be followed by b|B or x|X: `$c`")
                    val l = accept("L")
                    if (l != null) LongT(0, "0L")
                    else {
                        val f = accept("F")
                        if (f != null) FloatT(0F, "0F")
                        else IntT(0, "0")
                    }
                }
            }
        } else {
            val num = init + acceptMany("0123456789")

            val next = if (iter.hasNext()) iter.peek() else ' '
            when {
                next == '.' -> {
                    // Double
                    iter.next()
                    val end = acceptMany("0123456789")
                    if (end.isEmpty()) lexError("Invalid number format: number cannot end in `.`")
                    val number = if (iter.hasNext() && iter.peek().lowercaseChar() == 'e') {
                        "$num.$end${readE()}"
                    } else "$num.$end"

                    val f = accept("F")

                    if (f != null) FloatT(number.toSafeFloat() * n, pref + number + f)
                    else DoubleT(number.toSafeDouble() * n, pref + number)
                }
                next.lowercaseChar() == 'e' -> {
                    // Double
                    val number = "$num${readE()}"

                    val f = accept("F")

                    if (f != null) FloatT(number.toSafeFloat() * n, pref + number + f)
                    else DoubleT(number.toSafeDouble() * n, pref + number)
                }
                else -> {
                    // Int or Double
                    if (iter.hasNext()) {
                        val l = accept("L")
                        if (l == null) {
                            val f = accept("F")
                            if (f != null) FloatT(num.toSafeFloat() * n, pref + num + f)
                            else genNumIntToken(num.toSafeLong(10) * n, pref + num)
                        } else LongT(num.toSafeLong(10) * n, pref + num + l)
                    } else genNumIntToken(num.toSafeLong(10) * n, pref + num)
                }
            }
        }
    }

    private fun operator(init: Char): Token {
        return when (val op = init + acceptMany(operators)) {
            "=" -> Equals
            "->" -> Arrow
            "|" -> Pipe
            "." -> Dot
            ":" -> Colon
            else -> Op(op)
        }
    }

    private fun lineComment(): String {
        acceptMany("/")
        val builder = StringBuilder()
        while (iter.hasNext() && iter.peek() != '\n') {
            builder.append(iter.next())
        }
        return builder.toString().trimStart()
    }

    private fun multiLineComment(): String {
        val stars = acceptMany("*")
        if (stars.length > 1 && iter.peek() == '/') {
            iter.next()
            return ""
        }

        val builder = StringBuilder()
        var last = ' '

        while (true) {
            val c = iter.next()
            if (last == '*' && c == '/') break
            builder.append(c)
            last = c
        }

        return builder.toString().trimMargin("*").trimIndent()
    }

    private fun accept(chars: String): Char? {
        return if (iter.hasNext() && iter.peek() in chars) {
            iter.next()
        } else null
    }

    private fun acceptMany(chars: String): String {
        val bld = StringBuilder()
        while (iter.hasNext() && iter.peek() in chars) {
            bld.append(iter.next())
        }
        return bld.toString()
    }

    private fun consumeAllWhitespace() {
        while (iter.hasNext() && iter.peek().isWhitespace()) {
            iter.next()
        }
    }

    private fun readEscape(): Pair<Char, String> {
        fun getEscape(c: Char): Pair<Char, String> = when (c) {
            'n' -> '\n' to "\\n"
            't' -> '\t' to "\\t"
            'b' -> '\b' to "\\b"
            'r' -> '\r' to "\\r"
            '\'' -> '\'' to "\\'"
            '"' -> '"' to "\\\""
            '\\' -> '\\' to """\\"""
            'u' -> {
                val hex = "0123456789abcdefABCDEF"
                val b1 = accept(hex)
                val b2 = accept(hex)
                val b3 = accept(hex)
                val b4 = accept(hex)
                if (b1 == null || b2 == null || b3 == null || b4 == null) {
                    lexError("Unexpected UTF-16 escape character.")
                }
                readUTF16BMP(b1, b2, b3, b4)
            }
            else -> c to "$c"
        }
        return when (val c = iter.next()) {
            in validEscapes -> getEscape(c)
            else -> lexError("Unexpected escape character `$c`. Valid ones are: $validEscapes")
        }
    }

    private fun lexError(msg: String): Nothing {
        throw LexError(msg, iter.position().span())
    }

    private fun lexError(msg: String, span: Span): Nothing {
        throw LexError(msg, span)
    }

    private fun Char.isValidIdentifierStart(): Boolean {
        return this.isLetter() || this == '_'
    }

    private fun Char.isValidIdentifier(): Boolean {
        return this.isValidIdentifierStart() || this.isDigit()
    }

    private fun Char.isValidOperator(): Boolean = this in operators

    private fun String.toSafeLong(radix: Int): Long {
        try {
            return this.toLong(radix)
        } catch (e: NumberFormatException) {
            lexError("Invalid number format for integer: `$this`")
        }
    }

    private fun String.toSafeFloat(): Float {
        try {
            return this.toFloat()
        } catch (e: NumberFormatException) {
            lexError("Invalid number format for float: `$this`")
        }
    }

    private fun String.toSafeDouble(): Double {
        try {
            return this.toDouble()
        } catch (e: NumberFormatException) {
            lexError("Invalid number format for double: `$this`")
        }
    }

    companion object {

        private const val operators = "$=<>|&+-:*/%^.?!"

        fun isOperator(str: String) = str.toCharArray().all { it in operators }

        /**
         * Reads a Java UTF-16 basic multilingual plane escape (\uxxxx)
         */
        private fun readUTF16BMP(b1: Char, b2: Char, b3: Char, b4: Char): Pair<Char, String> {
            val str = "$b1$b2$b3$b4"
            return Integer.parseInt(str, 16).toChar() to "\\u$str"
        }
    }
}