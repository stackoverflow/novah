package novah.function;

public interface FunctionObjectFloat<T> extends Function<T, Float> {

    @Override
    default Float apply(T arg) {
        return applyFloat(arg);
    }
}