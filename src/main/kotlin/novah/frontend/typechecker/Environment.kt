package novah.frontend.typechecker

import com.github.ajalt.clikt.output.TermUi.echo
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.unwrap
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
import novah.main.Compiler
import novah.optimize.Optimization
import novah.optimize.Optimizer
import java.io.File
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
    fun parseSources(sources: Sequence<Compiler.Entry>): Map<String, FullModuleEnv> {
        val modMap = mutableMapOf<String, DagNode<String, Module>>()
        val modGraph = DAG<String, Module>()

        sources.forEach { (path, code) ->
            if (verbose) echo("Parsing $path")

            val lexer = Lexer(code)
            val parser = Parser(lexer, path.toString())
            when (val modRes = parser.parseFullModule()) {
                is Ok -> {
                    val mod = modRes.value
                    val node = DagNode(mod.name, mod)
                    if (modMap.containsKey(mod.name)) {
                        errors += CompilerProblem(
                            Errors.duplicateModule(mod.name),
                            ProblemContext.MODULE,
                            mod.span,
                            path.toString(),
                            mod.name
                        )
                    }
                    modMap[mod.name] = node
                }
                is Err -> errors += modRes.error
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
            if (errs.isNotEmpty()) {
                errors.addAll(errs)
                throwErrors()
            }

            if (verbose) echo("Typechecking ${mod.data.name}")
            val canonical = Desugar(mod.data).desugar().unwrap()
            val menv = ictx.infer(canonical)
            modules[mod.data.name] = FullModuleEnv(menv, canonical)
        }
        return modules
    }

    /**
     * Optimize and generate jvm bytecode for all modules.
     */
    fun generateCode(output: File) {
        modules.values.forEach { menv ->
            val opt = Optimizer(menv.ast)
            val optAST = Optimization.run(opt.convert())

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

    private fun throwErrors(): Nothing {
        throw CompilationError(errors)
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