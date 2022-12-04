package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.formatter.Formatter
import novah.frontend.TestUtil.module

class FormatterSpec : StringSpec({

    fun expect(input: String, output: String) {
        val mod = TestUtil.parseString(input.module())

        Formatter().format(mod) shouldBe output.module()
    }

    "let test" {
        expect(
            input = """
                foo () =
                  let bar : Int -> Unit
                      bar x = println x
                  bar 1
            """,
            output = """
                foo () =
                  let bar : Int -> Unit
                      bar x = println x
                  bar 1
            """
        )
    }

    "try catch test" {
        expect(
            input = """
                foo =
                  try danger ()
                  catch
                    :? Exception -> ()
                  finally println "done"
            """,
            output = """
                foo =
                  try
                    danger ()
                  catch
                    :? Exception -> ()
                  finally
                    println "done"
            """
        )
    }

    "literals test" {
        expect(
            input = """
                val = 12L
                
                val2 = 13F
                
                val3 = 18.4e10F
                
                val4 = 78N
                
                val5 = 13.7M
                
                val6 = 0x3AF
                
                val7 = 0x3AFL
                
                val8 = 0b011
                
                val9 = 0b011L
                
                val10 = #"[^1]\d{10}"
            """,
            output = """
                val = 12L
                
                val2 = 13F
                
                val3 = 18.4e10F
                
                val4 = 78N
                
                val5 = 13.7M
                
                val6 = 0x3AF
                
                val7 = 0x3AFL
                
                val8 = 0b011
                
                val9 = 0b011L
                
                val10 = #"[^1]\d{10}"
            """
        )
    }

    "imports test" {
        expect(
            input = """
                foreign import java.util.A
                foreign import java.io.A
                foreign import begin.A
                
                import util
                import middle
                import begin
            """,
            output = """
                import begin
                import middle
                import util
                
                foreign import begin.A
                foreign import java.io.A
                foreign import java.util.A
            """
        )
    }

    "while test" {
        expect(
            input = """
                fun =
                  while true do
                    println 1
                    
                    println 2
                    println 3
            """,
            output = """
                fun =
                  while true do
                    println 1
                
                    println 2
                    println 3
            """
        )
    }

    "computation test" {
        expect(
            input = """
                fun1 =
                  do.list for x in [1 .. 10] do yield x
                
                fun2 =
                  let data = do.list
                    for x in [0 .. 9] do
                      yield List.count (_ == x) input
                  
                  let data2 =
                    do.list
                      for x in [0 .. 9] do yield x
            """,
            output = """
                fun1 = do.list
                  for x in [1 .. 10] do yield x
                
                fun2 =
                  let data = do.list
                    for x in [0 .. 9] do
                      yield List.count (_ == x) input
                
                  let data2 =
                    do.list
                      for x in [0 .. 9] do yield x
            """
        )
    }

    "types test" {
        expect(
            input = """
                pub
                type Pair a = Pair Int a
                
                type MyRec = MyRec { veryLongName : VeryLongValue VeryLong, anotherVeryLongName : Type, otherField : String }
                
                fun1 : SomeVery Long name -> { foooo : Typeeeeee, baaaar : Typeeeeee } -> Return Type valueeee
                fun1 = 0
                
                fun2 : { veryLongName : VeryLongValue veryLong, anotherVeryLongName : Type, otherField : String } -> Int
                fun2 = 0
            """,
            output = """
                pub
                type Pair a = Pair Int a
                
                type MyRec =
                  MyRec { veryLongName : VeryLongValue VeryLong
                    , anotherVeryLongName : Type
                    , otherField : String
                    }
                
                fun1
                  : SomeVery Long name
                  -> { foooo : Typeeeeee, baaaar : Typeeeeee }
                  -> Return Type valueeee
                fun1 = 0
                
                fun2
                  : { veryLongName : VeryLongValue veryLong
                    , anotherVeryLongName : Type
                    , otherField : String
                    }
                  -> Int
                fun2 = 0
            """
        )
    }

    "attributes test" {
        expect(
            input = """
                #[foo: "string", bar: [5, 6, 7], noWarn]
                input : Int
                input = 1
            """,
            output = """
                #[foo: "string", bar: [5, 6, 7], noWarn]
                input : Int
                input = 1
            """
        )
    }

    "long function calls test" {
        expect(
            input = """
                input =
                  readProcessTestData 8 "\n"
                    (String.split "\\|" >> List.map (String.strip >> String.split " "))
            """,
            output = """
                input =
                  readProcessTestData
                    8
                    "\n"
                    (String.split "\\|" >> List.map (String.strip >> String.split " "))
            """
        )
    }

    "nested lambdas test" {
        expect(
            input = """
                flash octs =
                  let toFlash = linear []
                  List.forEachIndexed octs (\y l -> List.forEachIndexed l (\x v ->
                    if v == 10 then
                      flashes#update(_ + 1)
                      addAll toFlash (neighbors x y)
                      ()
                  ))
            """,
            output = """
                flash octs =
                  let toFlash = linear []
                  List.forEachIndexed octs (\y l ->
                    List.forEachIndexed l (\x v ->
                      if v == 10 then
                        flashes#update(_ + 1)
                        addAll toFlash (neighbors x y)
                        ()))
            """
        )
    }

    "cases test" {
        expect(
            input = """
                fun =
                  let f = case _ of
                   1 -> 1
                   _ -> 0
                  
                  let f2 =
                    case _ of
                      1 -> 1
                      _ -> 0
                  
                  let f3 = case _ of
                    #"[^1]\d{10}" -> 0
                    _ -> 1
            """,
            output = """
                fun =
                  let f = case _ of
                    1 -> 1
                    _ -> 0
                
                  let f2 =
                    case _ of
                      1 -> 1
                      _ -> 0
                
                  let f3 = case _ of
                    #"[^1]\d{10}" -> 0
                    _ -> 1
            """
        )
    }

    "lists and sets test" {
        expect(
            input = """
                list1 =
                  [ 1, 2
                  , 3, 4]
                
                list2 =
                  [3, 4, 5]
                
                set1 =
                  #{3, 4, 5}
                
                set2 =
                  #{ 2
                   , 3, 4}
            """,
            output = """
                list1 =
                  [ 1
                  , 2
                  , 3
                  , 4
                  ]
                
                list2 =
                  [3, 4, 5]
                
                set1 =
                  #{3, 4, 5}
                
                set2 =
                  #{ 2
                   , 3
                   , 4
                   }
            """
        )
    }

    "records test" {
        expect(
            input = """
                rec1 r =
                  { - foo, bar | r }
                
                rec2 r =
                  { - foo, bar
                    | r}
                
                rec3 =
                  {x:1, y: 2}
                
                rec4 =
                  {x: 1,
                   y: 2}
                
                mergeing =
                    { + { name: "Big Long Name", age: 13213123, city: "Big Long City Name" }, { eyeColor: "Redest blue" } }
            """,
            output = """
                rec1 r =
                  { - foo, bar | r }
                
                rec2 r =
                  { - foo
                    , bar
                    | r
                  }
                
                rec3 =
                  { x: 1, y: 2 }
                
                rec4 =
                  { x: 1
                  , y: 2
                  }
                
                mergeing =
                  { +
                    { name: "Big Long Name", age: 13213123, city: "Big Long City Name" }
                    ,
                    { eyeColor: "Redest blue" }
                  }
            """
        )
    }

    "simple and complex expressions test" {
        expect(
            input = """
                simple = 123.456F
                
                complex = fun [4, 5, 6]
                
                (!!!) : Map k v -> k -> v
                (!!!) m k =
                  (Map.get k m)!!
                
                matches =
                  case _ of
                    1 -> 1
                    _ -> 0
            """,
            output = """
                simple = 123.456F
                
                complex =
                  fun [4, 5, 6]
                
                (!!!) : Map k v -> k -> v
                (!!!) m k =
                  (Map.get k m)!!
                
                matches = case _ of
                  1 -> 1
                  _ -> 0
            """
        )
    }

    "comments test" {
        expect(
            input = """
                /* flying comment */

                // some
                // comment
                input = 1
            """,
            output = """
                // flying comment 
                
                // some
                // comment
                input = 1
            """
        )
    }

    "binary operators test" {
        expect(
            input = """
                input =
                  readTestData 16
                    |> String.toList
                    |> List.map (toString
                    >> (\x -> (Number.parseIntRadix 16 x)!!)
                    >> Int32#toBinaryString(_)
                    >> zeroPad 4)
                    |> String.join ""
                    |> fst
                
                input2 =
                  readTestData 20 |> String.split "\n\n" |> (\[algo, inp] ->
                    let image = String.lines inp |> List.map String.toList
                    String.toList algo ; image
                  )
            """,
            output = """
                input =
                  readTestData 16
                  |> String.toList
                  |> List.map (toString
                    >> (\x -> (Number.parseIntRadix 16 x)!!)
                    >> Int32#toBinaryString(_)
                    >> zeroPad 4)
                  |> String.join ""
                  |> fst
                
                input2 =
                  readTestData 20
                  |> String.split "\n\n"
                  |> (\[algo, inp] ->
                    let image = String.lines inp |> List.map String.toList
                    String.toList algo ; image)
            """
        )
    }
})