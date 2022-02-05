package novah.function;

public interface FunctionObjectLong<T> extends Function<T, Long> {

    @Override
    default Long apply(T arg) {
        return applyLong(arg);
    }
}