package novah.frontend.hmftypechecker

class Env(initial: Map<String, Type> = emptyMap()) {
    
    private val env = HashMap(initial)
    
    val empty = emptyMap<String, Type>()
    
    fun extend(name: String, type: Type) {
        env[name] = type
    }
    
    fun lookup(name: String): Type? = env[name]
    
    fun makeExtension() = Env(env)
}