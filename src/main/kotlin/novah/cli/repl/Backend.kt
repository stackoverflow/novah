package novah.cli.repl

import novah.data.Err
import novah.data.Ok
import novah.data.Result
import novah.main.*
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

class Backend(
    private val echo: (String) -> Unit,
    private val classpath: String,
    private val sourcepath: String,
    output: String
) {

    private var imports = LinkedList<String>()
    private var code = LinkedList<String>()
    private var eval = "()"

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
    }

    fun help() = """
        :help  -> shows this help
        :clear -> resets the repl
        :q     -> quits the repl
        :>     -> starts a definition (value or function). Won't stop until an empty new line is reached
        :test <suite> -> runs the specified test suite using novah.test.runTests
        import <module> -> adds an import to the repl
        <expression> -> evaluates the expression
    """.trimIndent()

    fun execute(input: String, def: Boolean = false) {
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
                } else if (input.startsWith(":test")) {
                    val suite = input.replace(Regex("^:test\\s+"), "")
                    eval = "runTests [$suite]"
                    when (val res = build()) {
                        is Ok -> run()
                        is Err -> echo(res.err)
                    }
                } else if (input.startsWith(":")) {
                    echo("error: invalid command `$input`")
                } else if (def) {
                    eval = "()"
                    code.addLast(input)
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
                        is Ok -> run()
                        is Err -> echo(res.err)
                    }
                }
            }
        }
        echo("")
    }

    private fun build(): Result<FullModuleEnv, String> {
        val scode = """module repl1
            |
            |${imports.joinToString("\n\n")}
            |
            |${code.joinToString("\n\n")}
            |
            |pub
            |main : Array String -> Unit
            |main _ =
            |  let _res = $eval
            |  println _res
        """.trimMargin()

        val sources = sequenceOf(Source.SString(Path("repl1"), scode))
        val env = Environment(classpath, sourcepath, options)
        return try {
            env.parseSources(sources)
            env.generateCode(folder)

            val errs = env.errors().filter { it.isErrorOrFatal() }
            if (errs.isNotEmpty()) {
                Err(errs.joinToString("\n\n") { it.msg })
            } else {
                Ok(env.modules()["repl1"]!!)
            }
        } catch (_: CompilationError) {
            env.errors().filter { it.isErrorOrFatal() }.joinToString("\n\n") { it.msg }.let { Err(it) }
        }
    }

    val pbuilder = ProcessBuilder("java", "-cp", ".", "repl1.\$Module")
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
}