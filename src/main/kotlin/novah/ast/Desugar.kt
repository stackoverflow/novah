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
import novah.ast.source.ForeignRef
import novah.ast.source.fullname
import novah.data.*
import novah.data.Reflection.isStatic
import novah.frontend.ParserError
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.frontend.error.Severity
import novah.frontend.hmftypechecker.*
import novah.frontend.validatePublicAliases
import novah.main.CompilationError
import kotlin.math.max
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
class Desugar(private val smod: SModule) {

    private val imports = smod.resolvedImports
    private val moduleName = smod.name
    private var synonyms = emptyMap<String, TypealiasDecl>()
    private val warnings = mutableListOf<CompilerProblem>()
    private val errors = mutableListOf<CompilerProblem>()

    fun getWarnings(): List<CompilerProblem> = warnings

    private var varCount = 0
    private fun newVar() = "var$${varCount++}"

    private val declNames = mutableSetOf<String>()

    fun desugar(): Result<Module, List<CompilerProblem>> {
        declNames.clear()
        declNames += imports.keys
        return try {
            synonyms = validateTypealiases()
            val decls = validateTopLevelValues(smod.decls.mapNotNull { it.desugar() })
            if (errors.isNotEmpty()) desugarErrors(errors)
            Ok(Module(moduleName, smod.sourceName, decls))
        } catch (pe: ParserError) {
            Err(listOf(CompilerProblem(pe.msg, ProblemContext.DESUGAR, pe.span, smod.sourceName, smod.name)))
        } catch (ce: CompilationError) {
            Err(ce.problems)
        }
    }

    private val declVars = mutableSetOf<String>()
    private val unusedVars = mutableMapOf<String, Span>()

    private fun SDecl.desugar(): Decl? = when (this) {
        is SDecl.TypeDecl -> {
            validateDataConstructorNames(this)
            if (smod.foreignTypes[name] != null || imports[name] != null) {
                parserError(E.duplicatedType(name), span)
            }
            Decl.TypeDecl(name, tyVars, dataCtors.map { it.desugar() }, span, visibility)
        }
        is SDecl.ValDecl -> {
            if (declNames.contains(name)) parserError(E.duplicatedDecl(name), span)
            declNames += name
            declVars.clear()
            
            unusedVars.clear()
            patterns.map { collectVars(it) }.flatten().forEach { 
                if (!it.instance && !it.implicit) unusedVars[it.name] = it.span
            }
            
            var expr = nestLambdaPatterns(patterns, exp.desugar(), emptyList())

            // if the declaration has a type annotation, annotate it, else warn
            expr = if (type != null) {
                // We want type annotations to be `forall` not `some`, so
                // we won't replace constants with vars here
                val ann = emptyList<Id>() to type.desugar()
                Expr.Ann(expr, ann, span)
            } else {
                warnings += makeWarn(E.noTypeAnnDecl(name), span)
                expr
            }
            if (unusedVars.isNotEmpty()) addUnusedVars(unusedVars)
            Decl.ValDecl(name, expr, name in declVars, span, type?.desugar(), visibility, isInstance, isOperator)
        }
        else -> null
    }

    private fun SDataConstructor.desugar(): DataConstructor =
        DataConstructor(name, args.map { it.desugar() }, visibility, span)

