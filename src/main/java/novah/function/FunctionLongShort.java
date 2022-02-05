package novah.function;

public interface FunctionLongShort extends Function<Long, Short> {

    @Override
    default Short apply(Long arg) {
        return applyShort(arg.longValue());
    }

    @Override
    default Short applyJ(long arg) {
        return applyShort(arg);
    }

    @Override
    default short applyShort(Long arg) {
        return applyShort(arg.longValue());
    }
}