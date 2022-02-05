package novah.function;

public interface FunctionShortInt extends Function<Short, Integer> {

    @Override
    default Integer apply(Short arg) {
        return applyInt(arg.shortValue());
    }

    @Override
    default Integer applyS(short arg) {
        return applyInt(arg);
    }

    @Override
    default int applyInt(Short arg) {
        return applyInt(arg.shortValue());
    }
}