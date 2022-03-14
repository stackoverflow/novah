package novah.function;

public interface FunctionFloatShort extends Function<Float, Short> {

    @Override
    default Short apply(Float arg) {
        return applyShort(arg.floatValue());
    }

    @Override
    default Short applyF(float arg) {
        return applyShort(arg);
    }

    @Override
    default short applyShort(Float arg) {
        return applyShort(arg.floatValue());
    }
}