package novah.range;

import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Set;
import novah.function.Function;
import novah.Unit;

import java.util.Iterator;

public interface Range<T> extends Iterable<T> {

    T start();
    T end();

    boolean contains(T x);

    boolean isEmpty();

    @Override
    Iterator<T> iterator();

    default List<T> toList() {
        return List.from(iterator());
    }

    default Set<T> toSet() {
        return Set.from(iterator());
    }

    default void foreach(Function<T, Unit> fun) {
        Iterator<T> it = iterator();
        while (it.hasNext()) {
            fun.apply(it.next());
        }
    }
}
