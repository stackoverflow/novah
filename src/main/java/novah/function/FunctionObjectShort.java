package novah.function;

public interface FunctionObjectShort<T> extends Function<T, Short> {

    @Override
    default Short apply(T arg) {
        return applyShort(arg);
    }
}