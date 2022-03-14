package novah.function;

public interface FunctionLongLong extends Function<Long, Long> {

    @Override
    default Long apply(Long arg) {
        return applyLong(arg.longValue());
    }

    @Override
    default Long applyJ(long arg) {
        return applyLong(arg);
    }

    @Override
    default long applyLong(Long arg) {
        return applyLong(arg.longValue());
    }
}