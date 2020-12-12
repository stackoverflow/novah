package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.ast.Desugar
import novah.ast.canonical.DataConstructor
import novah.ast.canonical.Decl
import novah.ast.canonical.Visibility
import novah.frontend.TestUtil.parseString

class DesugarSpec : StringSpec({

    fun List<Decl.DataDecl>.byName(n: String) = find { it.name == n }!!
    fun List<Decl.ValDecl>.byName(n: String) = find { it.name == n }!!
    fun List<DataConstructor>.byName(n: String) = find { it.name == n }!!

    "visibility is correctly set" {
        val code = """
            module test exposing
              ( x
              , AllVis(..)
              , NoVis
              , PartVis(PartVis2)
              )
            
            type AllVis = AllVis1 | AllVis2
            
            type NoVis = NoVis1 | NoVis2
            
            type PartVis = PartVis1 | PartVis2
            
            type Hidden = Hidden1
            
            x = 3
            
            y = true
        """.trimIndent()

        val ast = parseString(code)
        val dast = Desugar(ast).desugar()

        val tds = dast.decls.filterIsInstance<Decl.DataDecl>()

        val allVis = tds.byName("AllVis")
        allVis.visibility shouldBe Visibility.PUBLIC
        allVis.dataCtors.byName("AllVis1").visibility shouldBe Visibility.PUBLIC
        allVis.dataCtors.byName("AllVis2").visibility shouldBe Visibility.PUBLIC

        val noVis = tds.byName("NoVis")
        noVis.visibility shouldBe Visibility.PUBLIC
        noVis.dataCtors.byName("NoVis1").visibility shouldBe Visibility.PRIVATE
        noVis.dataCtors.byName("NoVis2").visibility shouldBe Visibility.PRIVATE

        val partVis = tds.byName("PartVis")
        partVis.visibility shouldBe Visibility.PUBLIC
        partVis.dataCtors.byName("PartVis1").visibility shouldBe Visibility.PRIVATE
        partVis.dataCtors.byName("PartVis2").visibility shouldBe Visibility.PUBLIC

        val hidden = tds.byName("Hidden")
        hidden.visibility shouldBe Visibility.PRIVATE
        hidden.dataCtors.byName("Hidden1").visibility shouldBe Visibility.PRIVATE

        val dds = dast.decls.filterIsInstance<Decl.ValDecl>()

        dds.byName("x").visibility shouldBe Visibility.PUBLIC
        dds.byName("y").visibility shouldBe Visibility.PRIVATE
    }
})