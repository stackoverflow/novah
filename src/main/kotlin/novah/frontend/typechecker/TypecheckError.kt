package novah.frontend.typechecker

import novah.frontend.Expression
import novah.frontend.show
import java.lang.RuntimeException

class TypecheckError(private val exp: Expression, private val msg: String) : RuntimeException() {
    override val message: String?
        get() = "$msg\n\tin: ${exp.show()}"
}

object TypeErrors {

    fun typeNotFound(name: String) = """Could not infer the type of $name.
        |Consider adding a type annotation.
    """.trimMargin()
}