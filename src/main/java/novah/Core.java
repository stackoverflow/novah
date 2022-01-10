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

import io.lacuna.bifurcan.IEntry;
import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Map;
import io.lacuna.bifurcan.Set;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public static <T> boolean equivalentObject(T o1, T o2) {
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

    @SuppressWarnings("unchecked")
    public static <T> T[] mkArray(Class<T> clazz, int size) {
        return (T[]) Array.newInstance(clazz, size);
    }

    public static List<Byte> fromByteArray(byte[] arr) {
        var list = new List<Byte>().linear();
        for (var e : arr) {
            list.addLast(e);
        }
        return list.forked();
    }

    public static List<Short> fromInt16Array(short[] arr) {
        var list = new List<Short>().linear();
        for (var e : arr) {
            list.addLast(e);
        }
        return list.forked();
    }

    public static List<Integer> fromInt32Array(int[] arr) {
        var list = new List<Integer>().linear();
        for (var e : arr) {
            list.addLast(e);
        }
        return list.forked();
    }

    public static List<Long> fromInt64Array(long[] arr) {
        var list = new List<Long>().linear();
        for (var e : arr) {
            list.addLast(e);
        }
        return list.forked();
    }

    public static List<Float> fromFloat32Array(float[] arr) {
        var list = new List<Float>().linear();
        for (var e : arr) {
            list.addLast(e);
        }
        return list.forked();
    }

    public static List<Double> fromFloat64Array(double[] arr) {
        var list = new List<Double>().linear();
        for (var e : arr) {
            list.addLast(e);
        }
        return list.forked();
    }

    public static List<Boolean> fromBooleanArray(boolean[] arr) {
        var list = new List<Boolean>().linear();
        for (var e : arr) {
            list.addLast(e);
        }
        return list.forked();
    }

    public static List<Character> fromCharArray(char[] arr) {
        var list = new List<Character>().linear();
        for (var e : arr) {
            list.addLast(e);
        }
        return list.forked();
    }

    public static byte[] toByteArray(List<Byte> list) {
        int size = (int) list.size();
        var arr = new byte[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.nth(i);
        }
        return arr;
    }

    public static short[] toInt16Array(List<Short> list) {
        int size = (int) list.size();
        var arr = new short[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.nth(i);
        }
        return arr;
    }

    public static int[] toInt32Array(List<Integer> list) {
        int size = (int) list.size();
        var arr = new int[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.nth(i);
        }
        return arr;
    }

    public static long[] toInt64Array(List<Long> list) {
        int size = (int) list.size();
        var arr = new long[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.nth(i);
        }
        return arr;
    }

    public static float[] toFloat32Array(List<Float> list) {
        int size = (int) list.size();
        var arr = new float[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.nth(i);
        }
        return arr;
    }

    public static double[] toFloat64Array(List<Double> list) {
        int size = (int) list.size();
        var arr = new double[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.nth(i);
        }
        return arr;
    }

    public static boolean[] toBooleanArray(List<Boolean> list) {
        int size = (int) list.size();
        var arr = new boolean[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.nth(i);
        }
        return arr;
    }

    public static char[] toCharArray(List<Character> list) {
        int size = (int) list.size();
        var arr = new char[size];
        for (int i = 0; i < size; i++) {
            arr[i] = list.nth(i);
        }
        return arr;
    }

    public static <T> T getArray(int index, T[] arr) {
        return arr[index];
    }

    public static <T> void setArray(int index, T val, T[] arr) {
        arr[index] = val;
    }

    public static <T> int getArrayLength(T[] arr) {
        return arr.length;
    }

    public static boolean not(boolean b) {
        return !b;
    }

    public static boolean listNotEmpty(List<?> list) {
        return list.size() != 0;
    }

    public static <T> T listGet(int index, List<T> list) {
        long idx = index >= 0 ? index : list.size() + index;
        return list.nth(idx);
    }

    public static <T> T setGet(int index, Set<T> set) {
        long idx = index >= 0 ? index : set.size() + index;
        return set.nth(idx);
    }

    public static <T> T arrayGet(int index, T[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static byte byteArrayGet(int index, byte[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static short shortArrayGet(int index, short[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static int intArrayGet(int index, int[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static long longArrayGet(int index, long[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static float floatArrayGet(int index, float[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static double doubleArrayGet(int index, double[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static boolean booleanArrayGet(int index, boolean[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static char charArrayGet(int index, char[] arr) {
        int idx = index >= 0 ? index : arr.length + index;
        return arr[idx];
    }

    public static void byteArraySet(int index, byte val, byte[] arr) {
        arr[index] = val;
    }

    public static void shortArraySet(int index, short val, short[] arr) {
        arr[index] = val;
    }

    public static void intArraySet(int index, int val, int[] arr) {
        arr[index] = val;
    }

    public static void longArraySet(int index, long val, long[] arr) {
        arr[index] = val;
    }

    public static void floatArraySet(int index, float val, float[] arr) {
        arr[index] = val;
    }

    public static void doubleArraySet(int index, double val, double[] arr) {
        arr[index] = val;
    }

    public static void booleanArraySet(int index, boolean val, boolean[] arr) {
        arr[index] = val;
    }

    public static void charArraySet(int index, char val, char[] arr) {
        arr[index] = val;
    }

    public static int byteArraySize(byte[] arr) {
        return arr.length;
    }

    public static int shortArraySize(short[] arr) {
        return arr.length;
    }

    public static int intArraySize(int[] arr) {
        return arr.length;
    }

    public static int longArraySize(long[] arr) {
        return arr.length;
    }

    public static int floatArraySize(float[] arr) {
        return arr.length;
    }

    public static int doubleArraySize(double[] arr) {
        return arr.length;
    }

    public static int booleanArraySize(boolean[] arr) {
        return arr.length;
    }

    public static int charArraySize(char[] arr) {
        return arr.length;
    }

    public static char stringGet(int index, String str) {
        int idx = index >= 0 ? index : str.length() + index;
        return str.charAt(idx);
    }

    public static <K, V> V mapGet(K key, Map<K, V> map) {
        if (!map.contains(key)) throw new Error("Key does not exist in map");
        return map.get(key, null);
    }

    public static <T> List<T> listDrop(int howMany, List<T> list) {
        return list.slice(howMany, list.size());
    }

    public static <T> boolean listMinSize(int minSize, List<T> list) {
        return list.size() >= minSize;
    }

    public static <T> boolean equalsList(List<T> v1, List<T> v2, Function<T, Function<T, Boolean>> comp) {
        if (v1.size() != v2.size()) return false;
        if (v1.hashCode() == v2.hashCode()) return true;

        for (long i = 0; i < v1.size(); i++) {
            if (!comp.apply(v1.nth(i)).apply(v2.nth(i))) return false;
        }
        return true;
    }

    public static <T> boolean equalsSet(Set<T> v1, Set<T> v2, Function<T, Function<T, Boolean>> comp) {
        if (v1.size() != v2.size()) return false;
        if (v1.hashCode() == v2.hashCode()) return true;

        for (long i = 0; i < v1.size(); i++) {
            if (!comp.apply(v1.nth(i)).apply(v2.nth(i))) return false;
        }
        return true;
    }

    public static <T> boolean equalsArray(T[] v1, T[] v2, Function<T, Function<T, Boolean>> comp) {
        if (v1.length != v2.length) return false;

        for (int i = 0; i < v1.length; i++) {
            if (!comp.apply(v1[i]).apply(v2[i])) return false;
        }
        return true;
    }

    public static <K, V> boolean equalsMap(Map<K, V> m1, Map<K, V> m2, Function<K, Function<K, Boolean>> keyComp
            , Function<V, Function<V, Boolean>> valComp) {
        if (m1.size() != m2.size()) return false;
        if (m1.hashCode() == m2.hashCode()) return true;

        for (long i = 0; i < m1.size(); i++) {
            var m1e = m1.nth(i);
            var m2e = m2.nth(i);
            if (!keyComp.apply(m1e.key()).apply(m2e.key()) || !valComp.apply(m1e.value()).apply(m2e.value()))
                return false;
        }
        return true;
    }

    public static <T> String toStringList(List<T> list, Function<T, String> show) {
        long size = list.size();
        if (size == 0) return "[]";
        StringBuilder builder = new StringBuilder("[");
        builder.append(show.apply(list.nth(0)));

        for (long i = 1; i < size; i++) {
            builder.append(", ");
            builder.append(show.apply(list.nth(i)));
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
        for (; ; ) {
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

    public static boolean xor(boolean cond1, boolean cond2) {
        return cond1 ^ cond2;
    }

    public static int bitAndInt(int b1, int b2) {
        return b1 & b2;
    }

    public static long bitAndLong(long b1, long b2) {
        return b1 & b2;
    }

    public static int bitOrInt(int b1, int b2) {
        return b1 | b2;
    }

    public static long bitOrLong(long b1, long b2) {
        return b1 | b2;
    }

    public static int bitXorInt(int b1, int b2) {
        return b1 ^ b2;
    }

    public static long bitXorLong(long b1, long b2) {
        return b1 ^ b2;
    }

    public static int bitShiftLeftInt(int b1, int amount) {
        return b1 << amount;
    }

    public static long bitShiftLeftLong(long b1, long amount) {
        return b1 << amount;
    }

    public static int bitShiftRightInt(int b1, int amount) {
        return b1 >> amount;
    }

    public static long bitShiftRightLong(long b1, long amount) {
        return b1 >> amount;
    }

    public static int unsignedBitShiftRightInt(int b1, int amount) {
        return b1 >>> amount;
    }

    public static long unsignedBitShiftRightLong(long b1, long amount) {
        return b1 >>> amount;
    }

    public static int bitNotInt(int b) {
        return ~b;
    }

    public static long bitNotLong(long b) {
        return ~b;
    }

    public static int charToInt(char c) {
        return c;
    }

    public static char intToChar(int c) {
        return (char) c;
    }

    public static int intRemainder(int i, int i2) {
        return i % i2;
    }

    public static long longRemainder(long i, long i2) {
        return i % i2;
    }

    public static float floatRemainder(float i, float i2) {
        return i % i2;
    }

    public static double doubleRemainder(double i, double i2) {
        return i % i2;
    }

    public static <T> void eachRangeInt(int begin, int end, int step, Function<Integer, T> f) {
        if (begin <= end) {
            for (var i = begin; i < end; i += step) {
                f.apply(i);
            }
        } else {
            for (var i = begin; i > end; i -= step) {
                f.apply(i);
            }
        }
    }

    public static <T> void eachRangeLong(long begin, long end, long step, Function<Long, T> f) {
        if (begin <= end) {
            for (var i = begin; i < end; i += step) {
                f.apply(i);
            }
        } else {
            for (var i = begin; i > end; i -= step) {
                f.apply(i);
            }
        }
    }

    public static <T> void eachRangeFloat(float begin, float end, float step, Function<Float, T> f) {
        if (begin <= end) {
            for (var i = begin; i < end; i += step) {
                f.apply(i);
            }
        } else {
            for (var i = begin; i > end; i -= step) {
                f.apply(i);
            }
        }
    }

    public static <T> void eachRangeDouble(double begin, double end, double step, Function<Double, T> f) {
        if (begin <= end) {
            for (var i = begin; i < end; i += step) {
                f.apply(i);
            }
        } else {
            for (var i = begin; i > end; i -= step) {
                f.apply(i);
            }
        }
    }

    public static <T> void eachRangeChar(char begin, char end, char step, Function<Character, T> f) {
        if (begin <= end) {
            for (var i = begin; i < end; i += step) {
                f.apply(i);
            }
        } else {
            for (var i = begin; i > end; i -= step) {
                f.apply(i);
            }
        }
    }

    public static void eachRangeBreak(int begin, int end, Function<Integer, Integer> f) {
        if (begin <= end) {
            for (int i = begin; i < end; i++) {
                var res = f.apply(i);
                if (res < 0) break;
            }
        } else {
            for (int i = begin; i > end; i--) {
                var res = f.apply(i);
                if (res < 0) break;
            }
        }
    }

    public static List<Integer> listIntRange(int begin, int end) {
        var list = new List<Integer>().linear();
        if (begin <= end) {
            for (int i = begin; i < end; i++) {
                list.addLast(i);
            }
        } else {
            for (int i = begin; i > end; i--) {
                list.addLast(i);
            }
        }
        return list.forked();
    }

    public static List<Long> listLongRange(long begin, long end) {
        var list = new List<Long>().linear();
        if (begin <= end) {
            for (long i = begin; i < end; i++) {
                list.addLast(i);
            }
        } else {
            for (long i = begin; i > end; i--) {
                list.addLast(i);
            }
        }
        return list.forked();
    }

    public static List<Float> listFloatRange(float begin, float end) {
        var list = new List<Float>().linear();
        if (begin <= end) {
            for (float i = begin; i < end; i++) {
                list.addLast(i);
            }
        } else {
            for (float i = begin; i > end; i--) {
                list.addLast(i);
            }
        }
        return list.forked();
    }

    public static List<Double> listDoubleRange(double begin, double end) {
        var list = new List<Double>().linear();
        if (begin <= end) {
            for (double i = begin; i < end; i++) {
                list.addLast(i);
            }
        } else {
            for (double i = begin; i > end; i--) {
                list.addLast(i);
            }
        }
        return list.forked();
    }

    public static List<Character> listCharRange(char begin, char end) {
        var list = new List<Character>().linear();
        if (begin <= end) {
            for (char i = begin; i < end; i++) {
                list.addLast(i);
            }
        } else {
            for (char i = begin; i > end; i--) {
                list.addLast(i);
            }
        }
        return list.forked();
    }

    public static <T> Comparator<T> makeComparator(Function<T, Function<T, Integer>> compare) {
        return (o1, o2) -> compare.apply(o1).apply(o2);
    }

    public static <T, R> Consumer<T> makeConsumer(Function<T, R> f) {
        return f::apply;
    }

    public static <T, R> Runnable makeRunnable(Function<T, R> f) {
        return () -> f.apply(null);
    }

    public static <T, R> Callable<R> makeCallable(Function<T, R> f) {
        return () -> f.apply(null);
    }

    public static <T, R> Supplier<R> makeSupplier(Function<T, R> f) {
        return () -> f.apply(null);
    }

    public static <T> Predicate<T> makePredicate(Function<T, Boolean> f) {
        return f::apply;
    }

    public static <T, R> BiPredicate<T, R> makeBiPredicate(Function<T, Function<R, Boolean>> f) {
        return (x, y) -> f.apply(x).apply(y);
    }

    public static <T> ToLongFunction<T> makeToLongFunction(Function<T, Long> f) {
        return f::apply;
    }

    public static <T> ToIntFunction<T> makeToIntFunction(Function<T, Integer> f) {
        return f::apply;
    }

    public static <R> IntFunction<R> makeIntFunction(Function<Integer, R> f) {
        return f::apply;
    }

    public static <T> BinaryOperator<T> makeBinaryOperator(Function<T, Function<T, T>> f) {
        return (x, y) -> f.apply(x).apply(y);
    }

    public static <T> UnaryOperator<T> makeUnaryOperator(Function<T, T> f) {
        return f::apply;
    }

    public static <T, U, R> BiFunction<T, U, R> makeBiFunction(Function<T, Function<U, R>> fun) {
        return (x, y) -> fun.apply(x).apply(y);
    }

    public static <T> Optional<T> findList(Function<T, Boolean> pred, List<T> list) {
        T found = null;
        for (T elem : list) {
            if (pred.apply(elem)) {
                found = elem;
                break;
            }
        }
        return Optional.ofNullable(found);
    }

    public static <T, R> R foldList(Function<R, Function<T, R>> f, R init, List<T> list) {
        R acc = init;
        for (long i = 0; i < list.size(); i++) {
            acc = f.apply(acc).apply(list.nth(i));
        }
        return acc;
    }

    public static <T, R> R foldSet(Function<R, Function<T, R>> f, R init, Set<T> set) {
        R acc = init;
        for (long i = 0; i < set.size(); i++) {
            acc = f.apply(acc).apply(set.nth(i));
        }
        return acc;
    }

    public static <T> boolean listEvery(Function<T, Boolean> pred, List<T> list) {
        for (T elem : list) {
            if (!pred.apply(elem)) return false;
        }
        return true;
    }

    public static <T> boolean setEvery(Function<T, Boolean> pred, Set<T> set) {
        for (T elem : set) {
            if (!pred.apply(elem)) return false;
        }
        return true;
    }

    public static <K, V> boolean mapEvery(Function<K, Function<V, Boolean>> pred, Map<K, V> map) {
        for (IEntry<K, V> kv : map) {
            if (!pred.apply(kv.key()).apply(kv.value())) return false;
        }
        return true;
    }

    public static Set<Class> loadAllClasses(String packageName) {
        var classes = findAllClasses(packageName)
                .filter(clazz -> clazz.endsWith("Module"))
                .map(Core::getClass)
                .collect(Collectors.toSet());
        return Set.from(classes);
    }

    public static Stream<String> findAllClasses(String packageName) {
        InputStream stream = ClassLoader.getSystemClassLoader()
                .getResourceAsStream(packageName.replaceAll("[.]", "/"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        var packs = new ArrayList<String>();
        var lines = reader.lines().collect(Collectors.toList());
        lines.forEach(line -> {
            if (!line.endsWith(".class")) packs.add(packageName + "/" + line);
        });
        var classes = lines.stream()
                .filter(line -> line.endsWith(".class"))
                .map(clazz ->
                        packageName.replaceAll("/", "\\.") + "."
                                + clazz.substring(0, clazz.lastIndexOf('.'))
                );
        if (!packs.isEmpty()) {
            var res = packs.stream().flatMap(Core::findAllClasses);
            return Stream.concat(classes, res);
        }
        return classes;
    }

    private static Class getClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            // handle the exception
        }
        return null;
    }
}