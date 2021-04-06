package novah;

import java.util.function.Function;

/**
 * A recursive function used for recursive let bindings.
 */
public class RecFunction<T, R> {
    public Function<T, R> fun;
}