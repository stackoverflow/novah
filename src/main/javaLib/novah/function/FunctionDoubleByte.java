package novah.function;

public interface FunctionDoubleByte extends Function<Double, Byte> {

    @Override
    default Byte apply(Double arg) {
        return applyByte(arg.doubleValue());
    }

    @Override
    default Byte applyD(double arg) {
        return applyByte(arg);
    }

    @Override
    default byte applyByte(Double arg) {
        return applyByte(arg.doubleValue());
    }
}