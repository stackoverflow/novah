package novah.function;

public interface FunctionLongBoolean extends Function<Long, Boolean> {

    @Override
    default Boolean apply(Long arg) {
        return applyBoolean(arg.longValue());
    }

    @Override
    default Boolean applyJ(long arg) {
        return applyBoolean(arg);
    }

    @Override
    default boolean applyBoolean(Long arg) {
        return applyBoolean(arg.longValue());
    }
}