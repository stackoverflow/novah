package novah.main

import novah.ast.source.FullVisibility
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File

class Apidoc(private val env: Map<String, FullModuleEnv>) {

    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().build()

    fun generate(output: File) {
        val menu = buildMenu()
        val template = getTemplate().replace("#modules", menu)
        val index = output.resolve("index.html")
        val indexText = template.replace("#content", """
            <div class="docs">
                <p>
                Novah API documentation.<br/>
                Use the menu on the left to navigate.
                </p>
            </div>
        """.trimIndent())
        index.writeText(indexText, Charsets.UTF_8)

        for ((name, mod) in env.entries) {
            val file = output.resolve("$name.html")
            val text = template.replace("#content", genModule(mod))
            file.writeText(text, Charsets.UTF_8)
        }
    }

    private fun genModule(mod: FullModuleEnv): String {
        val builder = StringBuilder("<div class=\"docs\"><h2>${mod.ast.name.value}</h2>")
        if (mod.ast.comment != null) {
            builder.append(getComment(mod.ast.comment.comment))
        }
        builder.append("<h2>Types</h2>")
        for ((name, type) in mod.env.types) {
            builder.append("<hr>")
            builder.append("<h4>$name</h4>")
            when (type.visibility) {
                FullVisibility.PUBLIC_PLUS -> builder.append("<pre>visibility: public+</pre>")
                FullVisibility.PUBLIC -> builder.append("<pre>visibility: public</pre>")
                FullVisibility.PRIVATE -> builder.append("<pre>visibility: private</pre>")
            }
            if (type.ctors.isNotEmpty()) {
                builder.append("<pre>constructors: ${type.ctors.joinToString()}</pre>")
            }
            if (type.comment != null) {
                builder.append("<div>${getComment(type.comment.comment)}</div>")
            }
        }
        builder.append("<h2>Declarations</h2>")
        for ((name, decl) in mod.env.decls) {
            builder.append("<hr>")
            builder.append("<h4>$name</h4>")
            if (decl.visibility.isPublic()) {
                builder.append("<pre>visibility: public</pre>")
            } else {
                builder.append("<pre>visibility: private</pre>")
            }
            builder.append("<pre>type: ${decl.type.show(qualified = false, mod.typeVarsMap)}</pre>")
            if (decl.comment != null) {
                builder.append("<div>${getComment(decl.comment.comment)}</div>")
            }
        }
        return builder.toString()
    }

    private fun buildMenu(): String {
        val modList = StringBuilder("<div class=\"menu\"><h3>Modules</h3>")
        for (name in env.keys) {
            modList.append("<p><a href=\"$name.html\">")
            modList.append(name)
            modList.append("</a></p>")
        }
        modList.append("</div>")
        return modList.toString()
    }

    private fun getComment(comment: String): String {
        val doc = parser.parse(comment)
        return renderer.render(doc)
    }

    companion object {
        private fun getTemplate(): String {
            return Apidoc::class.java.classLoader.getResource("apidoc_main.html")!!.readText()
        }
    }
}