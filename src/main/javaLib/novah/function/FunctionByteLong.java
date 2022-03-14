package novah.function;

public interface FunctionByteLong extends Function<Byte, Long> {

    @Override
    default Long apply(Byte arg) {
        return applyLong(arg.byteValue());
    }

    @Override
    default Long applyB(byte arg) {
        return applyLong(arg);
    }

    @Override
    default long applyLong(Byte arg) {
        return applyLong(arg.byteValue());
    }
}