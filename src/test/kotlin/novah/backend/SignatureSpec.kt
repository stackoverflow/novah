package novah.backend

import io.kotest.core.spec.style.StringSpec
import io.kotest.data.blocking.forAll
import io.kotest.data.row
import io.kotest.matchers.shouldBe
import novah.ast.optimized.Type
import novah.backend.TypeUtil.buildClassSignature
import novah.backend.TypeUtil.maybeBuildFieldSignature
import novah.frontend.TestUtil
import novah.frontend.TestUtil.preCompile
import java.io.File

class SignatureSpec : StringSpec({

    fun tvar(n: String) = Type.TVar(n)
    fun tfvar(n: String) = Type.TVar(n, true)
    fun tcons(n: String, t: Type) = Type.TConstructor(n, listOf(t))
    fun tfun(t1: Type, t2: Type) = Type.TFun(t1, t2)

    beforeTest {
        TestUtil.setupContext()
    }

    "class signatures are correctly generated" {

        val maybeSig = buildClassSignature(listOf("a"))
        val justSig = buildClassSignature(listOf("a"), "novah/Maybe")

        val daySig = buildClassSignature(listOf())
        val weekdaySig = buildClassSignature(listOf(), "novah/Day")

        val resultSig = buildClassSignature(listOf("k", "e"))
        val okSig = buildClassSignature(listOf("k", "e"), "novah/Result")

        maybeSig shouldBe "<A:Ljava/lang/Object;>Ljava/lang/Object;"
        justSig shouldBe "<A:Ljava/lang/Object;>Lnovah/Maybe<TA;>;"
        daySig shouldBe null
        weekdaySig shouldBe null
        resultSig shouldBe "<K:Ljava/lang/Object;E:Ljava/lang/Object;>Ljava/lang/Object;"
        okSig shouldBe "<K:Ljava/lang/Object;E:Ljava/lang/Object;>Lnovah/Result<TK;TE;>;"
    }

    "field signatures are correctly generated" {

        val funListString = tfun(
            tcons("java/util/List", tvar("java/lang/String")),
            tcons("java/util/List", tvar("java/lang/String"))
        )

        forAll(
            row(tvar("java/lang/Integer"), null),
            row(tvar("java/lang/String"), null),
            row(tcons("JArray", tvar("java/lang/String")), null),
            row(tcons("java/util/List", tfvar("A")), "Ljava/util/List<TA;>;"),
            row(
                funListString,
                "Ljava/util/function/Function<Ljava/util/List<Ljava/lang/String;>;Ljava/util/List<Ljava/lang/String;>;>;"
            ),
            row(tfvar("T"), "TT;"),
            row(tfun(tfvar("T"), tfvar("T")), "Ljava/util/function/Function<TT;TT;>;"),
            row(tfun(tfvar("T"), tcons("java/util/List", tfvar("T"))), "Ljava/util/function/Function<TT;Ljava/util/List<TT;>;>;")
        ) { type, expected ->
            maybeBuildFieldSignature(type) shouldBe expected
        }
    }

    "test full compilation" {
        val code = """
            module novah.test // exposing ( num, y )
            
            type Maybe a = Just a | Nothing
            
            type Day = Weekday | Weekend
            
            type Result k e = Ok k | Err e
            
            num = 2
            
            y = 45.8E12
            
            //b2 = b
            
            z = "something"
            
            b = true
            
            c = 'a'
            
            d x = do
              4
              true
              "asd"
            
            i = 99
            
            l x = let l1 = toString 4
                      l2 = toString l1
                  in l2
            
            w = \x -> x
            
            lamb :: Int -> String -> Unit
            lamb x y = do
              println y
              println (toString x)
              Just "a"
              println (toString (Just "a"))
            
            main args = do
              println "running novah"
              lamb 4 "hello"
        """.trimIndent()

        val outputDir = "output"
        val ast = preCompile(code, "novah/test.novah")
        val codegen = Codegen(ast) { dirName, fileName, bytes ->
            val file = File("$outputDir/$dirName/$fileName.class")
            File("$outputDir/$dirName").mkdirs()
            file.writeBytes(bytes)
        }
        codegen.run()
    }

    "test full comp" {
        val code = """
            module novah.test
            
            type Maybe a = Just a | Nothing
            
            f x = Just x
            
            main args = do
              println (toString (f "123"))
        """.trimIndent()

        val outputDir = "output"
        val ast = preCompile(code, "novah/test.novah")
        val codegen = Codegen(ast) { dirName, fileName, bytes ->
            val file = File("$outputDir/$dirName/$fileName.class")
            File("$outputDir/$dirName").mkdirs()
            file.writeBytes(bytes)
        }
        codegen.run()
    }
})