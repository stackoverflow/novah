package novah.main

import com.github.ajalt.clikt.output.TermUi.echo
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapBoth
import novah.ast.Desugar
import novah.ast.canonical.Visibility
import novah.ast.source.Module
import novah.backend.Codegen
import novah.data.DAG
import novah.data.DagNode
import novah.frontend.Lexer
import novah.frontend.Parser
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.frontend.resolveImports
import novah.frontend.typechecker.InferContext
import novah.frontend.typechecker.Type
import novah.optimize.Optimization
import novah.optimize.Optimizer
import novah.util.BufferedCharIterator
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import novah.ast.canonical.Module as TypedModule

/**
 * The environment where a full compilation
 * process takes place.
 */
class Environment(private val verbose: Boolean) {
    private val modules = mutableMapOf<String, FullModuleEnv>()

    private val errors = mutableListOf<CompilerProblem>()

    /**
     * Lex, parse and typecheck all modules and store them.
     */
    fun parseSources(sources: Sequence<Source>): Map<String, FullModuleEnv> {
        val modMap = mutableMapOf<String, DagNode<String, Module>>()
        val modGraph = DAG<String, Module>()

        sources.forEach { source ->
            val path = source.path
            if (verbose) echo("Parsing $path")

            source.withIterator { iter ->
                val lexer = Lexer(iter)
                val parser = Parser(lexer, path.toString())
                parser.parseFullModule().mapBoth(
                    { mod ->
                        val node = DagNode(mod.name, mod)
                        if (modMap.containsKey(mod.name)) {
                            errors += duplicateError(mod, path)
                        }
                        modMap[mod.name] = node
                    },
                    { err -> errors += err }
                )
            }
        }
        if (errors.isNotEmpty()) throwErrors()

        if (modMap.isEmpty()) {
            println("No files to compile")
            return modules
        }

        // add all nodes to the graph
        modGraph.addNodes(modMap.values)
        // link all the nodes
        modMap.values.forEach { node ->
            node.data.imports.forEach { imp ->
                modMap[imp.module]?.link(node)
            }
        }
        modGraph.findCycle()?.let { reportCycle(it) }

        val orderedMods = modGraph.topoSort()
        orderedMods.forEach { mod ->
            val ictx = InferContext()
            val errs = resolveImports(mod.data, ictx, modules)
            if (errs.isNotEmpty()) throwErrors(errs)

            if (verbose) echo("Typechecking ${mod.data.name}")

            val canonical = Desugar(mod.data).desugar().getOrElse { throwError(it) }
            val menv = ictx.infer(canonical).getOrElse { throwError(it) }
            modules[mod.data.name] = FullModuleEnv(menv, canonical)
        }
        return modules
    }

    /**
     * Optimize and generate jvm bytecode for all modules.
     */
    fun generateCode(output: File, dryRun: Boolean = false) {
        modules.values.forEach { menv ->
            val opt = Optimizer(menv.ast).convert().getOrElse { throwError(it) }
            val optAST = Optimization.run(opt)

            if (dryRun) return
            val codegen = Codegen(optAST) { dirName, fileName, bytes ->
                val dir = output.resolve(dirName)
                dir.mkdirs()
                val file = dir.resolve("$fileName.class")
                file.writeBytes(bytes)
            }
            codegen.run()
        }
    }

    private fun reportCycle(nodes: Set<DagNode<String, Module>>) {
        val msg = Errors.cycleFound(nodes.map { it.value })
        nodes.forEach { n ->
            val mod = n.data
            errors += CompilerProblem(msg, ProblemContext.MODULE, mod.span, mod.sourceName, mod.name)
        }
        throwErrors()
    }

    private fun duplicateError(mod: Module, path: Path): CompilerProblem {
        return CompilerProblem(
            Errors.duplicateModule(mod.name),
            ProblemContext.MODULE,
            mod.span,
            path.toString(),
            mod.name
        )
    }

    private fun throwErrors(errs: List<CompilerProblem> = errors): Nothing = throw CompilationError(errs)
    private fun throwError(err: CompilerProblem): Nothing = throw CompilationError(listOf(err))
}

sealed class Source(val path: Path) {
    class SPath(path: Path) : Source(path)
    class SString(path: Path, val str: String) : Source(path)

    fun withIterator(action: (Iterator<Char>) -> Unit): Unit = when (this) {
        is SPath -> Files.newBufferedReader(path, Charsets.UTF_8).use {
            action(BufferedCharIterator(it))
        }
        is SString -> action(str.iterator())
    }
}

class CompilationError(val problems: List<CompilerProblem>) : RuntimeException(problems.joinToString("\n") { it.msg })

data class FullModuleEnv(val env: ModuleEnv, val ast: TypedModule)

data class DeclRef(val type: Type, val visibility: Visibility)

data class TypeDeclRef(val type: Type, val visibility: Visibility, val ctors: List<String>)

data class ModuleEnv(
    val decls: Map<String, DeclRef>,
    val types: Map<String, TypeDeclRef>,
)