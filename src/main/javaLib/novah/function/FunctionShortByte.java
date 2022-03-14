package novah.function;

public interface FunctionShortByte extends Function<Short, Byte> {

    @Override
    default Byte apply(Short arg) {
        return applyByte(arg.shortValue());
    }

    @Override
    default Byte applyS(short arg) {
        return applyByte(arg);
    }

    @Override
    default byte applyByte(Short arg) {
        return applyByte(arg.shortValue());
    }
}