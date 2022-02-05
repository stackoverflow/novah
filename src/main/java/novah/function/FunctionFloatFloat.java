package novah.function;

public interface FunctionFloatFloat extends Function<Float, Float> {

    @Override
    default Float apply(Float arg) {
        return applyFloat(arg.floatValue());
    }

    @Override
    default Float applyF(float arg) {
        return applyFloat(arg);
    }

    @Override
    default float applyFloat(Float arg) {
        return applyFloat(arg.floatValue());
    }
}