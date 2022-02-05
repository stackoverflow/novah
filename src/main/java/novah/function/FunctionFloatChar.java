package novah.function;

public interface FunctionFloatChar extends Function<Float, Character> {

    @Override
    default Character apply(Float arg) {
        return applyChar(arg.floatValue());
    }

    @Override
    default Character applyF(float arg) {
        return applyChar(arg);
    }

    @Override
    default char applyChar(Float arg) {
        return applyChar(arg.floatValue());
    }
}