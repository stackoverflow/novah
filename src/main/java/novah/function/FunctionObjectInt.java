package novah.function;

public interface FunctionObjectInt<T> extends Function<T, Integer> {

    @Override
    default Integer apply(T arg) {
        return applyInt(arg);
    }
}