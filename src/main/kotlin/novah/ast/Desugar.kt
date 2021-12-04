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
package novah.ast

import novah.Util.internalError
import novah.Util.partitionIsInstance
import novah.ast.canonical.*
import novah.ast.source.Decl.TypealiasDecl
import novah.ast.source.DeclarationRef
import novah.ast.source.Import
import novah.ast.source.fullname
import novah.data.*
import novah.frontend.ParserError
import novah.frontend.Span
import novah.frontend.Spanned
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Severity
import novah.frontend.typechecker.*
import novah.frontend.typechecker.Type
import novah.frontend.validatePublicAliases
import novah.main.CompilationError
import novah.ast.source.Binder as SBinder
import novah.ast.source.Case as SCase
import novah.ast.source.DataConstructor as SDataConstructor
import novah.ast.source.Decl as SDecl
import novah.ast.source.Expr as SExpr
import novah.ast.source.LetDef as SLetDef
import novah.ast.source.LiteralPattern as SLiteralPattern
import novah.ast.source.Module as SModule
import novah.ast.source.Pattern as SPattern
import novah.ast.source.Type as SType
import novah.frontend.error.Errors as E

/**
 * Converts a source AST to the canonical spanned AST
 */
class Desugar(private val smod: SModule, private val typeChecker: Typechecker) {

    private val imports = smod.resolvedImports

    private val moduleName = smod.name.value

    private var synonyms = emptyMap<String, TypealiasDecl>()

    private val errors = mutableSetOf<CompilerProblem>()

    private val usedVars = mutableSetOf<String>()

    private val usedTypes = mutableSetOf<String>()

    private val usedImports = mutableSetOf<String>()

    private val unusedImports = mutableMapOf<String, Span>()

    private var varCount = 0
    private fun newVar() = "var$${varCount++}"

    private val declNames = mutableSetOf<String>()

    fun errors(): Set<CompilerProblem> = errors

    fun desugar(): Result<Module, Set<CompilerProblem>> {
        declNames.clear()
        declNames += imports.keys
        return try {
            synonyms = validateTypealiases()
            val decls = validateTopLevelValues(smod.decls.mapNotNull { it.desugar() })
            reportUnusedImports()
            Ok(
                Module(
                    smod.name,
                    smod.sourceName,
                    decls,
                    unusedImports,
                    smod.imports,
                    smod.foreigns,
                    smod.comment
                )
            )
        } catch (pe: ParserError) {
            Err(setOf(CompilerProblem(pe.msg, pe.span, smod.sourceName, moduleName, severity = Severity.FATAL)))
        } catch (ce: CompilationError) {
            Err(ce.problems.map { it.copy(severity = Severity.FATAL) }.toSet())
        }
    }

    private val declVars = mutableSetOf<String>()
    private val unusedVars = mutableMapOf<String, Span>()

    private fun SDecl.desugar(): Decl? {
        return when (this) {
            is SDecl.TypeDecl -> {
                validateDataConstructorNames(this)
                if (smod.foreignTypes[name] != null || imports[name] != null) {
                    errors += makeError(E.duplicatedType(name), span)
                    null
                } else {
                    Decl.TypeDecl(binder, tyVars, dataCtors.map { it.desugar() }, span, visibility, isOpaque, comment)
                }
            }
            is SDecl.ValDecl -> {
                if (declNames.contains(name)) {
                    errors += makeError(E.duplicatedDecl(name), span)
                    return null
                }
                declNames += name
                declVars.clear()
                checkShadow(name, span)

                unusedVars.clear()
                patterns.map { collectVars(it) }.flatten().forEach {
                    if (!it.instance && !it.implicit) unusedVars[it.name] = it.span
                    checkShadow(it.name, it.span)
                }

                // hold the type variables as scoped typed variables
                val typeVars = mutableMapOf<String, Type>()
                val expType = signature?.type?.desugar(isCtor = false, vars = typeVars)
                val sig = if (expType != null) Signature(expType, signature!!.span) else null

                try {
                    var expr = nestLambdaPatterns(patterns, exp.desugar(tvars = typeVars), emptyList(), typeVars)

                    // if the declaration has a type annotation, annotate it
                    expr = if (expType != null) Expr.Ann(expr, expType, expr.span) else expr
                    if (unusedVars.isNotEmpty()) addUnusedVars(unusedVars)
                    Decl.ValDecl(
                        binder,
                        expr,
                        name in declVars,
                        span,
                        sig,
                        visibility,
                        isInstance,
                        isOperator,
                        comment
                    )
                } catch (pe: ParserError) {
                    errors += makeError(pe.msg, pe.span)
                    return null
                }
            }
            else -> null
        }
    }

    private fun SDataConstructor.desugar(): DataConstructor =
        DataConstructor(name, args.map { it.desugar(true) }, visibility, span)

