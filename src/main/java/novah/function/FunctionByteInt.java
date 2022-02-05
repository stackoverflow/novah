package novah.function;

public interface FunctionByteInt extends Function<Byte, Integer> {

    @Override
    default Integer apply(Byte arg) {
        return applyInt(arg.byteValue());
    }

    @Override
    default Integer applyB(byte arg) {
        return applyInt(arg);
    }

    @Override
    default int applyInt(Byte arg) {
        return applyInt(arg.byteValue());
    }
}