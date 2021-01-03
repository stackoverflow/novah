package novah.frontend.typechecker

import com.github.ajalt.clikt.output.TermUi.echo
import novah.ast.Desugar
import novah.ast.canonical.Visibility
import novah.ast.source.Module
import novah.backend.Codegen
import novah.data.DAG
import novah.data.DagNode
import novah.frontend.Lexer
import novah.frontend.Parser
import novah.frontend.resolveImports
import novah.frontend.typechecker.InferContext.context
import novah.optimize.Optimization
import novah.optimize.Optimizer
import java.io.File
import novah.ast.canonical.Module as TypedModule
import novah.main.Compiler

/**
 * The environment where a full compilation
 * process takes place.
 */
class Environment(private val verbose: Boolean) {
    private val modules = mutableMapOf<String, FullModuleEnv>()

    /**
     * Lex, parse and typecheck all modules and store them.
     */
    fun parseSources(sources: Sequence<Compiler.Entry>): Map<String, FullModuleEnv> {
        val modMap = mutableMapOf<String, DagNode<String, Module>>()
        val modGraph = DAG<String, Module>()

        sources.forEach { (path, code) ->
            if (verbose) echo("Parsing $path")

            val lexer = Lexer(code)
            val parser = Parser(lexer, path)
            val mod = parser.parseFullModule()
            val node = DagNode(mod.name, mod)
            if (modMap.containsKey(mod.name)) compilationError("found duplicate module ${mod.name}")
            modMap[mod.name] = node
        }
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
        val cycle = modGraph.findCycle()
        if (cycle != null) reportCycle(cycle)

        val orderedMods = modGraph.topoSort()
        orderedMods.forEach { mod ->
            context.reset()
            val errors = resolveImports(mod.data, context, modules)
            if (errors.isNotEmpty()) compilationError(errors.joinToString("\n"))

            if (verbose) echo("Typechecking ${mod.data.name}")
            val desugar = Desugar(mod.data)
            val canonical = desugar.desugar()
            val menv = Inference.infer(canonical)
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
        compilationError("found cycle between modules " + nodes.joinToString(", ") { it.value })
    }

    private fun compilationError(msg: String): Nothing {
        throw CompilationError(msg)
    }
}

class CompilationError(msg: String) : RuntimeException(msg)

data class FullModuleEnv(val env: ModuleEnv, val ast: TypedModule)

data class DeclRef(val type: Type, val visibility: Visibility)

data class TypeDeclRef(val type: Type, val visibility: Visibility, val ctors: List<String>)

data class ModuleEnv(
    val decls: Map<String, DeclRef>,
    val types: Map<String, TypeDeclRef>,
)