    private fun SExpr.desugar(
        locals: List<String> = listOf(),
        tvars: Map<String, Type> = mapOf(),
    ): Expr = when (this) {
        is SExpr.Int32 -> Expr.Int32(v, span)
        is SExpr.Int64 -> Expr.Int64(v, span)
        is SExpr.Float32 -> Expr.Float32(v, span)
        is SExpr.Float64 -> Expr.Float64(v, span)
        is SExpr.StringE -> Expr.StringE(v, span)
        is SExpr.CharE -> Expr.CharE(v, span)
        is SExpr.Bool -> Expr.Bool(v, span)
        is SExpr.Var -> {
            declVars += name
            if (alias == null) unusedVars.remove(name)
            usedVars += name
            if (alias == null && name in locals) Expr.Var(name, span)
            else {
                if (alias != null) checkAlias(alias, span)
                val importedModule = imports[fullname()]
                if (importedModule != null) usedImports += importedModule
                Expr.Var(name, span, importedModule)
            }
        }
        is SExpr.ImplicitVar -> {
            if (alias != null) checkAlias(alias, span)
            val importedModule = imports[fullname()]
            if (importedModule != null) usedImports += importedModule
            Expr.ImplicitVar(name, span, if (name in locals) null else importedModule)
        }
        is SExpr.Operator -> {
            if (name == "<-") parserError(E.notAField(), span)
            if (alias == null) unusedVars.remove(name)
            usedVars += name
            if (alias != null) checkAlias(alias, span)
            val importedModule = imports[fullname()]
            if (importedModule != null) usedImports += importedModule
            Expr.Var(name, span, importedModule, isOp = true)
        }
        is SExpr.Constructor -> {
            usedVars += name
            if (alias != null) checkAlias(alias, span)
            val importedModule = imports[fullname()]
            if (importedModule != null) usedImports += importedModule
            Expr.Constructor(name, span, importedModule)
        }
        is SExpr.Lambda -> {
            val vars = patterns.map { collectVars(it) }.flatten()
            vars.forEach {
                if (!it.implicit && !it.instance) unusedVars[it.name] = it.span
                checkShadow(it.name, it.span)
            }
            nestLambdaPatterns(patterns, body.desugar(locals + vars.map { it.name }, tvars), locals, tvars)
        }
        is SExpr.App -> Expr.App(fn.desugar(locals, tvars), arg.desugar(locals, tvars), span)
        is SExpr.Parens -> exp.desugar(locals, tvars)
        is SExpr.If -> {
            val els = elseCase ?: SExpr.Unit()
            val args = listOf(cond, thenCase, els).map {
                if (it is SExpr.Underscore) {
                    val v = newVar()
                    Binder(v, it.span) to Expr.Var(v, it.span)
                } else null to it.desugar(locals, tvars)
            }

            val ifExp = Expr.If(args[0].second, args[1].second, args[2].second, span)
            nestLambdas(args.mapNotNull { it.first }, ifExp)
        }
        is SExpr.Let -> {
            val vars = collectVars(letDef)
            vars.forEach {
                if (!it.implicit && !it.instance) unusedVars[it.name] = it.span
                checkShadow(it.name, it.span)
            }
            nestLets(letDef, body.desugar(locals + vars.map { it.name }, tvars), span, locals, tvars)
        }
        is SExpr.Match -> {
            val args = exps.map {
                if (it is SExpr.Underscore) {
                    val v = newVar()
                    Binder(v, it.span) to Expr.Var(v, it.span)
                } else null to it.desugar(locals, tvars)
            }
            val match = Expr.Match(args.map { it.second }, cases.map { it.desugar(locals, tvars) }, span)
            nestLambdas(args.mapNotNull { it.first }, match)
        }
        is SExpr.Ann -> Expr.Ann(exp.desugar(locals, tvars), type.desugar(vars = tvars.toMutableMap()), span)
        is SExpr.Do -> {
            if (exps.last() is SExpr.DoLet) parserError(E.LET_DO_LAST, exps.last().span)
            val converted = convertDoLets(exps)
            Expr.Do(converted.map { it.desugar(locals, tvars) }, span)
        }
        is SExpr.DoLet -> parserError(E.LET_IN, span)
        is SExpr.Unit -> Expr.Unit(span)
        is SExpr.RecordEmpty -> Expr.RecordEmpty(span)
        is SExpr.RecordSelect -> {
            if (exp is SExpr.Underscore) {
                val v = newVar()
                Expr.Lambda(Binder(v, span), nestRecordSelects(Expr.Var(v, span), labels, span), span)
            } else nestRecordSelects(exp.desugar(locals, tvars), labels, span)
        }
        is SExpr.RecordExtend -> {
            val lvars = mutableListOf<Binder>()
            val plabels = labels.map { (label, e) ->
                label to if (e is SExpr.Underscore) {
                    val v = newVar()
                    lvars += Binder(v, span)
                    Expr.Var(v, span)
                } else e.desugar(locals, tvars)
            }
            val expr = if (exp is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Var(v, span)
            } else exp.desugar(locals, tvars)

            val rec = Expr.RecordExtend(labelMapWith(plabels), expr, span)
            nestLambdas(lvars, rec)
        }
        is SExpr.RecordRestrict -> {
            if (exp is SExpr.Underscore) {
                val v = newVar()
                nestLambdas(listOf(Binder(v, span)), nestRecordRestrictions(Expr.Var(v, span), labels, span))
            } else nestRecordRestrictions(exp.desugar(locals, tvars), labels, span)
        }
        is SExpr.RecordUpdate -> {
            val lvars = mutableListOf<Binder>()
            val lvalue = if (value is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Var(v, span)
            } else value.desugar(locals, tvars)

            val lexpr = if (exp is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Var(v, span)
            } else exp.desugar(locals, tvars)

            nestLambdas(lvars, nestRecordUpdates(lexpr, labels, lvalue, isSet, span))
        }
        is SExpr.RecordMerge -> {
            val lvars = mutableListOf<Binder>()
            val lexpr = if (exp1 is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Var(v, span)
            } else exp1.desugar(locals, tvars)

            val rexpr = if (exp2 is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Var(v, span)
            } else exp2.desugar(locals, tvars)

            nestLambdas(lvars, Expr.RecordMerge(lexpr, rexpr, span))
        }
        is SExpr.ListLiteral -> Expr.ListLiteral(exps.map { it.desugar(locals, tvars) }, span)
        is SExpr.SetLiteral -> Expr.SetLiteral(exps.map { it.desugar(locals, tvars) }, span)
        is SExpr.BinApp -> {
            if (op is SExpr.Operator && op.name == "<-") {
                desugarSetter(left.desugar(locals, tvars), right.desugar(locals, tvars), span)
            } else {
                val args = listOf(left, right).map {
                    if (it is SExpr.Underscore) {
                        val v = newVar()
                        Binder(v, it.span) to Expr.Var(v, it.span)
                    } else null to it.desugar(locals, tvars)
                }
                val inner = Expr.App(op.desugar(locals, tvars), args[0].second, Span.new(left.span, op.span))
                val app = Expr.App(inner, args[1].second, Span.new(inner.span, right.span))
                nestLambdas(args.mapNotNull { it.first }, app)
            }
        }
        is SExpr.Underscore -> parserError(E.ANONYMOUS_FUNCTION_ARGUMENT, span)
        is SExpr.Throw -> Expr.Throw(exp.desugar(locals, tvars), span)
        is SExpr.TryCatch -> {
            val fin = finallyExp?.desugar(locals, tvars)
            Expr.TryCatch(tryExpr.desugar(locals, tvars), cases.map { it.desugar(locals, tvars) }, fin, span)
        }
        is SExpr.While -> {
            Expr.While(cond.desugar(locals, tvars), exps.map { it.desugar(locals, tvars) }, span)
        }
        is SExpr.Computation -> {
            desugarComputation(exps, builder).desugar(locals, tvars)
        }
        is SExpr.Null -> Expr.Null(span)
        is SExpr.TypeCast -> Expr.TypeCast(exp.desugar(locals, tvars), cast.desugar(vars = tvars.toMutableMap()), span)
        is SExpr.ForeignStaticField -> {
            usedTypes += clazz.value
            val fqclass = smod.foreignTypes[clazz.value] ?: Reflection.novahToJava(clazz.value)
            if (field.value == "class") {
                val className = if (!fqclass.contains(".")) {
                    val imp = imports[fqclass]
                    (imp ?: moduleName) + ".$fqclass"
                } else fqclass
                Expr.ClassConstant(Spanned(clazz.span, className), span)
            } else Expr.ForeignStaticField(Spanned(clazz.span, fqclass), field, span)
        }
        is SExpr.ForeignField -> {
            val xp = if (exp is SExpr.Parens) exp.exp else exp
            val lvars = mutableListOf<Binder>()
            val lexpr = if (xp is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Var(v, span)
            } else if (xp is SExpr.Ann && xp.exp is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Ann(Expr.Var(v, span), xp.type.desugar(), span)
            } else xp.desugar(locals, tvars)

            nestLambdas(lvars, Expr.ForeignField(lexpr, field, span))
        }
        is SExpr.ForeignStaticMethod -> {
            usedTypes += clazz.value
            val fqclass = smod.foreignTypes[clazz.value] ?: Reflection.novahToJava(clazz.value)

            val lvars = mutableListOf<Binder>()
            val pars = args.map {
                if (it is SExpr.Underscore) {
                    val v = newVar()
                    lvars += Binder(v, it.span)
                    Expr.Var(v, it.span)
                } else it.desugar(locals, tvars)
            }

            nestLambdas(lvars, Expr.ForeignStaticMethod(Spanned(clazz.span, fqclass), method, pars, span))
        }
        is SExpr.ForeignMethod -> {
            val xp = if (exp is SExpr.Parens) exp.exp else exp
            val lvars = mutableListOf<Binder>()
            val lexpr = if (xp is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Var(v, span)
            } else if (xp is SExpr.Ann && xp.exp is SExpr.Underscore) {
                val v = newVar()
                lvars += Binder(v, span)
                Expr.Ann(Expr.Var(v, span), xp.type.desugar(), span)
            } else xp.desugar(locals, tvars)

            val pars = args.map {
                if (it is SExpr.Underscore) {
                    val v = newVar()
                    lvars += Binder(v, it.span)
                    Expr.Var(v, it.span)
                } else it.desugar(locals, tvars)
            }

            nestLambdas(lvars, Expr.ForeignMethod(lexpr, method, pars, span))
        }
        is SExpr.Return -> parserError(E.RETURN_EXPR, span)
        is SExpr.Yield -> parserError(E.YIELD_EXPR, span)
        is SExpr.LetBang -> parserError(E.LET_BANG, span)
        is SExpr.DoBang -> parserError(E.DO_BANG, span)
        is SExpr.For -> parserError(E.FOR_EXPR, span)
    }

