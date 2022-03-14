package novah.function;

public interface FunctionIntInt extends Function<Integer, Integer> {

    @Override
    default Integer apply(Integer arg) {
        return applyInt(arg.intValue());
    }

    @Override
    default Integer applyI(int arg) {
        return applyInt(arg);
    }

    @Override
    default int applyInt(Integer arg) {
        return applyInt(arg.intValue());
    }
}