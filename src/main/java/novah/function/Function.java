/**
 * Copyright 2022 Islon Scherer
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
package novah.function;

/**
 * Represents a function in Novah.
 */
@FunctionalInterface
@SuppressWarnings("unchecked")
public interface Function<T, R> extends java.util.function.Function<T, R> {

    default R applyI(int arg) {
        return apply((T) Integer.valueOf(arg));
    }

    default R applyB(byte arg) {
        return apply((T) Byte.valueOf(arg));
    }

    default R applyS(short arg) {
        return apply((T) Short.valueOf(arg));
    }

    default R applyJ(long arg) {
        return apply((T) Long.valueOf(arg));
    }

    default R applyF(float arg) {
        return apply((T) Float.valueOf(arg));
    }

    default R applyD(double arg) {
        return apply((T) Double.valueOf(arg));
    }

    default R applyC(char arg) {
        return apply((T) Character.valueOf(arg));
    }

    default R applyZ(boolean arg) {
        return apply((T) Boolean.valueOf(arg));
    }

    default int applyInt(T arg) {
        return (Integer) apply(arg);
    }

    default int applyInt(int arg) {
        return (Integer) apply((T) Integer.valueOf(arg));
    }

    default int applyInt(byte arg) {
        return (Integer) apply((T) Byte.valueOf(arg));
    }

    default int applyInt(short arg) {
        return (Integer) apply((T) Short.valueOf(arg));
    }

    default int applyInt(long arg) {
        return (Integer) apply((T) Long.valueOf(arg));
    }

    default int applyInt(float arg) {
        return (Integer) apply((T) Float.valueOf(arg));
    }

    default int applyInt(double arg) {
        return (Integer) apply((T) Double.valueOf(arg));
    }

    default int applyInt(char arg) {
        return (Integer) apply((T) Character.valueOf(arg));
    }

    default int applyInt(boolean arg) {
        return (Integer) apply((T) Boolean.valueOf(arg));
    }

    default byte applyByte(T arg) {
        return (Byte) apply(arg);
    }

    default byte applyByte(int arg) {
        return (Byte) apply((T) Integer.valueOf(arg));
    }

    default byte applyByte(byte arg) {
        return (Byte) apply((T) Byte.valueOf(arg));
    }

    default byte applyByte(short arg) {
        return (Byte) apply((T) Short.valueOf(arg));
    }

    default byte applyByte(long arg) {
        return (Byte) apply((T) Long.valueOf(arg));
    }

    default byte applyByte(float arg) {
        return (Byte) apply((T) Float.valueOf(arg));
    }

    default byte applyByte(double arg) {
        return (Byte) apply((T) Double.valueOf(arg));
    }

    default byte applyByte(char arg) {
        return (Byte) apply((T) Character.valueOf(arg));
    }

    default byte applyByte(boolean arg) {
        return (Byte) apply((T) Boolean.valueOf(arg));
    }

    default short applyShort(T arg) {
        return (Short) apply(arg);
    }

    default short applyShort(int arg) {
        return (Short) apply((T) Integer.valueOf(arg));
    }

    default short applyShort(byte arg) {
        return (Short) apply((T) Byte.valueOf(arg));
    }

    default short applyShort(short arg) {
        return (Short) apply((T) Short.valueOf(arg));
    }

    default short applyShort(long arg) {
        return (Short) apply((T) Long.valueOf(arg));
    }

    default short applyShort(float arg) {
        return (Short) apply((T) Float.valueOf(arg));
    }

    default short applyShort(double arg) {
        return (Short) apply((T) Double.valueOf(arg));
    }

    default short applyShort(char arg) {
        return (Short) apply((T) Character.valueOf(arg));
    }

    default short applyShort(boolean arg) {
        return (Short) apply((T) Boolean.valueOf(arg));
    }

    default long applyLong(T arg) {
        return (Long) apply(arg);
    }

    default long applyLong(int arg) {
        return (Long) apply((T) Integer.valueOf(arg));
    }

    default long applyLong(byte arg) {
        return (Long) apply((T) Byte.valueOf(arg));
    }

    default long applyLong(short arg) {
        return (Long) apply((T) Short.valueOf(arg));
    }

    default long applyLong(long arg) {
        return (Long) apply((T) Long.valueOf(arg));
    }

