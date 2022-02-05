package novah.function;

public interface FunctionLongFloat extends Function<Long, Float> {

    @Override
    default Float apply(Long arg) {
        return applyFloat(arg.longValue());
    }

    @Override
    default Float applyJ(long arg) {
        return applyFloat(arg);
    }

    @Override
    default float applyFloat(Long arg) {
        return applyFloat(arg.longValue());
    }
}