    private fun SCase.desugar(locals: List<String>, tvars: Map<String, Type>): Case {
        val vars = patterns.map { collectVars(it) }.flatten()
        vars.forEach {
            if (!it.implicit && !it.instance) unusedVars[it.name] = it.span
            checkShadow(it.name, it.span)
        }
        return Case(
            patterns.map { it.desugar(locals, tvars) },
            exp.desugar(locals, tvars),
            guard?.desugar(locals, tvars)
        )
    }

    private fun SPattern.desugar(locals: List<String>, tvars: Map<String, Type>): Pattern = when (this) {
        is SPattern.Wildcard -> Pattern.Wildcard(span)
        is SPattern.LiteralP -> Pattern.LiteralP(lit.desugar(locals), span)
        is SPattern.Var -> Pattern.Var(Expr.Var(v.name, v.span))
        is SPattern.Ctor -> Pattern.Ctor(
            ctor.desugar(locals, tvars) as Expr.Constructor,
            fields.map { it.desugar(locals, tvars) },
            span
        )
        is SPattern.Parens -> pattern.desugar(locals, tvars)
        is SPattern.Record -> Pattern.Record(labels.mapList { it.desugar(locals, tvars) }, span)
        is SPattern.ListP -> Pattern.ListP(elems.map { it.desugar(locals, tvars) }, span)
        is SPattern.ListHeadTail -> Pattern.ListHeadTail(head.desugar(locals, tvars), tail.desugar(locals, tvars), span)
        is SPattern.Named -> Pattern.Named(pat.desugar(locals, tvars), name, span)
        is SPattern.Unit -> Pattern.Unit(span)
        is SPattern.TypeTest -> Pattern.TypeTest(type.desugar(), alias, span)
        is SPattern.ImplicitPattern -> parserError(E.IMPLICIT_PATTERN, span)
        is SPattern.TypeAnnotation -> parserError(E.ANNOTATION_PATTERN, span)
    }

