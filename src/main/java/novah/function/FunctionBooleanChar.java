package novah.function;

public interface FunctionBooleanChar extends Function<Boolean, Character> {

    @Override
    default Character apply(Boolean arg) {
        return applyChar(arg.booleanValue());
    }

    @Override
    default Character applyZ(boolean arg) {
        return applyChar(arg);
    }

    @Override
    default char applyChar(Boolean arg) {
        return applyChar(arg.booleanValue());
    }
}