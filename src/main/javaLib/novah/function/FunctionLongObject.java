package novah.function;

public interface FunctionLongObject<R> extends Function<Long, R> {

    @Override
    default R apply(Long arg) {
        return applyJ(arg);
    }
}