    private fun SLiteralPattern.desugar(locals: List<String>): LiteralPattern = when (this) {
        is SLiteralPattern.BoolLiteral -> LiteralPattern.BoolLiteral(e.desugar(locals) as Expr.Bool)
        is SLiteralPattern.CharLiteral -> LiteralPattern.CharLiteral(e.desugar(locals) as Expr.CharE)
        is SLiteralPattern.StringLiteral -> LiteralPattern.StringLiteral(e.desugar(locals) as Expr.StringE)
        is SLiteralPattern.Int32Literal -> LiteralPattern.Int32Literal(e.desugar(locals) as Expr.Int32)
        is SLiteralPattern.Int64Literal -> LiteralPattern.Int64Literal(e.desugar(locals) as Expr.Int64)
        is SLiteralPattern.Float32Literal -> LiteralPattern.Float32Literal(e.desugar(locals) as Expr.Float32)
        is SLiteralPattern.Float64Literal -> LiteralPattern.Float64Literal(e.desugar(locals) as Expr.Float64)
    }

    private fun SLetDef.DefBind.desugar(locals: List<String>, tvars: Map<String, Type>): LetDef {
        val e = expr.desugar(locals, tvars)
        val vars = collectVars(e)
        val recursive = name.name in vars
        return if (patterns.isEmpty()) {
            val exp = if (type != null)
                Expr.Ann(e, type.desugar(), expr.span)
            else e
            LetDef(name.desugar(), exp, recursive, isInstance)
        } else {
            val binder = nestLambdaPatterns(patterns, e, locals, tvars)
            val exp = if (type != null) {
                Expr.Ann(binder, type.desugar(), expr.span)
            } else binder
            LetDef(name.desugar(), exp, recursive, isInstance)
        }
    }

    private fun SBinder.desugar(): Binder = Binder(name, span, isImplicit)

    private fun SType.desugar(isCtor: Boolean = false, vars: MutableMap<String, Type> = mutableMapOf()): Type {
        val ty = resolveAliases(this)
        return ty.goDesugar(isCtor, vars)
    }

    private fun SType.goDesugar(isCtor: Boolean, vars: MutableMap<String, Type>, kindArity: Int = 0): Type =
        when (this) {
            is SType.TConst -> {
                usedTypes += name
                val kind = if (kindArity == 0) Kind.Star else Kind.Constructor(kindArity)
                if (name[0].isLowerCase()) {
                    if (!isCtor) {
                        var v = vars[name]
                        if (v != null) v.clone().span(span)
                        else {
                            v = typeChecker.newGenVar(name).span(span)
                            vars[name] = v
                            v
                        }
                    } else TConst(name).span(span)
                } else {
                    val importedModule = imports[fullname()]
                    if (importedModule != null) usedImports += importedModule
                    val varName = smod.foreignTypes[name] ?: ((importedModule ?: moduleName) + ".$name")
                    TConst(varName, kind).span(span)
                }
            }
            is SType.TFun -> TArrow(listOf(arg.goDesugar(isCtor, vars)), ret.goDesugar(isCtor, vars)).span(span)
            is SType.TParens -> type.goDesugar(isCtor, vars, kindArity)
            is SType.TApp -> TApp(
                type.goDesugar(isCtor, vars, types.size),
                types.map { it.goDesugar(isCtor, vars) }).span(span)
            is SType.TRecord -> TRecord(row.goDesugar(isCtor, vars)).span(span)
            is SType.TRowEmpty -> TRowEmpty().span(span)
            is SType.TRowExtend -> {
                val sorted = labelMapWith(labels)
                TRowExtend(sorted.mapList { it.goDesugar(isCtor, vars) }, row.goDesugar(isCtor, vars)).span(span)
            }
            is SType.TImplicit -> TImplicit(type.goDesugar(isCtor, vars)).span(span)
        }

