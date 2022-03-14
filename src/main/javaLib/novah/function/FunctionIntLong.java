package novah.function;

public interface FunctionIntLong extends Function<Integer, Long> {

    @Override
    default Long apply(Integer arg) {
        return applyLong(arg.intValue());
    }

    @Override
    default Long applyI(int arg) {
        return applyLong(arg);
    }

    @Override
    default long applyLong(Integer arg) {
        return applyLong(arg.intValue());
    }
}