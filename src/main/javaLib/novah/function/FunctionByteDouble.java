package novah.function;

public interface FunctionByteDouble extends Function<Byte, Double> {

    @Override
    default Double apply(Byte arg) {
        return applyDouble(arg.byteValue());
    }

    @Override
    default Double applyB(byte arg) {
        return applyDouble(arg);
    }

    @Override
    default double applyDouble(Byte arg) {
        return applyDouble(arg.byteValue());
    }
}