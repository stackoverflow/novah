package novah.function;

public interface FunctionByteByte extends Function<Byte, Byte> {

    @Override
    default Byte apply(Byte arg) {
        return applyByte(arg.byteValue());
    }

    @Override
    default Byte applyB(byte arg) {
        return applyByte(arg);
    }

    @Override
    default byte applyByte(Byte arg) {
        return applyByte(arg.byteValue());
    }
}