package novah.function;

public interface FunctionShortShort extends Function<Short, Short> {

    @Override
    default Short apply(Short arg) {
        return applyShort(arg.shortValue());
    }

    @Override
    default Short applyS(short arg) {
        return applyShort(arg);
    }

    @Override
    default short applyShort(Short arg) {
        return applyShort(arg.shortValue());
    }
}