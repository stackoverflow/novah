package novah.cli.repl

import novah.ast.source.Decl
import novah.data.Err
import novah.data.Ok
import novah.data.Result
import novah.frontend.Lexer
import novah.frontend.Parser
import novah.frontend.ParserError
import novah.frontend.error.CompilerProblem
import novah.main.*
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

class Backend(
    private val echo: (String) -> Unit,
    private val classpath: String?,
    private val sourcepath: String?,
    output: String
) {

    private var imports = LinkedList<String>()
    private var foreignImports = LinkedList<String>()
    private var code = LinkedList<Code>()
    private var eval = "()"
    private var replType: String? = ""

    private val folder = File(output)
    private val options = Options(verbose = false, devMode = true)

    init {
        imports.addLast("import novah.test")
    }

    fun greet() = """Novah ${Main.VERSION} repl.
        |
        |Type :help for a list of commands.
        |
    """.trimMargin()

    private fun clear() {
        imports.clear()
        code.clear()
        eval = "()"
    }

    fun help() = """
        :help                -> shows this help
        :clear               -> resets the repl
        :q, ctrl+c           -> quits the repl
        :>                   -> starts a multi-line expression or definition
        :test <suite>        -> runs the specified test suite using novah.test.runTests
        :t, :type <exp>      -> shows the type of the given expression 
        import <mod>         -> adds an import to the repl
        foreign import <mod> -> adds a foreign import to the repl
    """.trimIndent()

    fun execute(input: String) {
        when (input) {
            ":help" -> echo(help())
            ":clear" -> {
                clear()
                echo("repl cleared")
            }
            else -> {
                if (input.startsWith("import ")) {
                    eval = "()"
                    imports.addLast(input)
                    when (val res = build()) {
                        is Ok -> echo(input)
                        is Err -> {
                            imports.removeLast()
                            echo(res.err)
                        }
                    }
                } else if (input.startsWith("foreign import")) {
                    eval = "()"
                    foreignImports.addLast(input)
                    when (val res = build()) {
                        is Ok -> echo(input)
                        is Err -> {
                            foreignImports.removeLast()
                            echo(res.err)
                        }
                    }
                } else if (input.startsWith(":test ")) {
                    val suite = input.replace(Regex("^:test\\s+"), "")
                    eval = "runTests [$suite]"
                    when (val res = build()) {
                        is Ok -> run()
                        is Err -> echo(res.err)
                    }
                } else if (input.startsWith(":type ") || input.startsWith(":t ")) {
                    val exp = input.replace(Regex("^:t\\w*\\s+"), "")
                    eval = exp
                    when (val res = build()) {
                        is Ok -> echo("$exp : $replType")
                        is Err -> echo(res.err)
                    }
                } else if (input.startsWith(":")) {
                    echo("error: invalid command `$input`")
                } else {
                    val decl = getDecl(input)
                    if (decl != null) {
                        // allow reassignment of declaration
                        code.removeIf { it.decl.name == decl.name  }
                        eval = "()"
                        code.addLast(Code(input, decl))
                        when (val res = build()) {
                            is Ok -> echo("")
                            is Err -> {
                                code.removeLast()
                                echo(res.err)
                            }
                        }
                    } else {
                        eval = input
                        when (val res = build()) {
                            is Ok -> {
                                run()
                                if (replType != null) {
                                    echo(" : $replType")
                                }
                            }
                            is Err -> echo(res.err)
                        }
                    }
                }
            }
        }
        echo("")
    }

    private fun build(): Result<FullModuleEnv, String> {
        val scode = """module _repl1
            |
            |${imports.joinToString("\n\n")}
            |
            |foreign import novah.function.Function
            |${foreignImports.joinToString("\n\n")}
            |
            |${code.joinToString("\n\n") { it.code }}
            |
            |_println_repl x = case x of
            |  :? Function -> println "<function>"
            |  _ -> println x
            |
            |_repl_res = $eval
            |
            |pub
            |main : Array String -> Unit
            |main _ = _println_repl _repl_res
        """.trimMargin()

        val sources = sequenceOf(Source.SString(Path("_repl1"), scode))
        val env = Environment(classpath, sourcepath, options)
        return try {
            env.parseSources(sources)
            env.generateCode(folder)

            val errs = env.errors().filter(::errFilter)
            if (errs.isNotEmpty()) {
                Err(errs.joinToString("\n\n") { it.msg })
            } else {
                val environment = env.modules()["_repl1"]!!
                replType = environment.env.decls["_repl_res"]?.type?.show(typeVarsMap = environment.typeVarsMap)
                Ok(environment)
            }
        } catch (_: CompilationError) {
            env.errors().filter(::errFilter).joinToString("\n\n") { it.msg }.let { Err(it) }
        }
    }

    private val pbuilder = ProcessBuilder("java", "-cp", ".", "_repl1.\$Module")
        .directory(folder)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)

    private fun run(): Int {
        return try {
            val process = pbuilder.start()
            process.waitFor(10, TimeUnit.MINUTES)
            process.exitValue()
        } catch (e: IOException) {
            e.printStackTrace()
            1
        }
    }

    private fun getDecl(code: String): Decl? {
        val lexer = Lexer(code.iterator())
        val parser = Parser(lexer, isStdlib = false)
        return try {
            parser.parseDecl()
        } catch (_: ParserError) {
            null
        }
    }

    companion object {
        private fun errFilter(err: CompilerProblem): Boolean =
            err.isErrorOrFatal() && err.msg != "Undefined variable _repl_res."
    }

    private data class Code(val code: String, val decl: Decl)
}