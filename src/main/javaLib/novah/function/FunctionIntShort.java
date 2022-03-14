package novah.function;

public interface FunctionIntShort extends Function<Integer, Short> {

    @Override
    default Short apply(Integer arg) {
        return applyShort(arg.intValue());
    }

    @Override
    default Short applyI(int arg) {
        return applyShort(arg);
    }

    @Override
    default short applyShort(Integer arg) {
        return applyShort(arg.intValue());
    }
}