    private fun desugarSetter(field: Expr, value: Expr, span: Span): Expr = when (field) {
        is Expr.ForeignStaticField -> Expr.ForeignStaticFieldSetter(field, value, span)
        is Expr.ForeignField -> Expr.ForeignFieldSetter(field, value, span)
        else -> parserError(E.notAField(), span)
    }

    private data class CollectedVar(
        val name: String,
        val span: Span,
        val implicit: Boolean = false,
        val instance: Boolean = false
    )

    private fun collectVars(ldef: SLetDef): List<CollectedVar> = when (ldef) {
        is SLetDef.DefBind ->
            listOf(CollectedVar(ldef.name.name, ldef.name.span, ldef.name.isImplicit, ldef.isInstance))
        is SLetDef.DefPattern -> collectVars(ldef.pat)
    }

    private fun collectVars(pat: SPattern, implicit: Boolean = false): List<CollectedVar> = when (pat) {
        is SPattern.Var -> listOf(CollectedVar(pat.v.name, pat.span, implicit = implicit))
        is SPattern.Parens -> collectVars(pat.pattern, implicit)
        is SPattern.Ctor -> pat.fields.flatMap { collectVars(it, implicit) }
        is SPattern.Record -> pat.labels.flatMapList { collectVars(it, implicit) }.toList()
        is SPattern.ListP -> pat.elems.flatMap { collectVars(it, implicit) }
        is SPattern.ListHeadTail -> collectVars(pat.head, implicit) + collectVars(pat.tail, implicit)
        is SPattern.Named -> collectVars(pat.pat, implicit) + listOf(CollectedVar(pat.name.value, pat.name.span))
        is SPattern.ImplicitPattern -> collectVars(pat.pat, true)
        is SPattern.Wildcard -> emptyList()
        is SPattern.LiteralP -> emptyList()
        is SPattern.Unit -> emptyList()
        is SPattern.TypeTest -> {
            if (pat.alias != null) listOf(CollectedVar(pat.alias, pat.span, implicit)) else emptyList()
        }
        is SPattern.TypeAnnotation -> collectVars(pat.pat)
    }

    private fun nestLambdas(binders: List<Binder>, exp: Expr): Expr {
        return if (binders.isEmpty()) exp
        else Expr.Lambda(binders[0], nestLambdas(binders.drop(1), exp), exp.span)
    }

    private fun nestLambdaPatterns(
        pats: List<SPattern>,
        exp: Expr,
        locals: List<String>,
        tvars: Map<String, Type>
    ): Expr {
        return if (pats.isEmpty()) exp
        else {
            when (val pat = pats[0]) {
                is SPattern.Var -> Expr.Lambda(
                    Binder(pat.v.name, pat.span, false),
                    nestLambdaPatterns(pats.drop(1), exp, locals, tvars),
                    Span.new(pat.span, exp.span)
                )
                is SPattern.ImplicitPattern -> {
                    if (pat.pat is SPattern.Var) {
                        Expr.Lambda(
                            Binder(pat.pat.v.name, pat.pat.span, true),
                            nestLambdaPatterns(pats.drop(1), exp, locals, tvars),
                            Span.new(pat.span, exp.span)
                        )
                    } else {
                        val v = newVar()
                        val vars = listOf(Expr.Var(v, pat.span))
                        val expr = Expr.Match(vars, listOf(Case(listOf(pat.pat.desugar(locals, tvars)), exp)), exp.span)
                        Expr.Lambda(
                            Binder(v, pat.span, true),
                            nestLambdaPatterns(pats.drop(1), expr, locals, tvars),
                            Span.new(pat.span, exp.span)
                        )
                    }
                }
                is SPattern.Parens -> {
                    nestLambdaPatterns(listOf(pat.pattern) + pats.drop(1), exp, locals, tvars)
                }
                is SPattern.TypeAnnotation -> {
                    val bind = Binder(pat.pat.v.name, pat.pat.span)
                    bind.type = pat.type.desugar(vars = tvars.toMutableMap())
                    Expr.Lambda(
                        bind,
                        nestLambdaPatterns(pats.drop(1), exp, locals, tvars),
                        Span.new(pat.span, exp.span)
                    )
                }
                else -> {
                    val vars = pats.map { Expr.Var(newVar(), it.span) }
                    val span = Span.new(pat.span, exp.span)
                    val expr = Expr.Match(vars, listOf(Case(pats.map { it.desugar(locals, tvars) }, exp)), span)
                    nestLambdas(vars.map { Binder(it.name, it.span) }, expr)
                }
            }
        }
    }

    private fun nestLets(ld: SLetDef, exp: Expr, span: Span, locals: List<String>, tvars: Map<String, Type>): Expr =
        when (ld) {
            is SLetDef.DefBind -> {
                Expr.Let(ld.desugar(locals, tvars), exp, span)
            }
            is SLetDef.DefPattern -> {
                val case = Case(listOf(ld.pat.desugar(locals, tvars)), exp)
                Expr.Match(listOf(ld.expr.desugar(locals, tvars)), listOf(case), span)
            }
        }

