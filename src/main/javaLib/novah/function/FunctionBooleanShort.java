package novah.function;

public interface FunctionBooleanShort extends Function<Boolean, Short> {

    @Override
    default Short apply(Boolean arg) {
        return applyShort(arg.booleanValue());
    }

    @Override
    default Short applyZ(boolean arg) {
        return applyShort(arg);
    }

    @Override
    default short applyShort(Boolean arg) {
        return applyShort(arg.booleanValue());
    }
}