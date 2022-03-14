package novah.function;

public interface FunctionBooleanByte extends Function<Boolean, Byte> {

    @Override
    default Byte apply(Boolean arg) {
        return applyByte(arg.booleanValue());
    }

    @Override
    default Byte applyZ(boolean arg) {
        return applyByte(arg);
    }

    @Override
    default byte applyByte(Boolean arg) {
        return applyByte(arg.booleanValue());
    }
}