    private tailrec fun nestRecordSelects(exp: Expr, labels: List<Spanned<String>>, span: Span): Expr {
        return if (labels.isEmpty()) exp
        else nestRecordSelects(Expr.RecordSelect(exp, labels[0], span), labels.drop(1), span)
    }

    private tailrec fun nestRecordRestrictions(exp: Expr, labels: List<String>, span: Span): Expr {
        return if (labels.isEmpty()) exp
        else nestRecordRestrictions(Expr.RecordRestrict(exp, labels[0], span), labels.drop(1), span)
    }

    private fun nestRecordUpdates(
        exp: Expr,
        labels: List<Spanned<String>>,
        value: Expr,
        isSet: Boolean,
        span: Span
    ): Expr {
        return if (labels.isEmpty()) exp
        else {
            val tail = labels.drop(1)
            val shouldSet = isSet || tail.isNotEmpty()
            val select = if (tail.isEmpty()) value else Expr.RecordSelect(exp, labels[0], value.span)
            Expr.RecordUpdate(
                exp,
                labels[0],
                nestRecordUpdates(select, labels.drop(1), value, isSet, span),
                shouldSet,
                span
            )
        }
    }

    /**
     * Expand type aliases and make sure they are not recursive
     */
    private fun validateTypealiases(): Map<String, TypealiasDecl> {
        val typealiases = smod.decls.filterIsInstance<TypealiasDecl>()
        smod.resolvedTypealiases.forEach { ta ->
            if (ta.expanded == null) internalError("Got unexpanded imported typealias: $ta")
        }
        val map = (typealiases + smod.resolvedTypealiases).associateBy { it.name }

        fun expandAndcheck(ta: TypealiasDecl, ty: SType, vars: List<SType> = listOf()): SType = when (ty) {
            is SType.TConst -> {
                val name = ta.name
                if (ty.name == name) parserError(E.recursiveAlias(name), ta.span)
                if (ty.name[0].isLowerCase() && ty.name !in ta.tyVars) {
                    ta.freeVars += ty.name
                }
                val pointer = map[ty.fullname()]
                if (pointer != null) {
                    // recursively resolved inner typealiases
                    if (vars.size != pointer.tyVars.size) {
                        val errMsg = E.partiallyAppliedAlias(pointer.name, pointer.tyVars.size, vars.size)
                        errors += makeError(errMsg, ta.span)
                    }

                    if (vars.isEmpty() && pointer.tyVars.isEmpty()) expandAndcheck(ta, pointer.type)
                    else {
                        val substMap = pointer.tyVars.zip(vars).toMap()
                        expandAndcheck(ta, pointer.type.substVars(substMap))
                    }
                } else ty
            }
            is SType.TFun -> ty.copy(expandAndcheck(ta, ty.arg), expandAndcheck(ta, ty.ret))
            is SType.TApp -> {
                val resolvedTypes = ty.types.map { expandAndcheck(ta, it) }
                val typ = expandAndcheck(ta, ty.type, resolvedTypes)
                if (typ is SType.TConst) ty.copy(type = typ, types = resolvedTypes)
                else typ
            }
            is SType.TParens -> ty.copy(expandAndcheck(ta, ty.type))
            is SType.TRecord -> ty.copy(row = expandAndcheck(ta, ty.row))
            is SType.TRowEmpty -> ty
            is SType.TRowExtend -> ty.copy(
                row = expandAndcheck(ta, ty.row),
                labels = ty.labels.map { (k, v) -> k to expandAndcheck(ta, v) }
            )
            is SType.TImplicit -> ty.copy(expandAndcheck(ta, ty.type))
        }
        typealiases.forEach { ta ->
            ta.expanded = expandAndcheck(ta, ta.type)
            if (ta.freeVars.isNotEmpty()) {
                errors += makeError(E.freeVarsInTypealias(ta.name, ta.freeVars), ta.span)
            }
        }
        errors += validatePublicAliases(smod)
        return map
    }

    /**
     * Resolve all type aliases in this type
     */
    private fun resolveAliases(ty: SType): SType {
        return when (ty) {
            is SType.TConst -> synonyms[ty.name]?.expanded?.withSpan(ty.span) ?: ty
            is SType.TParens -> ty.copy(type = resolveAliases(ty.type))
            is SType.TFun -> ty.copy(arg = resolveAliases(ty.arg), ret = resolveAliases(ty.ret))
            is SType.TApp -> {
                val typ = ty.type as SType.TConst
                val syn = synonyms[typ.name]
                if (syn != null) {
                    val synTy = syn.expanded?.withSpan(ty.type.span) ?: internalError("Got unexpanded typealias: $syn")
                    syn.tyVars.zip(ty.types).fold(synTy) { oty, (tvar, type) ->
                        oty.substVar(tvar, resolveAliases(type))
                    }
                } else ty.copy(types = ty.types.map { resolveAliases(it) })
            }
            is SType.TRecord -> ty.copy(row = resolveAliases(ty.row))
            is SType.TRowEmpty -> ty
            is SType.TRowExtend -> ty.copy(
                row = resolveAliases(ty.row),
                labels = ty.labels.map { (k, v) -> k to resolveAliases(v) }
            )
            is SType.TImplicit -> ty.copy(resolveAliases(ty.type))
        }
    }

