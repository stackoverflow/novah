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
import novah.ast.source.FullVisibility
import novah.ast.source.Module
import novah.ast.source.Visibility
import novah.backend.Codegen
import novah.data.DAG
import novah.data.DagNode
import novah.data.mapBoth
import novah.data.unwrapOrElse
import novah.frontend.*
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.Severity
import novah.frontend.matching.Ctor
import novah.frontend.typechecker.Type
import novah.frontend.typechecker.Typechecker
import novah.optimize.Optimization
import novah.optimize.Optimizer
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.regex.Pattern
import novah.ast.canonical.Module as TypedModule

/**
 * The environment where a full compilation
 * process takes place.
 */
class Environment(classpath: String?, sourcepath: String?, private val opts: Options) {
    private val modules = mutableMapOf<String, FullModuleEnv>()
    private val sourceMap = mutableMapOf<Path, String>()

    private val errors = mutableSetOf<CompilerProblem>()

    private val ctorCache = mutableMapOf(
        "prim.Some" to Ctor("Some", 1, 2),
        "prim.None" to Ctor("None", 0, 2)
    )

    private val classLoader: NovahClassLoader
    private val sourceLoader: SourceCodeLoader

    init {
        classLoader = NovahClassLoader(classpath)
        sourceLoader = SourceCodeLoader(sourcepath)
    }

    /**
     * Lex, parse and typecheck all modules and store them.
     */
    fun parseSources(sources: Sequence<Source>): Map<String, FullModuleEnv> {
        // stdlib
        if (opts.stdlib) {
            if (stdlibCompiled.isNotEmpty()) modules.putAll(stdlibCompiled)
            else {
                innerParseSources(stdlib, isStdlib = true)
                stdlibCompiled.putAll(modules)
            }
        }
        val allSources = sources.plus(sourceLoader.loadSources())
        return innerParseSources(allSources, isStdlib = false)
    }

    private fun innerParseSources(sources: Sequence<Source>, isStdlib: Boolean): Map<String, FullModuleEnv> {
        val modMap = mutableMapOf<String, DagNode<String, Module>>()
        val modGraph = DAG<String, Module>()

        val alreadySeenPaths = mutableSetOf<String>()
        for (source in sources) {
            val path = source.path
            // TODO: check for duplicate modules
            // don't parse the same path
            if (path.toString() in alreadySeenPaths) continue
            alreadySeenPaths += path.toString()

            if (opts.verbose) echo("Parsing $path")

            source.withIterator { iter ->
                val lexer = Lexer(iter)
                val parser = Parser(lexer, isStdlib, path.toFile().invariantSeparatorsPath, opts.stdlib)
                parser.parseFullModule().mapBoth(
                    { mod ->
                        val module = mod.name.value
                        if (isStdlib) stdlibModuleNames += module
                        else sourceMap[path] = module

                        val node = DagNode(module, mod)
                        if (modMap.containsKey(module)) {
                            errors += duplicateError(mod, path)
                        }
                        errors += parser.errors()
                        modMap[module] = node
                    },
                    { err -> errors += err }
                )
            }
        }
        if (shouldThrow(errors)) throwErrors()

        if (modMap.isEmpty()) {
            if (errors.any { it.isErrorOrFatal() }) throwErrors()
            if (opts.verbose) echo("No files to compile")
            return modules
        }

        // add all nodes to the graph
        modGraph.addNodes(modMap.values)
        // link all the nodes
        modMap.values.forEach { node ->
            node.data.imports.forEach { imp ->
                modMap[imp.module.value]?.link(node)
            }
        }
        modGraph.findCycle()?.let { reportCycle(it) }

        val orderedMods = modGraph.topoSort()
        for (modNode in orderedMods) {
            val mod = modNode.data
            val typeChecker = Typechecker(classLoader)
            val importErrs = resolveImports(mod, modules, typeChecker.env)
            val foreignErrs = resolveForeignImports(mod, classLoader, typeChecker)
            errors += importErrs
            errors += foreignErrs
            if (shouldThrow(errors)) throwErrors()

            if (opts.verbose) echo("Typechecking ${mod.name.value}")

            val desugar = Desugar(mod, typeChecker)
            val canonical = desugar.desugar().unwrapOrElse { throwAllErrors(it + desugar.errors()) }
            errors += desugar.errors()
            if (shouldThrow(errors)) throwErrors()

            val menv = typeChecker.infer(canonical).unwrapOrElse { throwAllErrors(it + typeChecker.infer.errors()) }
            errors += typeChecker.infer.errors()
            if (shouldThrow(errors)) throwErrors()

            val taliases = mod.decls.filterIsInstance<Decl.TypealiasDecl>()
            modules[mod.name.value] =
                FullModuleEnv(menv, canonical, taliases, typeChecker.typeVars(), mod.comment, isStdlib)
        }
        return modules
    }

