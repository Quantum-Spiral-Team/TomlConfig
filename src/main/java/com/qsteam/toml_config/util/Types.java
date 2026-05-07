package com.qsteam.toml_config.util;

import com.qsteam.toml_config.TOMLConfigMod;
import com.qsteam.toml_config.core.ConfigManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast conversion helpers between TOML/NightConfig values and Java field types.
 * <p>
 * Designed to be used by {@link ConfigManager} during reflective
 * config loading. This utility focuses on performance and predictable behavior:
 * numeric casts, UUID parsing, enum mapping (case-insensitive), and array/collection shaping.
 */
public final class Types {

    private static final Logger LOGGER = TOMLConfigMod.getLogger(Types.class.getSimpleName());
    private static final Map<Class<?>, Map<String, Enum<?>>> ENUM_LOOKUP_CACHE = new ConcurrentHashMap<>();

    public static final class ConversionResult {
        private final boolean success;
        private final Object value;

        private ConversionResult(boolean success, Object value) {
            this.success = success;
            this.value = value;
        }

        public boolean isSuccess() {
            return success;
        }

        public Object getValue() {
            return value;
        }
    }

    public static Object convertType(Object value, Class<?> targetType) {
        ConversionResult result = tryConvertType(value, targetType);
        return result.success ? result.value : value;
    }

