package novah.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import novah.cli.DepsProcessor
import novah.data.Err
import java.io.File

class ClearCommand : CliktCommand(name = "clear", help = "Clear the output directory") {

    private val mapper = jacksonObjectMapper()
    
    override fun run() {
        val depsRes = DepsProcessor.readNovahFile(mapper)
        if (depsRes is Err) {
            echo(depsRes.err)
            return
        }
        val deps = depsRes.unwrap()
        
        val out = deps.output ?: DepsProcessor.defaultOutput
        val file = File(out)
        if (file.deleteRecursively()) {
            file.mkdir()
        } else {
            echo("Failed to delete $out directory")
        }
    }
}