    /**
     * Optimize and generate jvm bytecode for all modules.
     */
    fun generateCode(output: File, dryRun: Boolean = false) {

        val optASTs = modules.values.map { menv ->
            val optimizer = Optimizer(menv.ast, ctorCache)
            val opt = optimizer.convert()
            errors += optimizer.errors()
            opt
        }

        if (opts.devMode) {
            if (errors.any { it.isErrorOrFatal() }) throwErrors(errors)
        } else if (errors.isNotEmpty()) {
            // in normal mode every warning is an error
            val errs = errors.map {
                if (it.severity == Severity.WARN) it.copy(severity = Severity.ERROR) else it
            }.toSet()
            throwErrors(errs)
        }

        if (!dryRun) {
            optASTs.forEach { opt ->
                // no optimizations are run in dev mode
                val optAST = if (opts.devMode) opt else Optimization.run(opt)
                val codegen = Codegen(optAST) { dirName, fileName, bytes ->
                    val dir = output.resolve(dirName)
                    dir.mkdirs()
                    val file = dir.resolve("$fileName.class")
                    file.writeBytes(bytes)
                }
                codegen.run()
            }
            copyNativeLibs(output)
        }
    }

    fun modules() = modules

    fun sourceMap() = sourceMap

    fun classLoader() = classLoader

    fun errors(): Set<CompilerProblem> = errors

    /**
     * Copy all the java classes necessary for novah to run
     * from the resources' folder to the output.
     * Cache it in ~/.novah/nativelib/<version>/
     */
    private fun copyNativeLibs(output: File) {
        val path = File("${System.getProperty("user.home")}/.novah/nativelib/${Main.VERSION}/")

        if (!path.exists()) {
            path.mkdirs()
            val input = javaClass.classLoader.getResourceAsStream("nativeLibs.zip")
            Util.unzip(input!!, path)
        }
        Util.copyFolder(path.toPath(), output.toPath())
    }

    private fun reportCycle(nodes: Set<DagNode<String, Module>>) {
        val msg = Errors.cycleFound(nodes.map { it.value })
        nodes.forEach { n ->
            val mod = n.data
            errors += CompilerProblem(msg, mod.span, mod.sourceName, mod.name.value)
        }
        throwErrors()
    }

    private fun duplicateError(mod: Module, path: Path): CompilerProblem {
        return CompilerProblem(
            Errors.duplicateModule(mod.name.value),
            mod.span,
            path.toFile().invariantSeparatorsPath,
            mod.name.value
        )
    }

    private fun throwAllErrors(errs: Set<CompilerProblem>): Nothing = throw CompilationError(errors + errs)
    private fun throwErrors(errs: Set<CompilerProblem> = errors): Nothing = throw CompilationError(errs)

    companion object {
        private const val ERROR_THRESHOLD = 10

        private val stdlibModuleNames = mutableSetOf<String>()

        fun stdlibModuleNames(): Set<String> = stdlibModuleNames

        private val constructorTypes = mutableMapOf<String, Type>()

        fun cacheConstructorType(name: String, type: Type) {
            constructorTypes[name] = type
        }

        fun findConstructor(name: String): Type? = constructorTypes[name]

        private fun shouldThrow(errors: Set<CompilerProblem>) =
            errors.any { it.isFatal() } || errors.count { it.isErrorOrFatal() } > ERROR_THRESHOLD

        private val stdlibCompiled = mutableMapOf<String, FullModuleEnv>()

        fun stdlibStream(): List<Pair<String, InputStream>> {
            val ref = Reflections(
                ConfigurationBuilder().setUrls(ClasspathHelper.forPackage("novah"))
                    .setScanners(Scanners.Resources)
            )
            val res = ref.getResources(Pattern.compile(".*\\.novah"))
            return res.map { path ->
                path to (Environment::class.java.classLoader.getResourceAsStream(path)
                    ?: internalError("Could not find stdlib module $path"))
            }
        }
    }
}

class CompilationError(val problems: Set<CompilerProblem>) :
    RuntimeException(problems.joinToString("\n") { it.formatToConsole() })

data class FullModuleEnv(
    val env: ModuleEnv,
    val ast: TypedModule,
    val aliases: List<Decl.TypealiasDecl>,
    val typeVarsMap: Map<Int, String>,
    val comment: Comment?,
    val isStdlib: Boolean
)

data class DeclRef(val type: Type, val visibility: Visibility, val isInstance: Boolean, val comment: Comment?)

data class TypeDeclRef(
    val type: Type,
    val visibility: FullVisibility,
    val ctors: List<String>,
    val comment: Comment?
)

data class ModuleEnv(
    val decls: Map<String, DeclRef>,
    val types: Map<String, TypeDeclRef>
)

/**
 * The standard library.
 * Read from the jar itself.
 */
private val stdlib by lazy {
    Environment.stdlibStream().map { (path, stream) ->
        val contents = stream.bufferedReader().use { it.readText() }
        Source.SString(Path.of(path), contents)
    }.asSequence()
}