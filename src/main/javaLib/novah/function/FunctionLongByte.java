package novah.function;

public interface FunctionLongByte extends Function<Long, Byte> {

    @Override
    default Byte apply(Long arg) {
        return applyByte(arg.longValue());
    }

    @Override
    default Byte applyJ(long arg) {
        return applyByte(arg);
    }

    @Override
    default byte applyByte(Long arg) {
        return applyByte(arg.longValue());
    }
}