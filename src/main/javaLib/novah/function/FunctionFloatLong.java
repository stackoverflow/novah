package novah.function;

public interface FunctionFloatLong extends Function<Float, Long> {

    @Override
    default Long apply(Float arg) {
        return applyLong(arg.floatValue());
    }

    @Override
    default Long applyF(float arg) {
        return applyLong(arg);
    }

    @Override
    default long applyLong(Float arg) {
        return applyLong(arg.floatValue());
    }
}