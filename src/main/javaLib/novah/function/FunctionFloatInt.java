package novah.function;

public interface FunctionFloatInt extends Function<Float, Integer> {

    @Override
    default Integer apply(Float arg) {
        return applyInt(arg.floatValue());
    }

    @Override
    default Integer applyF(float arg) {
        return applyInt(arg);
    }

    @Override
    default int applyInt(Float arg) {
        return applyInt(arg.floatValue());
    }
}