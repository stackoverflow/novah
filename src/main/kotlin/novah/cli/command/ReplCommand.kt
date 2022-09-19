package novah.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import novah.cli.DepsProcessor
import novah.cli.repl.*
import novah.data.Err

class ReplCommand : CliktCommand(name = "repl", help = "start a repl for the current project") {

    private val alias by option(
        "-a", "--alias",
        help = "Alias to turn on"
    )

    override fun run() {
        val depsRes = DepsProcessor.readNovahFile()
        if (depsRes is Err) {
            echo(depsRes.err, err = true)
            return
        }
        val deps = depsRes.unwrap()
        val al = alias ?: DepsProcessor.defaultAlias
        val out = deps.output ?: DepsProcessor.defaultOutput

        val cp = BuildCommand.getClasspath(al, "classpath", ::echo) ?: return
        val sp = BuildCommand.getClasspath(al, "sourcepath", ::echo) ?: return

        echo("Building project")
        BuildCommand.build(al, deps, verbose = false, devMode = false, check = false, echo = {}, echoErr = ::echo)

        val repl = Backend(::echo, cp, sp, out)

        echo(repl.greet())
        while (true) {
            var def = false
            var input = prompt(">", promptSuffix = " ", default = "")
            if (input == null || input == ":q") break
            input = input.trimStart()
            if (input == "") continue
            if (input.startsWith(":>")) {
                input = input.drop(2).trimStart()
                def = true
                while (true) {
                    val more = prompt("", promptSuffix = "", default = "")
                    if (more == null || more == "") break
                    input += "\n$more"
                }
            }
            repl.execute(input, def)
        }
    }
}