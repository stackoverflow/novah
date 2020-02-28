package novah.frontend

import novah.frontend.Token.*
import java.lang.NumberFormatException
import java.lang.RuntimeException
import java.util.*

data class Comment(val comment: String, val isMulti: Boolean = false)

sealed class Token {

    var comment: Comment? = null

    override fun toString(): String = this.javaClass.simpleName

    object EOF : Token()
    class LParen : Token()
    class RParen : Token()
    class LSBracket : Token()
    class RSBracket : Token()
    class LBracket : Token()
    class RBracket : Token()
    class Hash : Token()
    class Dot : Token()
    class Comma : Token()
    class Semicolon : Token()
    class DoubleColon : Token()
    class Equals : Token()
    class Backslash : Token()
    class Arrow : Token()
    class Underline : Token()
    class Pipe : Token()
    class ModuleT : Token()
    class Hiding : Token()
    class Exposing : Token()
    class ImportT : Token()
    class Forall : Token()
    class Type : Token()
    class As : Token()
    class And : Token()
    class IfT : Token()
    class Then : Token()
    class Else : Token()
    class LetT : Token()
    class CaseT : Token()
    class Of : Token()
    class In : Token()
    class Do : Token()

    data class BoolT(val b: Boolean) : Token()
    data class CharT(val c: Char) : Token()
    data class StringT(val s: String) : Token()
    data class IntT(val i: Long) : Token()
    data class FloatT(val f: Double) : Token()
    data class Ident(val v: String) : Token()
    data class UpperIdent(val v: String) : Token()
    data class Op(val op: String) : Token()
}

data class Position(val line: Int, val column: Int) {
    override fun toString(): String = "$line:$column"

    fun shift(n: Int) = copy(column = column + n)
}

data class Span(val start: Position, val end: Position) {
    override fun toString(): String = "$start - $end"
}

data class Spanned<out T>(val span: Span, val value: T) {
    override fun toString(): String = "$value($span)"
}

class LexError(msg: String, pos: Position) : RuntimeException("$msg at $pos")

class CharPositionIterator(private val chars: Iterator<Char>) : Iterator<Char> {

    private var lookahead: Char? = null
    var position = Position(1, 1)

    override fun hasNext(): Boolean {
        return if (lookahead != null) {
            true
        } else {
            chars.hasNext()
        }
    }

    override fun next(): Char {
        return if (lookahead != null) {
            val temp = lookahead!!
            lookahead = null
            advancePos(temp)
        } else {
            if (!chars.hasNext()) {
                throw LexError("Unexpected end of file", position)
            }
            advancePos(chars.next())
        }
    }

    fun peek(): Char {
        if (!chars.hasNext() && lookahead == null) {
            throw LexError("Unexpected end of file", position)
        }
        lookahead = lookahead ?: chars.next()
        return lookahead!!
    }

    private fun advancePos(c: Char): Char {
        position = if (c == '\n') {
            Position(position.line + 1, 1)
        } else {
            position.shift(1)
        }
        return c
    }
}

class Lexer(input: String) : Iterator<Spanned<Token>> {

    private val iter = CharPositionIterator(input.iterator())

    private val operators = "$=<>|&+-:*/%^."

    override fun hasNext(): Boolean = iter.hasNext()

    override fun next(): Spanned<Token> {
        consumeWhitespace()

        val start = iter.position
        if (!iter.hasNext()) return Spanned(Span(start, start), EOF)

        val token = when (val c = iter.next()) {
            '(' -> LParen()
            ')' -> RParen()
            '[' -> LSBracket()
            ']' -> RSBracket()
            '{' -> LBracket()
            '}' -> RBracket()
            '#' -> Hash()
            ',' -> Comma()
            ';' -> Semicolon()
            '∀' -> Forall()
            '\\' -> Backslash()
            '\'' -> char()
            '"' -> string()
            '/' -> when (iter.peek()) {
                '/' -> {
                    val comment = lineComment()
                    val next = this.next()
                    next.value.comment = Comment(comment)
                    next.value
                }
                '*' -> {
                    val comment = multiLineComment()
                    val next = this.next()
                    next.value.comment = Comment(comment, true)
                    next.value
                }
                else -> operator('/')
            }
            else -> {
                when {
                    c.isDigit() -> number(c)
                    c.isValidOperator() -> operator(c)
                    c.isValidIdentifierStart() -> ident(c)
                    else -> throw LexError("Unexpected Identifier: `$c`", start)
                }
            }
        }

        return Spanned(Span(start, iter.position), token)
    }

    private val validEscapes = arrayOf('t', 'b', 'n', 'r', '\'', '\"', '\\')

    private fun char(): Token {
        val c = iter.next()
        return if (c == '\\') {
            CharT(readEscape())
        } else {
            CharT(c)
        }
    }