    private fun SExpr.desugar(locals: List<String> = listOf(), appFnDepth: Int = 0): Expr = when (this) {
        is SExpr.IntE -> Expr.IntE(v, span)
        is SExpr.LongE -> Expr.LongE(v, span)
        is SExpr.FloatE -> Expr.FloatE(v, span)
        is SExpr.DoubleE -> Expr.DoubleE(v, span)
        is SExpr.StringE -> Expr.StringE(v, span)
        is SExpr.CharE -> Expr.CharE(v, span)
        is SExpr.Bool -> Expr.Bool(v, span)
        is SExpr.Var -> {
            declVars += name
            unusedVars.remove(name)
            if (name in locals) Expr.Var(name, span)
            else {
                val foreign = smod.foreignVars[name]
                if (foreign != null) {
                    // this var is a native call/field
                    val exp = when (foreign) {
                        is ForeignRef.FieldRef -> {
                            val isStatic = isStatic(foreign.field)
                            if (foreign.isSetter) {
                                Expr.NativeFieldSet(name, foreign.field, isStatic, span)
                            } else Expr.NativeFieldGet(name, foreign.field, isStatic, span)
                        }
                        is ForeignRef.MethodRef -> {
                            Expr.NativeMethod(name, foreign.method, isStatic(foreign.method), span)
                        }
                        is ForeignRef.CtorRef -> {
                            Expr.NativeConstructor(name, foreign.ctor, span)
                        }
                    }
                    validateNativeCall(exp, appFnDepth)
                    exp
                } else Expr.Var(name, span, imports[fullname()])
            }
        }
        is SExpr.ImplicitVar -> Expr.ImplicitVar(name, span, if (name in locals) null else imports[fullname()])
        is SExpr.Operator -> Expr.Var(name, span, imports[fullname()])
        is SExpr.Constructor -> Expr.Constructor(name, span, imports[fullname()])
        is SExpr.Lambda -> {
            val vars = patterns.map { collectVars(it) }.flatten()
            vars.forEach { if (!it.implicit && !it.instance) unusedVars[it.name] = it.span }
            nestLambdaPatterns(patterns, body.desugar(locals + vars.map { it.name }), locals)
        }
        is SExpr.App -> Expr.App(fn.desugar(locals, appFnDepth + 1), arg.desugar(locals), span)
        is SExpr.Parens -> exp.desugar(locals)
        is SExpr.If -> {
            val args = listOf(cond, thenCase, elseCase).map {
                if (it is SExpr.Underscore) {
                    val v = newVar()
                    Binder(v, it.span) to Expr.Var(v, it.span)
                } else null to it.desugar(locals)
            }
            
            val ifExp = Expr.If(args[0].second, args[1].second, args[2].second, span)
            nestLambdas(args.mapNotNull { it.first }, ifExp)
        }
        is SExpr.Let -> {
            val vars = letDefs.map { collectVars(it) }.flatten()
            vars.forEach { if (!it.implicit && !it.instance) unusedVars[it.name] = it.span }
            nestLets(letDefs, body.desugar(locals + vars.map { it.name }), locals)
        }
        is SExpr.Match -> {
            val args = exps.map { 
                if (it is SExpr.Underscore) {
                    val v = newVar()
                    Binder(v, it.span) to Expr.Var(v, it.span)
                } else null to it.desugar(locals)
            }
            val match = Expr.Match(args.map { it.second }, cases.map { it.desugar(locals) }, span)
            nestLambdas(args.mapNotNull { it.first }, match)
        }
        is SExpr.Ann -> {
            val ann = if (type is SType.TForall) replaceConstantsWithVars(type.names, type.type.desugar())
            else emptyList<Id>() to type.desugar()
            Expr.Ann(exp.desugar(locals), ann, span)
        }
        is SExpr.Do -> {
            if (exps.last() is SExpr.DoLet) parserError(E.LET_DO_LAST, exps.last().span)
            val converted = convertDoLets(exps)
            Expr.Do(converted.map { it.desugar(locals) }, span)
        }
        is SExpr.Unit -> Expr.Unit(span)
        is SExpr.DoLet -> internalError("got `do-let` outside of do statement: $this")
        is SExpr.RecordEmpty -> Expr.RecordEmpty(span)
        is SExpr.RecordSelect -> {
            if (exp is SExpr.Underscore) {
                val v = newVar()
                Expr.Lambda(Binder(v, span), null, nestRecordSelects(Expr.Var(v, span), labels), span)
            } else nestRecordSelects(exp.desugar(locals), labels)
        }
        is SExpr.RecordExtend -> {
            val lvars = mutableListOf<Binder>()
            val plabels = labels.map { (label, e) ->
                label to if (e is SExpr.Underscore) {
                    val v = newVar()
                    lvars += Binder(v, span)
                    Expr.Var(v, span)
                } else e.desugar(locals)
            }
            
            val rec = Expr.RecordExtend(labelMapWith(plabels), exp.desugar(locals), span)
            nestLambdas(lvars, rec)
        }
        is SExpr.RecordRestrict -> Expr.RecordRestrict(exp.desugar(locals), label, span)
        is SExpr.VectorLiteral -> Expr.VectorLiteral(exps.map { it.desugar(locals) }, span)
        is SExpr.SetLiteral -> Expr.SetLiteral(exps.map { it.desugar(locals) }, span)
        is SExpr.Underscore -> {
            internalError("got undesugared underscore")
        }
    }