    /**
     * Make sure variables are not co-dependent (form cycles)
     * and order them by dependency
     */
    private fun validateTopLevelValues(desugared: List<Decl>): List<Decl> {
        tailrec fun isVariable(e: Expr): Boolean = when (e) {
            is Expr.Lambda -> false
            is Expr.Ann -> isVariable(e.exp)
            else -> true
        }

        fun reportCycle(cycle: Set<DagNode<String, Decl.ValDecl>>) {
            val first = cycle.iterator().next()
            val vars = cycle.map { it.value }
            if (cycle.size == 1) parserError(E.cycleInValues(vars), first.data.span)
            else {
                if (cycle.any { isVariable(it.data.exp) })
                    desugarErrors(cycle.map { makeError(E.cycleInValues(vars), it.data.span) })
                else
                    desugarErrors(cycle.map { makeError(E.cycleInFunctions(vars), it.data.span) })
            }
        }

        fun collectDependencies(exp: Expr): Set<String> {
            val deps = mutableSetOf<String>()
            exp.everywhereUnit { e ->
                when (e) {
                    is Expr.Var -> deps += e.fullname()
                    is Expr.ImplicitVar -> deps += e.fullname()
                    is Expr.Constructor -> deps += e.fullname()
                }
            }
            return deps
        }

        val (decls, types) = desugared.partitionIsInstance<Decl.ValDecl, Decl>()
        val deps = decls.associate { it.name.value to collectDependencies(it.exp) }

        val dag = DAG<String, Decl.ValDecl>()
        val nodes = decls.associate { it.name.value to DagNode(it.name.value, it) }
        dag.addNodes(nodes.values)

        nodes.values.forEach { node ->
            deps[node.value]?.forEach { name ->
                nodes[name]?.let { dep ->
                    val (d1, d2) = dep.data to node.data
                    when {
                        // variables cannot have cycles
                        isVariable(d1.exp) || isVariable(d2.exp) -> dep.link(node)
                        // functions can be recursive
                        d1.name.value == d2.name.value -> {
                        }
                        // functions can only be mutually recursive if they have type annotations
                        d1.signature?.type == null || d2.signature?.type == null -> dep.link(node)
                    }
                }
            }
        }
        dag.findCycle()?.let { reportCycle(it) }

        val orderedDecls = dag.topoSort().map { it.data }

        return types + orderedDecls
    }

    /**
     * To avoid confusion, constructors can't have the same name as
     * their type unless there's only one constructor for the type.
     */
    private fun validateDataConstructorNames(dd: SDecl.TypeDecl) {
        if (dd.dataCtors.size > 1) {
            val typeName = dd.name
            dd.dataCtors.forEach { dc ->
                if (dc.name.value == typeName) {
                    errors += makeError(E.wrongConstructorName(typeName), dd.span)
                }
            }
        }
    }

    /**
     * Converts the let statements inside a do into
     * let expressions where the body is every expression
     * that comes after.
     */
    private fun convertDoLets(exprs: List<SExpr>): List<SExpr> {
        return if (exprs.none { it is SExpr.DoLet }) exprs
        else {
            val exp = exprs[0]
            if (exp is SExpr.DoLet) {
                val body = convertDoLets(exprs.drop(1))
                val bodyExp = if (body.size == 1) body[0] else SExpr.Do(body).withSpan(exp.span)
                listOf(SExpr.Let(exp.letDef, bodyExp).withSpan(exp.span, bodyExp.span))
            } else listOf(exp) + convertDoLets(exprs.drop(1))
        }
    }

    private fun makeZero(builder: SExpr.Var, span: Span): SExpr {
        return SExpr.RecordSelect(builder, listOf(Spanned(span, "zero"))).withSpan(span)
    }

    private fun makeCombine(builder: SExpr.Var, span: Span, exp1: SExpr, exp2: SExpr): SExpr {
        val combine = SExpr.RecordSelect(builder, listOf(Spanned(span, "combine"))).withSpan(span)
        return SExpr.App(SExpr.App(combine, exp1).withSpan(exp1.span), exp2).withSpan(exp1.span, exp2.span)
    }