    default long applyLong(float arg) {
        return (Long) apply((T) Float.valueOf(arg));
    }

    default long applyLong(double arg) {
        return (Long) apply((T) Double.valueOf(arg));
    }

    default long applyLong(char arg) {
        return (Long) apply((T) Character.valueOf(arg));
    }

    default long applyLong(boolean arg) {
        return (Long) apply((T) Boolean.valueOf(arg));
    }

    default float applyFloat(T arg) {
        return (Float) apply(arg);
    }

    default float applyFloat(int arg) {
        return (Float) apply((T) Integer.valueOf(arg));
    }

    default float applyFloat(byte arg) {
        return (Float) apply((T) Byte.valueOf(arg));
    }

    default float applyFloat(short arg) {
        return (Float) apply((T) Short.valueOf(arg));
    }

    default float applyFloat(long arg) {
        return (Float) apply((T) Long.valueOf(arg));
    }

    default float applyFloat(float arg) {
        return (Float) apply((T) Float.valueOf(arg));
    }

    default float applyFloat(double arg) {
        return (Float) apply((T) Double.valueOf(arg));
    }

    default float applyFloat(char arg) {
        return (Float) apply((T) Character.valueOf(arg));
    }

    default float applyFloat(boolean arg) {
        return (Float) apply((T) Boolean.valueOf(arg));
    }

    default double applyDouble(T arg) {
        return (Double) apply(arg);
    }

    default double applyDouble(int arg) {
        return (Double) apply((T) Integer.valueOf(arg));
    }

    default double applyDouble(byte arg) {
        return (Double) apply((T) Byte.valueOf(arg));
    }

    default double applyDouble(short arg) {
        return (Double) apply((T) Short.valueOf(arg));
    }

    default double applyDouble(long arg) {
        return (Double) apply((T) Long.valueOf(arg));
    }

    default double applyDouble(float arg) {
        return (Double) apply((T) Float.valueOf(arg));
    }

    default double applyDouble(double arg) {
        return (Double) apply((T) Double.valueOf(arg));
    }

    default double applyDouble(char arg) {
        return (Double) apply((T) Character.valueOf(arg));
    }

    default double applyDouble(boolean arg) {
        return (Double) apply((T) Boolean.valueOf(arg));
    }

    default char applyChar(T arg) {
        return (Character) apply(arg);
    }

    default char applyChar(int arg) {
        return (Character) apply((T) Integer.valueOf(arg));
    }

    default char applyChar(byte arg) {
        return (Character) apply((T) Byte.valueOf(arg));
    }

    default char applyChar(short arg) {
        return (Character) apply((T) Short.valueOf(arg));
    }

    default char applyChar(long arg) {
        return (Character) apply((T) Long.valueOf(arg));
    }

    default char applyChar(float arg) {
        return (Character) apply((T) Float.valueOf(arg));
    }

    default char applyChar(double arg) {
        return (Character) apply((T) Double.valueOf(arg));
    }

    default char applyChar(char arg) {
        return (Character) apply((T) Character.valueOf(arg));
    }

    default char applyChar(boolean arg) {
        return (Character) apply((T) Boolean.valueOf(arg));
    }

    default boolean applyBoolean(T arg) {
        return (Boolean) apply(arg);
    }

    default boolean applyBoolean(int arg) {
        return (Boolean) apply((T) Integer.valueOf(arg));
    }

    default boolean applyBoolean(byte arg) {
        return (Boolean) apply((T) Byte.valueOf(arg));
    }

    default boolean applyBoolean(short arg) {
        return (Boolean) apply((T) Short.valueOf(arg));
    }

    default boolean applyBoolean(long arg) {
        return (Boolean) apply((T) Long.valueOf(arg));
    }

    default boolean applyBoolean(float arg) {
        return (Boolean) apply((T) Float.valueOf(arg));
    }

    default boolean applyBoolean(double arg) {
        return (Boolean) apply((T) Double.valueOf(arg));
    }

    default boolean applyBoolean(char arg) {
        return (Boolean) apply((T) Character.valueOf(arg));
    }

    default boolean applyBoolean(boolean arg) {
        return (Boolean) apply((T) Boolean.valueOf(arg));
    }
}
