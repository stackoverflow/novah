/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah;

import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Set;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Core language functions
 */
public class Core {
    
    public static int sum(int x, int y) {
        return x + y;
    }

    public static long sum(long x, long y) {
        return x + y;
    }

    public static float sum(float x, float y) {
        return x + y;
    }

    public static double sum(double x, double y) {
        return x + y;
    }

    public static int sub(int x, int y) {
        return x - y;
    }

    public static long sub(long x, long y) {
        return x - y;
    }

    public static float sub(float x, float y) {
        return x - y;
    }

    public static double sub(double x, double y) {
        return x - y;
    }

    public static int mult(int x, int y) {
        return x * y;
    }

    public static long mult(long x, long y) {
        return x * y;
    }

    public static float mult(float x, float y) {
        return x * y;
    }

    public static double mult(double x, double y) {
        return x * y;
    }

    public static int div(int x, int y) {
        return x / y;
    }

    public static long div(long x, long y) {
        return x / y;
    }

    public static float div(float x, float y) {
        return x / y;
    }

    public static double div(double x, double y) {
        return x / y;
    }
    
    public static <T> void println(T arg) {
        System.out.println(arg);
    }
    
    public static boolean equivalent(byte e1, byte e2) {
        return e1 == e2;
    }

    public static boolean equivalent(short e1, short e2) {
        return e1 == e2;
    }

    public static boolean equivalent(int e1, int e2) {
        return e1 == e2;
    }

    public static boolean equivalent(long e1, long e2) {
        return e1 == e2;
    }

    public static boolean equivalent(float e1, float e2) {
        return e1 == e2;
    }

    public static boolean equivalent(double e1, double e2) {
        return e1 == e2;
    }

    public static boolean equivalent(char e1, char e2) {
        return e1 == e2;
    }

    public static boolean equivalent(boolean e1, boolean e2) {
        return e1 == e2;
    }
    
    public static boolean equivalentObject(Object o1, Object o2) {
        if (o1 instanceof Integer && o2 instanceof Integer) {
            return ((Integer) o1).intValue() == ((Integer) o2).intValue();
        }
        if (o1 instanceof Byte && o2 instanceof Byte) {
            return ((Byte) o1).byteValue() == ((Byte) o2).byteValue();
        }
        if (o1 instanceof Short && o2 instanceof Short) {
            return ((Short) o1).shortValue() == ((Short) o2).shortValue();
        }
        if (o1 instanceof Long && o2 instanceof Long) {
            return ((Long) o1).longValue() == ((Long) o2).longValue();
        }
        if (o1 instanceof Float && o2 instanceof Float) {
            return ((Float) o1).floatValue() == ((Float) o2).floatValue();
        }
        if (o1 instanceof Double && o2 instanceof Double) {
            return ((Double) o1).doubleValue() == ((Double) o2).doubleValue();
        }
        if (o1 instanceof Character && o2 instanceof Character) {
            return ((Character) o1).charValue() == ((Character) o2).charValue();
        }
        if (o1 instanceof Boolean && o2 instanceof Boolean) {
            return ((Boolean) o1).booleanValue() == ((Boolean) o2).booleanValue();
        }
        return o1 == o2;
    }
    
    public static Function<Integer, Function<Integer, Object>> compareInt(Object lt, Object eq, Object gt) {
        return xx -> yy -> {
            int x = xx;
            int y = yy;
            return x == y ? eq : (x < y ? lt : gt);
        };
    }

    public static Function<Long, Function<Long, Object>> compareLong(Object lt, Object eq, Object gt) {
        return xx -> yy -> {
            long x = xx;
            long y = yy;
            return x == y ? eq : (x < y ? lt : gt);
        };
    }

    public static Function<Double, Function<Double, Object>> compareDouble(Object lt, Object eq, Object gt) {
        return xx -> yy -> {
            double x = xx;
            double y = yy;
            return x == y ? eq : (x < y ? lt : gt);
        };
    }
    
    public static Function<Character, Function<Character, Object>> compareChar(Object lt, Object eq, Object gt) {
        return x -> y -> {
            int comp = x.compareTo(y);
            return comp == 0 ? eq : (comp < 0 ? lt : gt);
        };
    }

    public static Function<String, Function<String, Object>> compareString(Object lt, Object eq, Object gt) {
        return x -> y -> {
            int comp = x.compareTo(y);
            return comp == 0 ? eq : (comp < 0 ? lt : gt);
        };
    }

    public static boolean greaterInt(int i1, int i2) {
        return i1 > i2;
    }

    public static boolean smallerInt(int i1, int i2) {
        return i1 < i2;
    }

    public static boolean greaterOrEqualsInt(int i1, int i2) {
        return i1 >= i2;
    }

    public static boolean smallerOrEqualsInt(int i1, int i2) {
        return i1 <= i2;
    }
    
    public static boolean greaterLong(long i1, long i2) {
        return i1 > i2;
    }

    public static boolean smallerLong(long i1, long i2) {
        return i1 < i2;
    }

