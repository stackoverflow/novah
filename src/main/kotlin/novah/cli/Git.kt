package novah.cli

import novah.cli.command.RunCommand
import novah.data.Err
import novah.data.Ok
import novah.data.Result
import java.io.File
import java.nio.file.Paths

object Git {

    fun fetchDependency(url: String, version: String): Result<File, Int> {
        val libs = ensureFolder()
        var outFolder = libs
        for (part in url.split("/")) {
            outFolder = outFolder.resolve(part)
        }
        if (outFolder.exists()) return Ok(outFolder)

        val clone = "git clone --depth 1 --branch $version https://$url.git $outFolder"
        val exit = RunCommand.runCommand(clone)
        return if (exit == 0) Ok(outFolder)
        else Err(exit)
    }

    fun makeSourcepaths(deps: List<GitDeps>): Pair<Set<String>, Set<String>> {
        val sourcepath = deps.flatMap { ds ->
            ds.deps.paths.map { ds.folder.resolve(it).absolutePath }
        }.toSet()
        val javasourcepath = deps.flatMap { ds ->
            (ds.deps.javaPaths ?: listOf()).map { ds.folder.resolve(it).absolutePath }
        }.toSet()

        return sourcepath to javasourcepath
    }

    private var libsFolder: File? = null

    private fun ensureFolder(): File {
        if (libsFolder != null) return libsFolder!!
        val home = System.getProperty("user.home")
        val folder = Paths.get(home, ".novah", "libs").toFile()
        if (!folder.exists()) folder.mkdirs()

        libsFolder = folder
        return folder
    }
}