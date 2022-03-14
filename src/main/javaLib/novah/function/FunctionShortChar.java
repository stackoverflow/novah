package novah.function;

public interface FunctionShortChar extends Function<Short, Character> {

    @Override
    default Character apply(Short arg) {
        return applyChar(arg.shortValue());
    }

    @Override
    default Character applyS(short arg) {
        return applyChar(arg);
    }

    @Override
    default char applyChar(Short arg) {
        return applyChar(arg.shortValue());
    }
}