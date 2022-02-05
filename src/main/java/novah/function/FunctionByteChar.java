package novah.function;

public interface FunctionByteChar extends Function<Byte, Character> {

    @Override
    default Character apply(Byte arg) {
        return applyChar(arg.byteValue());
    }

    @Override
    default Character applyB(byte arg) {
        return applyChar(arg);
    }

    @Override
    default char applyChar(Byte arg) {
        return applyChar(arg.byteValue());
    }
}