    private fun desugarComputation(exprs: List<SExpr>, builder: SExpr.Var): SExpr {
        fun combiner(span: Span, exp: SExpr): SExpr =
            makeCombine(builder, span, exp, desugarComputation(exprs.drop(1), builder))

        val isLast = exprs.size == 1
        return when (val exp = exprs[0]) {
            is SExpr.Do -> {
                val res = desugarComputation(exp.exps, builder)
                if (isLast) res
                else combiner(exp.span, res)
            }
            is SExpr.DoLet -> {
                if (isLast) parserError(E.LET_BANG_LAST, exp.span)
                val body = desugarComputation(exprs.drop(1), builder)
                SExpr.Let(exp.letDef, body).withSpan(exp.span, body.span)
            }
            is SExpr.LetBang -> {
                if (isLast && exp.body == null) parserError(E.LET_BANG_LAST, exp.span)
                val span = exp.span
                val select = SExpr.RecordSelect(builder, listOf(Spanned(span, "bind"))).withSpan(span)
                if (exp.body == null) {
                    val body = desugarComputation(exprs.drop(1), builder)

                    val func = SExpr.Lambda(listOf((exp.letDef as SLetDef.DefPattern).pat), body).withSpan(span)
                    SExpr.App(SExpr.App(select, exp.letDef.expr).withSpan(span), func).withSpan(span)
                } else {
                    val body = desugarComputation(listOf(exp.body), builder)
                    val func = SExpr.Lambda(listOf((exp.letDef as SLetDef.DefPattern).pat), body).withSpan(span)
                    val res = SExpr.App(SExpr.App(select, exp.letDef.expr).withSpan(span), func).withSpan(span)

                    if (isLast) res
                    else combiner(span, res)
                }
            }
            is SExpr.For -> {
                val span = exp.span
                val select = SExpr.RecordSelect(builder, listOf(Spanned(span, "for"))).withSpan(span)
                val body = desugarComputation(listOf(exp.body), builder)
                val func = SExpr.Lambda(listOf((exp.letDef as SLetDef.DefPattern).pat), body).withSpan(span)
                val res = SExpr.App(SExpr.App(select, exp.letDef.expr).withSpan(span), func).withSpan(span)

                if (isLast) res
                else combiner(span, res)
            }
            is SExpr.DoBang -> {
                val span = exp.span
                val body = if (isLast) makeZero(builder, span) else desugarComputation(exprs.drop(1), builder)

                val select = SExpr.RecordSelect(builder, listOf(Spanned(span, "bind"))).withSpan(span)
                val func = SExpr.Lambda(listOf(SPattern.Unit(span)), body).withSpan(span)
                SExpr.App(SExpr.App(select, exp.exp).withSpan(span), func).withSpan(span)
            }
            is SExpr.Return -> {
                val span = exp.span
                val select = SExpr.RecordSelect(builder, listOf(Spanned(span, "return"))).withSpan(span)
                val ret = SExpr.App(select, exp.exp).withSpan(span)
                if (isLast) ret
                else combiner(span, ret)
            }
            is SExpr.Yield -> {
                val span = exp.span
                val select = SExpr.RecordSelect(builder, listOf(Spanned(span, "yield"))).withSpan(span)
                val ret = SExpr.App(select, exp.exp).withSpan(span)
                if (isLast) ret
                else combiner(span, ret)
            }
            is SExpr.If -> {
                if (exp.elseCase == null) {
                    val span = exp.span
                    val elseCase = makeZero(builder, span)
                    val then = desugarComputation(listOf(exp.thenCase), builder)
                    val iff = SExpr.If(exp.cond, then, elseCase).withSpan(span)

                    if (isLast) iff
                    else combiner(span, iff)
                } else {
                    val doo = SExpr.Do(listOf(exp, makeZero(builder, exp.span)))
                    if (isLast) doo
                    else combiner(exp.span, doo)
                }
            }
            else -> {
                val doo = SExpr.Do(listOf(exp, makeZero(builder, exp.span)))
                if (isLast) doo
                else combiner(exp.span, doo)
            }
        }
    }

    private fun reportUnusedImports() {
        // normal imports
        for (imp in smod.imports.filter { !it.isAuto() }) {
            if (imp.module.value !in usedImports) {
                unusedImports[imp.module.value] = imp.span()
                continue
            }
            if (imp is Import.Exposing) {
                for (def in imp.defs) {
                    when (def) {
                        is DeclarationRef.RefVar -> {
                            if (def.name !in usedVars) unusedImports[def.name] = def.span
                        }
                        is DeclarationRef.RefType -> {
                            if (def.ctors == null && def.name !in usedTypes)
                                unusedImports[def.name] = def.span
                            if (def.ctors != null) {
                                for (ct in def.ctors) {
                                    if (ct.value !in usedVars)
                                        unusedImports[ct.value] = ct.span
                                }
                            }
                        }
                    }
                }
            }
        }

        for (imp in smod.foreigns) {
            val name = imp.name()
            if (name !in usedTypes) unusedImports[name] = imp.span
        }
    }

    private fun addUnusedVars(unusedVars: Map<String, Span>) {
        val err = CompilerProblem(
            E.unusedVariables(unusedVars.keys.toList()),
            unusedVars.values.first(),
            smod.sourceName,
            moduleName,
            severity = Severity.WARN
        )
        errors += err
    }

    private val aliasedImports = smod.imports.mapNotNull { it.alias() }.toSet()

    private fun checkAlias(alias: String, span: Span) {
        if (alias !in aliasedImports) {
            val err = CompilerProblem(
                E.noAliasFound(alias),
                span,
                smod.sourceName,
                moduleName
            )
            errors += err
        }
    }

    private fun checkShadow(name: String, span: Span) {
        if (imports.containsKey(name)) {
            val err = CompilerProblem(
                E.shadowedVariable(name),
                span,
                smod.sourceName,
                moduleName
            )
            errors += err
        }
    }

    private fun desugarErrors(errs: List<CompilerProblem>): Nothing {
        throw CompilationError(errs.toSet())
    }

    private fun parserError(msg: String, span: Span): Nothing = throw ParserError(msg, span)

    private fun makeError(msg: String, span: Span): CompilerProblem =
        CompilerProblem(msg, span, smod.sourceName, moduleName)

    companion object {
        fun collectVars(exp: Expr): List<String> =
            exp.everywhereAccumulating { e -> if (e is Expr.Var) listOf(e.name) else emptyList() }
    }
}