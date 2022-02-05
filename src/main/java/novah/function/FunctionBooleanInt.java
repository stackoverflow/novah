package novah.function;

public interface FunctionBooleanInt extends Function<Boolean, Integer> {

    @Override
    default Integer apply(Boolean arg) {
        return applyInt(arg.booleanValue());
    }

    @Override
    default Integer applyZ(boolean arg) {
        return applyInt(arg);
    }

    @Override
    default int applyInt(Boolean arg) {
        return applyInt(arg.booleanValue());
    }
}