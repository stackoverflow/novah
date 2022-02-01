package novah;

/**
 * A simple mutable reference.
 * Should only be used for local mutation, never exposed.
 */
public class Ref<T> {
    public T val;
    
    public Ref(T value) {
        val = value;
    }
    
    public void update(Function<T, T> fun) {
        val = fun.apply(val);
    }
}
