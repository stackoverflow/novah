package novah.function;

public interface FunctionObjectChar<T> extends Function<T, Character> {

    @Override
    default Character apply(T arg) {
        return applyChar(arg);
    }
}