    private fun SCase.desugar(locals: List<String>): Case {
        val vars = patterns.map { collectVars(it) }.flatten()
        vars.forEach { if (!it.implicit && !it.instance) unusedVars[it.name] = it.span }
        return Case(patterns.map { it.desugar(locals) }, exp.desugar(), guard?.desugar(locals))
    }

    private fun SPattern.desugar(locals: List<String>): Pattern = when (this) {
        is SPattern.Wildcard -> Pattern.Wildcard(span)
        is SPattern.LiteralP -> Pattern.LiteralP(lit.desugar(locals), span)
        is SPattern.Var -> Pattern.Var(name, span)
        is SPattern.Ctor -> Pattern.Ctor(
            ctor.desugar(locals) as Expr.Constructor,
            fields.map { it.desugar(locals) },
            span
        )
        is SPattern.Parens -> pattern.desugar(locals)
        is SPattern.Record -> Pattern.Record(labels.mapList { it.desugar(locals) }, span)
        is SPattern.Vector -> Pattern.Vector(elems.map { it.desugar(locals) }, span)
        is SPattern.VectorHT -> Pattern.VectorHT(head.desugar(locals), tail.desugar(locals), span)
        is SPattern.Named -> Pattern.Named(pat.desugar(locals), name, span)
        is SPattern.Unit -> Pattern.Unit(span)
        is SPattern.TypeTest -> Pattern.TypeTest(type.desugar(), alias, span)
        is SPattern.ImplicitVar -> parserError(E.IMPLICIT_PATTERN, span)
    }

    private fun SLiteralPattern.desugar(locals: List<String>): LiteralPattern = when (this) {
        is SLiteralPattern.BoolLiteral -> LiteralPattern.BoolLiteral(e.desugar(locals) as Expr.Bool)
        is SLiteralPattern.CharLiteral -> LiteralPattern.CharLiteral(e.desugar(locals) as Expr.CharE)
        is SLiteralPattern.StringLiteral -> LiteralPattern.StringLiteral(e.desugar(locals) as Expr.StringE)
        is SLiteralPattern.IntLiteral -> LiteralPattern.IntLiteral(e.desugar(locals) as Expr.IntE)
        is SLiteralPattern.LongLiteral -> LiteralPattern.LongLiteral(e.desugar(locals) as Expr.LongE)
        is SLiteralPattern.FloatLiteral -> LiteralPattern.FloatLiteral(e.desugar(locals) as Expr.FloatE)
        is SLiteralPattern.DoubleLiteral -> LiteralPattern.DoubleLiteral(e.desugar(locals) as Expr.DoubleE)
    }

    private fun SLetDef.DefBind.desugar(locals: List<String>): LetDef {
        return if (patterns.isEmpty()) {
            LetDef(name.desugar(), expr.desugar(locals), false, isInstance, type?.desugar())
        } else {
            val exp = expr.desugar(locals)
            val vars = collectVars(exp)
            val recursive = name.name in vars
            LetDef(name.desugar(), nestLambdaPatterns(patterns, exp, locals), recursive, isInstance, type?.desugar())
        }
    }

    private fun SBinder.desugar(): Binder = Binder(name, span, isImplicit)

    private fun SType.desugar(): Type {
        val ty = resolveAliases(this)
        return ty.goDesugar()
    }

