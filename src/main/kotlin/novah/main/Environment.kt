/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.main

import com.github.ajalt.clikt.output.TermUi.echo
import novah.Util
import novah.Util.internalError
import novah.ast.Desugar
import novah.ast.source.Decl
import novah.ast.source.Module
import novah.ast.source.Visibility
import novah.backend.Codegen
import novah.data.*
import novah.frontend.Lexer
import novah.frontend.Parser
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.frontend.error.Severity
import novah.frontend.typechecker.Type
import novah.frontend.typechecker.Typechecker
import novah.frontend.resolveForeignImports
import novah.frontend.resolveImports
import novah.frontend.typechecker.Inference
import novah.optimize.Optimization
import novah.optimize.Optimizer
import novah.util.BufferedCharIterator
import org.reflections.Reflections
import org.reflections.scanners.ResourcesScanner
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import novah.ast.canonical.Module as TypedModule

/**
 * The environment where a full compilation
 * process takes place.
 */
class Environment(classPath: String, private val verbose: Boolean) {
    private val modules = mutableMapOf<String, FullModuleEnv>()
    private val sourceMap = mutableMapOf<Path, String>()

    private val errors = mutableListOf<CompilerProblem>()
    private val warnings = mutableListOf<CompilerProblem>()

    init {
        classLoader = NovahClassLoader(classPath)
    }

    fun getWarnings(): List<CompilerProblem> = warnings

    /**
     * Lex, parse and typecheck all modules and store them.
     */
    fun parseSources(sources: Sequence<Source>): Map<String, FullModuleEnv> {
        // stdlib
        if (stdlibCompiled.isNotEmpty()) modules.putAll(stdlibCompiled)
        else {
            innerParseSources(stdlib, isStdlib = true)
            stdlibCompiled.putAll(modules)
        }
        return innerParseSources(sources, isStdlib = false)
    }

    private fun innerParseSources(sources: Sequence<Source>, isStdlib: Boolean): Map<String, FullModuleEnv> {
        val modMap = mutableMapOf<String, DagNode<String, Module>>()
        val modGraph = DAG<String, Module>()

        sources.forEach { source ->
            val path = source.path
            if (verbose) echo("Parsing $path")

            source.withIterator { iter ->
                val lexer = Lexer(iter)
                val parser = Parser(lexer, isStdlib, path.toFile().invariantSeparatorsPath)
                parser.parseFullModule().mapBoth(
                    { mod ->
                        if (!isStdlib) sourceMap[path] = mod.name
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
            if (verbose) echo("No files to compile")
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
            Typechecker.reset()
            val importErrs = resolveImports(mod.data, modules)
            val foreignErrs = resolveForeignImports(mod.data)
            val (warns, errs) = (importErrs + foreignErrs).partition { it.severity == Severity.WARN }
            if (errs.isNotEmpty()) throwErrors(errs)
            warnings.addAll(warns)

            if (verbose) echo("Typechecking ${mod.data.name}")

            val desugar = Desugar(mod.data)
            val (canonical, errs_) = desugar.desugar().unwrapOrElse { throwAllErrors(it) }
            errors += errs_
            warnings.addAll(desugar.getWarnings())
            val menv = Typechecker.infer(canonical).unwrapOrElse { throwAllErrors(it) }
            warnings.addAll(Inference.getWarnings())
            if (errors.isNotEmpty()) throwErrors()

            val taliases = mod.data.decls.filterIsInstance<Decl.TypealiasDecl>()
            modules[mod.data.name] = FullModuleEnv(menv, canonical, taliases)
        }
        return modules
    }

    /**
     * Optimize and generate jvm bytecode for all modules.
     */
    fun generateCode(output: File, dryRun: Boolean = false) {
        modules.values.forEach { menv ->
            val optimizer = Optimizer(menv.ast)
            val opt = optimizer.convert().unwrapOrElse { throwError(it) }

            warnings.addAll(optimizer.getWarnings())
            val optAST = Optimization.run(opt)

            if (!dryRun) {
                val codegen = Codegen(optAST) { dirName, fileName, bytes ->
                    val dir = output.resolve(dirName)
                    dir.mkdirs()
                    val file = dir.resolve("$fileName.class")
                    file.writeBytes(bytes)
                }
                codegen.run()
            }
        }
        if (!dryRun) copyNativeLibs(output)
    }

    fun modules() = modules

    fun sourceMap() = sourceMap
    
    fun reset() {
        modules.clear()
        sourceMap.clear()
        errors.clear()
        warnings.clear()
    }

    /**
     * Copy all the java classes necessary for novah to run
     * from the resources folder to the output.
     */
    private fun copyNativeLibs(output: File) {
        val input = javaClass.classLoader.getResourceAsStream("nativeLibs.zip")
        Util.unzip(input!!, output)
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
            path.toFile().invariantSeparatorsPath,
            mod.name
        )
    }

    private fun throwAllErrors(errs: List<CompilerProblem>): Nothing = throw CompilationError(errors + errs)
    private fun throwErrors(errs: List<CompilerProblem> = errors): Nothing = throw CompilationError(errs)
    private fun throwError(err: CompilerProblem): Nothing = throw CompilationError(listOf(err))

    companion object {
        private var classLoader: NovahClassLoader? = null

        fun classLoader(): NovahClassLoader {
            return if (classLoader == null) internalError("Novah class loader is null")
            else classLoader!!
        }

        private val constructorTypes = mutableMapOf<String, Type>()

        fun cacheConstructorType(name: String, type: Type) {
            constructorTypes[name] = type
        }

        fun findConstructor(name: String): Type? = constructorTypes[name]

        private val stdlibCompiled = mutableMapOf<String, FullModuleEnv>()
    }
}

/**
 * The (compiled) standard library.
 * Read from the jar itself.
 */
private val stdlib by lazy {
    val ref = Reflections(
        ConfigurationBuilder().setUrls(ClasspathHelper.forPackage("novah"))
            .setScanners(ResourcesScanner())
    )
    val res = ref.getResources(Pattern.compile(".*\\.novah"))
    res.map { path ->
        val stream = Environment::class.java.classLoader.getResourceAsStream(path)
            ?: internalError("Could not find stdlib module $path")
        val contents = stream.bufferedReader().use { it.readText() }
        Source.SString(Path.of(path), contents)
    }.asSequence()
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

class CompilationError(val problems: List<CompilerProblem>) :
    RuntimeException(problems.joinToString("\n") { it.formatToConsole() })

data class FullModuleEnv(val env: ModuleEnv, val ast: TypedModule, val aliases: List<Decl.TypealiasDecl>)

data class DeclRef(val type: Type, val visibility: Visibility, val isInstance: Boolean)

data class TypeDeclRef(val type: Type, val visibility: Visibility, val ctors: List<String>)

data class ModuleEnv(
    val decls: Map<String, DeclRef>,
    val types: Map<String, TypeDeclRef>,
)