package novah.function;

public interface FunctionLongInt extends Function<Long, Integer> {

    @Override
    default Integer apply(Long arg) {
        return applyInt(arg.longValue());
    }

    @Override
    default Integer applyJ(long arg) {
        return applyInt(arg);
    }

    @Override
    default int applyInt(Long arg) {
        return applyInt(arg.longValue());
    }
}