    private fun SType.goDesugar(kindArity: Int = 0): Type = when (this) {
        is SType.TConst -> {
            val kind = if (kindArity == 0) Kind.Star else Kind.Constructor(kindArity)
            if (name[0].isLowerCase()) {
                // this will be replaced later in [replaceConstantsWithVars]
                TConst(name, kind).span(span)
            } else {
                val varName = smod.foreignTypes[name] ?: (imports[fullname()] ?: moduleName) + ".$name"
                TConst(varName, kind).span(span)
            }
        }
        is SType.TFun -> TArrow(listOf(arg.goDesugar()), ret.goDesugar()).span(span)
        is SType.TForall -> {
            val (ids, ty) = replaceConstantsWithVars(names, type.goDesugar(kindArity))
            TForall(ids, ty).span(span)
        }
        is SType.TParens -> type.goDesugar(kindArity)
        is SType.TApp -> TApp(type.goDesugar(types.size), types.map { it.goDesugar() }).span(span)
        is SType.TRecord -> TRecord(row.goDesugar()).span(span)
        is SType.TRowEmpty -> TRowEmpty().span(span)
        is SType.TRowExtend -> {
            val sorted = labelMapWith(labels)
            TRowExtend(sorted.mapList { it.goDesugar() }, row.goDesugar()).span(span)
        }
        is SType.TImplicit -> TImplicit(type.goDesugar()).span(span)
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

    private fun collectVars(pat: SPattern): List<CollectedVar> = when (pat) {
        is SPattern.Var -> listOf(CollectedVar(pat.name, pat.span))
        is SPattern.Parens -> collectVars(pat.pattern)
        is SPattern.Ctor -> pat.fields.flatMap { collectVars(it) }
        is SPattern.Record -> pat.labels.flatMapList { collectVars(it) }.toList()
        is SPattern.Vector -> pat.elems.flatMap { collectVars(it) }
        is SPattern.VectorHT -> collectVars(pat.head) + collectVars(pat.tail)
        is SPattern.Named -> collectVars(pat.pat)
        is SPattern.ImplicitVar -> listOf(CollectedVar(pat.name, pat.span, true))
        is SPattern.Wildcard -> emptyList()
        is SPattern.LiteralP -> emptyList()
        is SPattern.Unit -> emptyList()
        is SPattern.TypeTest -> if (pat.alias != null) listOf(CollectedVar(pat.alias, pat.span)) else emptyList()
    }

    /**
     * Normalizes a forall type:
     * forall c b a. a -> b
     * to
     * forall a b. a -> b
     */
    private fun replaceConstantsWithVars(vars: List<String>, type: Type): Pair<List<Id>, Type> {
        val map = mutableMapOf<String, TVar?>()
        val ids = mutableListOf<Id>()
        vars.forEach { map[it] = null }

        fun go(t: Type): Type = when (t) {
            is TConst -> {
                if (map.containsKey(t.name)) {
                    val vvar = map[t.name]
                    if (vvar != null) vvar
                    else {
                        val (id, nvar) = Typechecker.newBoundVar()
                        ids += id
                        map[t.name] = nvar
                        nvar
                    }
                } else t
            }
            is TVar -> t
            is TApp -> {
                val newType = go(t.type)
                val pars = t.types.map(::go)
                t.copy(newType, pars)
            }
            is TArrow -> {
                val pars = t.args.map(::go)
                val ret = go(t.ret)
                t.copy(pars, ret)
            }
            is TForall -> t.copy(t.ids, go(t.type))
            is TRowEmpty -> t
            is TRecord -> t.copy(go(t.row))
            is TRowExtend -> t.copy(t.labels.mapList { go(it) }, go(t.row))
            is TImplicit -> t.copy(go(t.type))
        }
        return ids to go(type)
    }

    private fun nestLambdas(binders: List<Binder>, exp: Expr): Expr {
        return if (binders.isEmpty()) exp
        else Expr.Lambda(binders[0], null, nestLambdas(binders.drop(1), exp), exp.span)
    }
    
    private fun nestLambdaPatterns(pats: List<SPattern>, exp: Expr, locals: List<String>): Expr {
        return if (pats.isEmpty()) exp
        else {
            when (val pat = pats[0]) {
                is SPattern.Var -> Expr.Lambda(
                    Binder(pat.name, pat.span, false),
                    null,
                    nestLambdaPatterns(pats.drop(1), exp, locals),
                    exp.span
                )
                is SPattern.ImplicitVar -> Expr.Lambda(
                    Binder(pat.name, pat.span, true),
                    null,
                    nestLambdaPatterns(pats.drop(1), exp, locals),
                    exp.span
                )
                else -> {
                    val vars = pats.map { Expr.Var(newVar(), it.span) }
                    val expr = Expr.Match(vars, listOf(Case(pats.map { it.desugar(locals) }, exp)), exp.span)
                    nestLambdas(vars.map { Binder(it.name, it.span) }, expr)
                }
            }
        }
    }

    private fun nestLets(defs: List<SLetDef>, exp: Expr, locals: List<String>): Expr {
        return if (defs.isEmpty()) exp
        else {
            when (val ld = defs[0]) {
                is SLetDef.DefBind -> {
                    Expr.Let(ld.desugar(locals), nestLets(defs.drop(1), exp, locals), exp.span)
                }
                is SLetDef.DefPattern -> {
                    val case = Case(listOf(ld.pat.desugar(locals)), nestLets(defs.drop(1), exp, locals))
                    Expr.Match(listOf(ld.expr.desugar(locals)), listOf(case), Span.new(ld.pat.span, exp.span))
                }
            }
        }
    }
    
    private fun nestRecordSelects(exp: Expr, labels: List<String>): Expr {
        return if (labels.isEmpty()) exp
        else nestRecordSelects(Expr.RecordSelect(exp, labels[0], exp.span), labels.drop(1))
    }

    private fun collectVars(exp: Expr): List<String> =
        exp.everywhereAccumulating { e -> if (e is Expr.Var) listOf(e.name) else emptyList() }

    /**
     * Expand type aliases and make sure they are not recursive
     */
    private fun validateTypealiases(): Map<String, TypealiasDecl> {
        val typealiases = smod.decls.filterIsInstance<TypealiasDecl>()
        smod.resolvedTypealiases.forEach { ta ->
            if (ta.expanded == null) internalError("Got unexpanded imported typealias: $ta")
        }
        val map = (typealiases + smod.resolvedTypealiases).map { it.name to it }.toMap()

        fun expandAndcheck(name: String, ty: SType, span: Span, vars: List<SType> = listOf()): SType = when (ty) {
            is SType.TConst -> {
                if (ty.name == name) parserError(E.recursiveAlias(name), span)
                val pointer = map[ty.fullname()]
                if (pointer != null) {
                    if (vars.size != pointer.tyVars.size)
                        parserError(E.partiallyAppliedAlias(pointer.name, pointer.tyVars.size, vars.size), span)

                    if (vars.isEmpty() && pointer.tyVars.isEmpty()) expandAndcheck(name, pointer.type, span)
                    else {
                        val substMap = pointer.tyVars.zip(vars).toMap()
                        expandAndcheck(name, pointer.type.substVars(substMap), span)
                    }
                } else ty
            }
            is SType.TFun -> ty.copy(expandAndcheck(name, ty.arg, span), expandAndcheck(name, ty.ret, span))
            is SType.TForall -> ty.copy(type = expandAndcheck(name, ty.type, span))
            is SType.TApp -> {
                val resolvedTypes = ty.types.map { expandAndcheck(name, it, span) }
                val typ = expandAndcheck(name, ty.type, span, resolvedTypes)
                if (typ is SType.TConst) ty.copy(type = typ, types = resolvedTypes)
                else typ
            }
            is SType.TParens -> ty.copy(expandAndcheck(name, ty.type, span))
            is SType.TRecord -> ty.copy(row = expandAndcheck(name, ty.row, span))
            is SType.TRowEmpty -> ty
            is SType.TRowExtend -> ty.copy(
                row = expandAndcheck(name, ty.row, span),
                labels = ty.labels.map { (k, v) -> k to expandAndcheck(name, v, span) }
            )
            is SType.TImplicit -> ty.copy(expandAndcheck(name, ty.type, span))
        }
        typealiases.forEach { ta ->
            ta.expanded = expandAndcheck(ta.name, ta.type, ta.span)
        }
        val errs = validatePublicAliases(smod)
        if (errs.isNotEmpty()) desugarErrors(errs)
        return map
    }

    /**
     * Resolve all type aliases in this type
     */
    private fun resolveAliases(ty: SType): SType {
        return when (ty) {
            is SType.TConst -> synonyms[ty.name]?.expanded ?: ty
            is SType.TParens -> ty.copy(type = resolveAliases(ty.type))
            is SType.TForall -> ty.copy(type = resolveAliases(ty.type))
            is SType.TFun -> ty.copy(arg = resolveAliases(ty.arg), ret = resolveAliases(ty.ret))
            is SType.TApp -> {
                val typ = ty.type as SType.TConst
                val syn = synonyms[typ.name]
                if (syn != null) {
                    val synTy = syn.expanded ?: internalError("Got unexpanded typealias: $syn")
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
            exp.everywhere { e ->
                when (e) {
                    is Expr.Var -> deps += e.fullname()
                    is Expr.ImplicitVar -> deps += e.fullname()
                    is Expr.Constructor -> deps += e.fullname()
                    else -> {
                    }
                }
                e
            }
            return deps
        }

        val (decls, types) = desugared.partitionIsInstance<Decl.ValDecl, Decl>()
        val deps = decls.map { it.name to collectDependencies(it.exp) }.toMap()

        val dag = DAG<String, Decl.ValDecl>()
        val nodes = decls.map { it.name to DagNode(it.name, it) }.toMap()
        dag.addNodes(nodes.values)

        nodes.values.forEach { node ->
            deps[node.value]?.forEach { name ->
                nodes[name]?.let { dep ->
                    val (d1, d2) = dep.data to node.data
                    when {
                        // variables cannot have cycles
                        isVariable(d1.exp) || isVariable(d2.exp) -> dep.link(node)
                        // functions can be recursive
                        d1.name == d2.name -> {
                        }
                        // functions can only be mutually recursive if they have type annotations
                        d1.type == null || d2.type == null -> dep.link(node)
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
                if (dc.name == typeName)
                    parserError(E.wrongConstructorName(typeName), dd.span)
            }
        }
    }

    /**
     * Converts the let statements inside a do into
     * let expressions where the body is every expression
     * that comes after.
     */
    private fun convertDoLets(exprs: List<SExpr>): List<SExpr> {
        return if (exprs.filterIsInstance<SExpr.DoLet>().isEmpty()) exprs
        else {
            val lets = mutableListOf<SLetDef>()
            var exp = exprs[0]
            var i = 0
            while (exp is SExpr.DoLet) {
                lets.addAll(exp.letDefs)
                exp = exprs[++i]
            }
            if (lets.isEmpty()) listOf(exprs[0]) + convertDoLets(exprs.drop(1))
            else {
                val rest = convertDoLets(exprs.subList(i, exprs.size))
                if (rest.size == 1) {
                    listOf(SExpr.Let(lets, rest[0]))
                } else {
                    listOf(SExpr.Let(lets, SExpr.Do(rest)))
                }
            }
        }
    }

    /**
     * Check if a native expression has the correct number
     * of parameters as they can't be partially applied.
     */
    private fun validateNativeCall(exp: Expr, depth: Int) {
        fun throwArgs(name: String, span: Span, ctx: String, should: Int, got: Int): Nothing {
            parserError(
                E.wrongArgsToNative(name, ctx, should, got),
                span
            )
        }
        when (exp) {
            is Expr.NativeFieldGet -> {
                if (!exp.isStatic && depth < 1) throwArgs(exp.name, exp.span, "getter", 1, depth)
            }
            is Expr.NativeFieldSet -> {
                val should = if (exp.isStatic) 1 else 2
                if (depth != should) throwArgs(exp.name, exp.span, "setter", should, depth)
            }
            is Expr.NativeMethod -> {
                var count = exp.method.parameterCount
                if (!exp.isStatic) count++
                if (depth != count) throwArgs(exp.name, exp.span, "method", count, depth)
            }
            is Expr.NativeConstructor -> {
                val count = max(exp.ctor.parameterCount, 1)
                if (depth != count) throwArgs(exp.name, exp.span, "constructor", count, depth)
            }
            else -> {
            }
        }
    }

    private fun addUnusedVars(unusedVars: Map<String, Span>) {
        val err = CompilerProblem(
            E.unusedVariables(unusedVars.keys.toList()),
            ProblemContext.DESUGAR,
            unusedVars.values.first(),
            smod.sourceName,
            smod.name
        )
        errors += err
    }

    private fun desugarErrors(errs: List<CompilerProblem>): Nothing {
        throw CompilationError(errs)
    }

    private fun parserError(msg: String, span: Span): Nothing = throw ParserError(msg, span)

    private fun makeWarn(msg: String, span: Span): CompilerProblem =
        CompilerProblem(msg, ProblemContext.DESUGAR, span, smod.sourceName, smod.name, null, Severity.WARN)

    private fun makeError(msg: String, span: Span): CompilerProblem =
        CompilerProblem(msg, ProblemContext.DESUGAR, span, smod.sourceName, smod.name)
}