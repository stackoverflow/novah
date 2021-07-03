package novah.main

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil
import java.io.File
import java.io.IOException
import java.lang.RuntimeException
import java.util.concurrent.TimeUnit

class NovahSpec : StringSpec({
    
    fun compileStdlibTests() {
        val compiler = TestUtil.compilerFor("test")
        compiler.run(TestUtil.cleanAndGetOutDir())
    }

    fun runCommand(command: String, cwd: File): Int {
        return try {
            val parts = command.split("\\s".toRegex())
            val process = ProcessBuilder(*parts.toTypedArray())
                .directory(cwd)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            process.waitFor(180, TimeUnit.MINUTES)
            process.exitValue()
        } catch(e: IOException) {
            e.printStackTrace()
            3
        }
    }
    
    fun runStdlibTests(): Int =
        runCommand("java -cp output main.Module", File("."))
    
    "run standard library tests" {
        compileStdlibTests()
        runStdlibTests() shouldBe 0
        RuntimeException().stackTrace[0].lineNumber
    }
})