    public static boolean greaterOrEqualsLong(long i1, long i2) {
        return i1 >= i2;
    }

    public static boolean smallerOrEqualsLong(long i1, long i2) {
        return i1 <= i2;
    }

    public static boolean greaterDouble(double d1, double d2) {
        return d1 > d2;
    }

    public static boolean smallerDouble(double d1, double d2) {
        return d1 < d2;
    }

    public static boolean greaterOrEqualsDouble(double d1, double d2) {
        return d1 >= d2;
    }

    public static boolean smallerOrEqualsDouble(double d1, double d2) {
        return d1 <= d2;
    }

    public static byte[] mkByteArray(int size) {
        return new byte[size];
    }

    public static short[] mkInt16Array(int size) {
        return new short[size];
    }

    public static int[] mkInt32Array(int size) {
        return new int[size];
    }

    public static long[] mkInt64Array(int size) {
        return new long[size];
    }

    public static float[] mkFloat32Array(int size) {
        return new float[size];
    }

    public static double[] mkFloat64Array(int size) {
        return new double[size];
    }

    public static char[] mkCharArray(int size) {
        return new char[size];
    }

    public static boolean[] mkBooleanArray(int size) {
        return new boolean[size];
    }
    
    public static Object[] mkObjectArray(int size) {
        return new Object[size];
    }
    
    public static Object getArray(int index, Object[] arr) {
        return arr[index];
    }
    
    public static void setArray(int index, Object val, Object[] arr) {
        arr[index] = val;
    }
    
    public static int getArrayLength(Object[] arr) {
        return arr.length;
    }
    
    public static boolean not(boolean b) {
        return !b;
    }
    
    public static boolean vectorNotEmpty(List<?> vec) {
        return vec.size() != 0;
    }
    
    public static List<Object> mapVector(Function<Object, Object> f, List<Object> vec) {
        var res = List.empty().linear();
        vec.stream().forEach((elem) -> res.addLast(f.apply(elem)));
        return res.forked();
    }
    
    public static Object[] mapArray(Function<Object, Object> f, Object[] arr) {
        var res = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) {
            res[i] = f.apply(arr[i]);
        }
        return res;
    }
    
    public static <T> boolean equalsVector(List<T> v1, List<T> v2, Function<T, Function<T, Boolean>> comp) {
        if (v1.size() != v2.size()) return false;
        
        long total = v1.size();
        for (long i = 0; i < total; i++) {
            if (!comp.apply(v1.nth(i)).apply(v2.nth(i))) return false;
        }
        return true;
    }

    public static <T> boolean equalsSet(Set<T> v1, Set<T> v2, Function<T, Function<T, Boolean>> comp) {
        if (v1.size() != v2.size()) return false;

        long total = v1.size();
        for (long i = 0; i < total; i++) {
            if (!comp.apply(v1.nth(i)).apply(v2.nth(i))) return false;
        }
        return true;
    }

    public static <T> boolean equalsArray(T[] v1, T[] v2, Function<T, Function<T, Boolean>> comp) {
        if (v1.length != v2.length) return false;

        int total = v1.length;
        for (int i = 0; i < total; i++) {
            if (!comp.apply(v1[i]).apply(v2[i])) return false;
        }
        return true;
    }
    
    public static <T> String toStringVector(List<T> vec, Function<T, String> show) {
        long size = vec.size();
        if (size == 0) return "[]";
        StringBuilder builder = new StringBuilder("[");
        builder.append(show.apply(vec.nth(0)));
        
        for (long i = 1; i < size; i++) {
            builder.append(", ");
            builder.append(show.apply(vec.nth(i)));
        }
        builder.append("]");
        return builder.toString();
    }

    public static <T> String toStringSet(Set<T> set, Function<T, String> show) {
        long size = set.size();
        if (size == 0) return "#{}";
        StringBuilder builder = new StringBuilder("#{");
        builder.append(show.apply(set.nth(0)));

        for (long i = 1; i < size; i++) {
            builder.append(", ");
            builder.append(show.apply(set.nth(i)));
        }
        builder.append("}");
        return builder.toString();
    }
    
    public static <T> String toStringArray(T[] array, Function<T, String> show) {
        int size = array.length;
        if (size == 0) return "[]";
        StringBuilder builder = new StringBuilder("[");
        builder.append(show.apply(array[0]));

        for (int i = 1; i < size; i++) {
            builder.append(", ");
            builder.append(show.apply(array[i]));
        }
        builder.append("]");
        return builder.toString();
    }
    
    public static <T> T swapAtom(AtomicReference<T> atom, Function<T, T> f) {
        for (;;) {
            T v = atom.get();
            T newv = f.apply(v);
            if (atom.compareAndSet(v, newv)) {
                return newv;
            }
        }
    }
    
    public static boolean and(boolean cond1, boolean cond2) {
        return cond1 && cond2;
    }

    public static boolean or(boolean cond1, boolean cond2) {
        return cond1 || cond2;
    }
    
    @SuppressWarnings("unchecked")
    public static <T, T2> T unsafeCoerce(T2 o) {
        return (T) o;
    }
}
