package novah.function;

public interface FunctionByteBoolean extends Function<Byte, Boolean> {

    @Override
    default Boolean apply(Byte arg) {
        return applyBoolean(arg.byteValue());
    }

    @Override
    default Boolean applyB(byte arg) {
        return applyBoolean(arg);
    }

    @Override
    default boolean applyBoolean(Byte arg) {
        return applyBoolean(arg.byteValue());
    }
}