    /**
     * Same as {@link #tryConvertType(Object, Class, Type)} with no declared generic type (raw collections keep legacy copy behavior).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ConversionResult tryConvertType(Object value, Class<?> targetType) {
        return tryConvertType(value, targetType, null);
    }

    /**
     * Attempts to convert a value to the requested Java type.
     * <p>
     * For {@link Collection} targets, {@code declaredType} should be the field's {@link java.lang.reflect.Field#getGenericType()}
     * so list/set elements are converted (e.g. TOML {@code Long} to {@code Integer} for {@code List<Integer>}).
     * <p>
     * On failure, returns {@code success=false} and {@code value=null}. Callers may decide whether
     * to fall back to a default value, rewrite TOML, or ignore the mismatch.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ConversionResult tryConvertType(Object value, Class<?> targetType, Type declaredType) {
        if (value == null) return new ConversionResult(true, null);

        if (targetType == Object.class) {
            return new ConversionResult(true, value);
        }

        Class<?> valueClass = value.getClass();

        // Before isInstance: List/Collection isInstance(ArrayList) is true even when elements need narrowing (TOML Long -> Integer).
        if (Collection.class.isAssignableFrom(targetType) && !Map.class.isAssignableFrom(targetType)
                && (value instanceof Iterable || valueClass.isArray())) {
            Class<?> elementType = resolveCollectionElementType(declaredType);
            if (elementType != null) {
                return convertCollectionElements(value, valueClass, targetType, elementType);
            }
            if (targetType.isInstance(value)) {
                return new ConversionResult(true, value);
            }
        }

        if (targetType.isInstance(value)) {
            return new ConversionResult(true, value);
        }

        if (targetType == String.class) {
            return new ConversionResult(true, value.toString());
        }

        if (value instanceof Number) {
            Number num = (Number) value;
            if (targetType == int.class || targetType == Integer.class) return new ConversionResult(true, num.intValue());
            if (targetType == long.class || targetType == Long.class) return new ConversionResult(true, num.longValue());
            if (targetType == float.class || targetType == Float.class) return new ConversionResult(true, num.floatValue());
            if (targetType == double.class || targetType == Double.class) return new ConversionResult(true, num.doubleValue());
            if (targetType == byte.class || targetType == Byte.class) return new ConversionResult(true, num.byteValue());
            if (targetType == short.class || targetType == Short.class) return new ConversionResult(true, num.shortValue());
        }

        if (value instanceof String && (targetType == boolean.class || targetType == Boolean.class)) {
            return new ConversionResult(true, Boolean.parseBoolean((String) value));
        }
        if (value instanceof Boolean && (targetType == boolean.class || targetType == Boolean.class)) {
            return new ConversionResult(true, value);
        }
        if (value instanceof Character && (targetType == char.class || targetType == Character.class)) {
            return new ConversionResult(true, value);
        }

        if (value instanceof String && targetType == UUID.class) {
            try {
                return new ConversionResult(true, UUID.fromString((String) value));
            } catch (IllegalArgumentException e) {
                return new ConversionResult(false, null);
            }
        }

        if (targetType.isEnum() && value instanceof String) {
            Enum<?> enumValue = resolveEnumIgnoreCase((Class<? extends Enum>) targetType, (String) value);
            if (enumValue != null) {
                return new ConversionResult(true, enumValue);
            }
            LOGGER.error("Invalid enum value: {} for type {}", value, targetType.getName());
            return new ConversionResult(false, null);
        }

        if (targetType.isArray()) {
            Class<?> componentType = targetType.getComponentType();
            Object array;

            if (value instanceof Collection) {
                Collection<?> col = (Collection<?>) value;
                int size = col.size();
                array = Array.newInstance(componentType, size);
                if (componentType.isPrimitive()) {
                    int i = 0;
                    for (Object item : col) {
                        if (!setPrimitiveArrayElement(array, i++, componentType, item)) {
                            return new ConversionResult(false, null);
                        }
                    }
                } else {
                    int i = 0;
                    for (Object item : col) {
                        ConversionResult itemResult = tryConvertType(item, componentType);
                        if (!itemResult.isSuccess()) {
                            return new ConversionResult(false, null);
                        }
                        Array.set(array, i++, itemResult.getValue());
                    }
                }
                return new ConversionResult(true, array);
            } else if (valueClass.isArray()) {
                int length = Array.getLength(value);
                array = Array.newInstance(componentType, length);
                if (componentType.isPrimitive()) {
                    for (int i = 0; i < length; i++) {
                        if (!setPrimitiveArrayElement(array, i, componentType, Array.get(value, i))) {
                            return new ConversionResult(false, null);
                        }
                    }
                } else {
                    for (int i = 0; i < length; i++) {
                        ConversionResult itemResult = tryConvertType(Array.get(value, i), componentType);
                        if (!itemResult.isSuccess()) {
                            return new ConversionResult(false, null);
                        }
                        Array.set(array, i, itemResult.getValue());
                    }
                }
                return new ConversionResult(true, array);
            }
        }

        if (Collection.class.isAssignableFrom(targetType) && !Map.class.isAssignableFrom(targetType)
                && (value instanceof Iterable || valueClass.isArray())) {
            return copyCollectionShape(value, valueClass, targetType);
        }

        return new ConversionResult(false, null);
    }

    private static ConversionResult convertCollectionElements(
            Object value, Class<?> valueClass, Class<?> targetType, Class<?> elementType) {
        List<Object> resultList;
        if (value instanceof Iterable) {
            if (value instanceof Collection) {
                resultList = new ArrayList<>(((Collection<?>) value).size());
            } else {
                resultList = new ArrayList<>();
            }
            for (Object item : (Iterable<?>) value) {
                ConversionResult itemResult = tryConvertType(item, elementType);
                if (!itemResult.isSuccess()) {
                    return new ConversionResult(false, null);
                }
                resultList.add(itemResult.getValue());
            }
        } else {
            int length = Array.getLength(value);
            resultList = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                ConversionResult itemResult = tryConvertType(Array.get(value, i), elementType);
                if (!itemResult.isSuccess()) {
                    return new ConversionResult(false, null);
                }
                resultList.add(itemResult.getValue());
            }
        }
        if (Set.class.isAssignableFrom(targetType)) {
            return new ConversionResult(true, new HashSet<>(resultList));
        }
        return new ConversionResult(true, resultList);
    }

    private static ConversionResult copyCollectionShape(Object value, Class<?> valueClass, Class<?> targetType) {
        List<Object> resultList;
        if (value instanceof Iterable) {
            if (value instanceof Collection) {
                resultList = new ArrayList<>(((Collection<?>) value).size());
            } else {
                resultList = new ArrayList<>();
            }
            for (Object item : (Iterable<?>) value) {
                resultList.add(item);
            }
        } else {
            int length = Array.getLength(value);
            resultList = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                resultList.add(Array.get(value, i));
            }
        }
        if (Set.class.isAssignableFrom(targetType)) {
            return new ConversionResult(true, new HashSet<>(resultList));
        }
        return new ConversionResult(true, resultList);
    }

    private static Class<?> resolveCollectionElementType(Type declaredType) {
        if (declaredType == null) {
            return null;
        }
        if (!(declaredType instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType pt = (ParameterizedType) declaredType;
        if (!(pt.getRawType() instanceof Class)) {
            return null;
        }
        Class<?> raw = (Class<?>) pt.getRawType();
        if (!Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
            return null;
        }
        Type[] args = pt.getActualTypeArguments();
        if (args.length == 0) {
            return null;
        }
        return classFromType(args[0]);
    }

    private static Class<?> classFromType(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof WildcardType) {
            WildcardType wt = (WildcardType) type;
            Type[] upper = wt.getUpperBounds();
            if (upper.length > 0) {
                return classFromType(upper[0]);
            }
            return Object.class;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            if (raw instanceof Class) {
                return (Class<?>) raw;
            }
        }
        if (type instanceof TypeVariable) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            if (bounds.length > 0) {
                return classFromType(bounds[0]);
            }
        }
        return Object.class;
    }

    private static boolean setPrimitiveArrayElement(Object array, int index, Class<?> componentType, Object rawValue) {
        try {
        if (componentType == int.class) {
            Array.setInt(array, index, toInt(rawValue));
            return true;
        }
        if (componentType == long.class) {
            Array.setLong(array, index, toLong(rawValue));
            return true;
        }
        if (componentType == float.class) {
            Array.setFloat(array, index, toFloat(rawValue));
            return true;
        }
        if (componentType == double.class) {
            Array.setDouble(array, index, toDouble(rawValue));
            return true;
        }
        if (componentType == byte.class) {
            Array.setByte(array, index, toByte(rawValue));
            return true;
        }
        if (componentType == short.class) {
            Array.setShort(array, index, toShort(rawValue));
            return true;
        }
        if (componentType == char.class) {
            Array.setChar(array, index, toChar(rawValue));
            return true;
        }
        if (componentType == boolean.class) {
            Array.setBoolean(array, index, toBoolean(rawValue));
            return true;
        }

        Array.set(array, index, rawValue);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) return Integer.parseInt((String) value);
        return (Integer) value;
    }

    private static long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        return (Long) value;
    }

    private static float toFloat(Object value) {
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value instanceof String) return Float.parseFloat((String) value);
        return (Float) value;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) return Double.parseDouble((String) value);
        return (Double) value;
    }

    private static byte toByte(Object value) {
        if (value instanceof Number) return ((Number) value).byteValue();
        if (value instanceof String) return Byte.parseByte((String) value);
        return (Byte) value;
    }

    private static short toShort(Object value) {
        if (value instanceof Number) return ((Number) value).shortValue();
        if (value instanceof String) return Short.parseShort((String) value);
        return (Short) value;
    }

    private static char toChar(Object value) {
        if (value instanceof Character) return (Character) value;
        if (value instanceof Number) return (char) ((Number) value).intValue();
        if (value instanceof String) {
            String str = (String) value;
            return str.isEmpty() ? '\0' : str.charAt(0);
        }
        return (Character) value;
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return (Boolean) value;
    }

    private static Enum<?> resolveEnumIgnoreCase(Class<? extends Enum> enumClass, String rawValue) {
        Map<String, Enum<?>> enumLookup = ENUM_LOOKUP_CACHE.computeIfAbsent(enumClass, cls -> {
            Map<String, Enum<?>> lookup = new HashMap<>();
            for (Enum<?> constant : (Enum<?>[]) cls.getEnumConstants()) {
                lookup.put(constant.name(), constant);
                lookup.put(constant.name().toLowerCase(Locale.ROOT), constant);
            }
            return lookup;
        });

        Enum<?> exact = enumLookup.get(rawValue);
        if (exact != null) {
            return exact;
        }

        return enumLookup.get(rawValue.toLowerCase(Locale.ROOT));
    }
}
