package novah.function;

public interface FunctionLongDouble extends Function<Long, Double> {

    @Override
    default Double apply(Long arg) {
        return applyDouble(arg.longValue());
    }

    @Override
    default Double applyJ(long arg) {
        return applyDouble(arg);
    }

    @Override
    default double applyDouble(Long arg) {
        return applyDouble(arg.longValue());
    }
}