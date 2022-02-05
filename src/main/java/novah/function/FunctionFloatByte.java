package novah.function;

public interface FunctionFloatByte extends Function<Float, Byte> {

    @Override
    default Byte apply(Float arg) {
        return applyByte(arg.floatValue());
    }

    @Override
    default Byte applyF(float arg) {
        return applyByte(arg);
    }

    @Override
    default byte applyByte(Float arg) {
        return applyByte(arg.floatValue());
    }
}