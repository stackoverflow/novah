package novah.function;

public interface FunctionByteShort extends Function<Byte, Short> {

    @Override
    default Short apply(Byte arg) {
        return applyShort(arg.byteValue());
    }

    @Override
    default Short applyB(byte arg) {
        return applyShort(arg);
    }

    @Override
    default short applyShort(Byte arg) {
        return applyShort(arg.byteValue());
    }
}