    private fun ident(init: Char): Token {
        val builder = StringBuilder(init.toString())

        while (iter.hasNext() && iter.peek().isValidIdentifier()) {
            builder.append(iter.next())
        }

        return when (val id = builder.toString()) {
            "true" -> BoolT(true)
            "false" -> BoolT(false)
            "if" -> IfT()
            "then" -> Then()
            "else" -> Else()
            "_" -> Underline()
            "module" -> ModuleT()
            "import" -> ImportT()
            "forall" -> Forall()
            "let" -> LetT()
            "case" -> CaseT()
            "of" -> Of()
            "type" -> Type()
            "as" -> As()
            "in" -> In()
            "and" -> And()
            "do" -> Do()
            "hiding" -> Hiding()
            "exposing" -> Exposing()
            else ->
                if (id[0].isUpperCase()) {
                    UpperIdent(id)
                } else {
                    Ident(id)
                }
        }
    }

    private fun string(): Token {
        val bld = StringBuilder()
        var c = iter.next()

        while (c != '\"') {
            if (c == '\n') {
                lexError("Newline is not allowed inside strings")
            }
            if (c == '\\') {
                c = readEscape()
            }
            bld.append(c)
            c = iter.next()
        }
        val str = bld.toString()
        return StringT(str)
    }

    private val numPat = Regex("""\d+(\.\d+)?[e|E]?""")

    private fun number(init: Char): Token {
        if (init == '0') {
            return when (val c = iter.peek()) {
                // binary numbers
                'b', 'B' -> {
                    iter.next()
                    val bin = acceptMany("01")
                    if (bin.isEmpty()) lexError("Binary number cannot be empty")
                    IntT(bin.toSafeLong(2))
                }
                // hex numbers
                'x', 'X' -> {
                    iter.next()
                    val hex = acceptMany("0123456789abcdefABCDEF")
                    if (hex.isEmpty()) lexError("Hexadecimal number cannot be empty")
                    IntT(hex.toSafeLong(16))
                }
                else -> {
                    if (c.isDigit()) lexError("Number 0 can only be followed by b|B or x|X: `$c`")
                    IntT(0)
                }
            }
        } else {
            val num = init + acceptMany("0123456789.eE")
            if (!numPat.matches(num)) lexError("Invalid number format: `$num`")

            return when {
                num.endsWith("e", ignoreCase = true) -> {
                    val symOrNum =
                        accept("+-123456789") ?: lexError("Invalid number format: expected number or sign after `E`")
                    val rest = acceptMany("0123456789")
                    if (rest.isEmpty() && (symOrNum == '+' || symOrNum == '-')) lexError("Invalid number format: expected number after sign")
                    val str = num + symOrNum + rest
                    FloatT(str.toSafeDouble())
                }
                num.contains('.') -> FloatT(num.toSafeDouble())
                else -> IntT(num.toSafeLong(10))
            }
        }
    }

    private fun operator(init: Char): Token {
        val op = init + acceptMany(operators)

        // Long operators = unreadable code
        if (op.length > 3) lexError("Operators cannot have more than 3 characters: `$op`")

        return when (op) {
            "=" -> Equals()
            "->" -> Arrow()
            "|" -> Pipe()
            "." -> Dot()
            "::" -> DoubleColon()
            else -> Op(op)
        }
    }

    private fun lineComment(): String {
        accept("/")
        val builder = StringBuilder()
        while (iter.peek() != '\n') {
            builder.append(iter.next())
        }
        return builder.toString().trim()
    }

    private fun multiLineComment(): String {
        acceptMany("*")
        val builder = StringBuilder()
        var last = ' '

        while (true) {
            val c = iter.next()
            if (last == '*' && c == '/') break
            builder.append(c)
            last = c
        }

        return builder.toString().trimMargin("*")
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

    private fun consumeWhitespace() {
        while (iter.hasNext() && iter.peek().isWhitespace()) {
            iter.next()
        }
    }

    private fun readEscape(): Char {
        return when (val c = iter.next()) {
            in validEscapes -> c
            else -> lexError("Unexpected escape character `$c`. Valid ones are: $validEscapes")
        }
    }

    private fun lexError(msg: String): Nothing {
        throw LexError(msg, iter.position)
    }

    private fun Char.isValidIdentifierStart(): Boolean {
        return this.isLetter() || this == '_'
    }

    private fun Char.isValidIdentifier(): Boolean {
        return this.isValidIdentifierStart() || this.isDigit()
    }

    private fun Char.isValidWhiteSpace(): Boolean {
        return this == ' ' || this == '\t' || this == '\n'
    }

    private fun Char.isValidOperator(): Boolean = this in operators

    private fun String.toSafeLong(radix: Int): Long {
        try {
            return this.toLong(radix)
        } catch (e: NumberFormatException) {
            lexError("Invalid number format for integer: `$this`")
        }
    }

    private fun String.toSafeDouble(): Double {
        try {
            return this.toDouble()
        } catch (e: NumberFormatException) {
            lexError("Invalid number format for float: `$this`")
        }
    }
}