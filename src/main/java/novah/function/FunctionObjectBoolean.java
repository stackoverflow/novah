package novah.function;

public interface FunctionObjectBoolean<T> extends Function<T, Boolean> {

    @Override
    default Boolean apply(T arg) {
        return applyBoolean(arg);
    }
}