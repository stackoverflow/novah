package novah.function;

public interface FunctionIntByte extends Function<Integer, Byte> {

    @Override
    default Byte apply(Integer arg) {
        return applyByte(arg.intValue());
    }

    @Override
    default Byte applyI(int arg) {
        return applyByte(arg);
    }

    @Override
    default byte applyByte(Integer arg) {
        return applyByte(arg.intValue());
    }
}