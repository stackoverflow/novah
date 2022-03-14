package novah.function;

public interface FunctionByteFloat extends Function<Byte, Float> {

    @Override
    default Float apply(Byte arg) {
        return applyFloat(arg.byteValue());
    }

    @Override
    default Float applyB(byte arg) {
        return applyFloat(arg);
    }

    @Override
    default float applyFloat(Byte arg) {
        return applyFloat(arg.byteValue());
    }
}