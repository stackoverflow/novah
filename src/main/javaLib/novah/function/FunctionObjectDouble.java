package novah.function;

public interface FunctionObjectDouble<T> extends Function<T, Double> {

    @Override
    default Double apply(T arg) {
        return applyDouble(arg);
    }
}