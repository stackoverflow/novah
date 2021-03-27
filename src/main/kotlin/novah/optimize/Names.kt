package novah.optimize

import java.lang.StringBuilder

object Names {

    /**
     * Converts a novah name to Java.
     */
    fun convert(name: String): String {
        val b = StringBuilder()
        for (c in name) {
            val str = chars[c]
            if (str != null) b.append(str)
            else b.append(c)
        }
        return b.toString()
    }
    
    // novah operators
    private val chars = mapOf(
        '$' to "\$dollar",
        '=' to "\$equals",
        '<' to "\$smaller",
        '>' to "\$greater",
        '|' to "\$pipe",
        '&' to "\$and",
        '+' to "\$plus",
        '-' to "\$minus",
        ':' to "\$colon",
        '*' to "\$times",
        '/' to "\$slash",
        '%' to "\$percent",
        '^' to "\$hat",
        '.' to "\$dot",
        '?' to "\$question",
    )
}