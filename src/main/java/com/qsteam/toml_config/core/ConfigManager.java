package com.qsteam.toml_config.core;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.qsteam.toml_config.TOMLConfigMod;
import com.qsteam.toml_config.Tags;
import com.qsteam.toml_config.api.ConfigCategory;
import com.qsteam.toml_config.api.ConfigValue;
import com.qsteam.toml_config.api.TOMLConfig;
import com.qsteam.toml_config.util.Types;
import com.qsteam.toml_config.util.range.RangeValidator;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The central manager for processing TOML configurations.
 * <p>
 * Responsibilities include:
 * <ul>
 * <li>Loading and saving files using the NightConfig library.</li>
 * <li>Reflective scanning of {@link TOMLConfig} classes.</li>
 * <li>Type conversion between Java objects and TOML formats.</li>
 * <li>Injecting mod metadata via the {@code [mod_info]} block.</li>
 * <li>Range validation for loaded values via {@link ConfigValue#range()}.</li>
 * <li>Keeping comments in sync (including {@code @Allowed: ...} metadata).</li>
 * <li>Runtime reload of all initialized config classes from in-game command handlers.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public class ConfigManager {

    private static final Set<Class<?>> INITIALIZED_CLASSES = ConcurrentHashMap.newKeySet();
    private static final Map<Path, CommentedFileConfig> CONFIG_CACHE = new ConcurrentHashMap<>();

    private static final String HEADER_NAME = "mod_info";
    /// Map<path, initialized>
    private static final Map<Path, Boolean> HEADERS_INITIALIZED = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<FieldMeta>> FIELD_META_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean FML_READY_CACHED = false;
    private static final Map<String, String> SNAKE_CASE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> BOOLEAN_RANGE_IGNORED_WARNED = ConcurrentHashMap.newKeySet();
    private static final String SNAKE_CASE_CACHE_FILE_NAME = "snake_case_cache.properties";
    private static volatile Path snakeCaseCacheFile;
    private static volatile boolean snakeCaseDiskCacheEnabled = true;
    private static volatile boolean snakeCaseCacheDirty = false;

    private static final Logger LOGGER = TOMLConfigMod.getLogger(ConfigManager.class.getSimpleName());
    private static final int ENUM_COMMENT_VALUES_LIMIT = 32;

    /**
     * Entry point for initializing a configuration class.
     * This method is called automatically from the {@code <clinit>} block
     * of target classes via {@link TOMLClassTransformer} injection and is idempotent
     * per class.
     *
     * @param configClass The {@link TOMLConfig} annotated class to initialize.
     */
    public static void init(Class<?> configClass) {
        syncConfigClass(configClass, true);
    }

    /**
     * Reloads all already discovered/initialized {@link TOMLConfig} classes from disk.
     * <p>
     * This reuses the same conversion/range validation pipeline as first-time init.
     * Value fields and comments are both synchronized.
     *
     * @return number of config classes processed.
     */
    public static int reloadAllConfigs() {
        List<Class<?>> classesToReload = new ArrayList<>(INITIALIZED_CLASSES);
        int reloaded = 0;
        for (Class<?> configClass : classesToReload) {
            if (syncConfigClass(configClass, false)) {
                reloaded++;
            }
        }
        applyPendingHeaders();
        trimCaches();
        return reloaded;
    }

    /**
     * Synchronizes one config class with its TOML file.
     *
     * @param configClass class annotated with {@link TOMLConfig}.
     * @param firstInit   whether this call originates from first-time initialization.
     * @return {@code true} when the class was processed successfully.
     */
    private static boolean syncConfigClass(Class<?> configClass, boolean firstInit) {
        try {
            if (firstInit && !INITIALIZED_CLASSES.add(configClass)) {
                return false;
            }

            TOMLConfig configMeta = configClass.getAnnotation(TOMLConfig.class);
            if (configMeta == null) return false;

            String fileName = configMeta.name().endsWith(".toml") ? configMeta.name() : configMeta.name() + ".toml";
            Path configPath = Launch.minecraftHome.toPath()
                    .resolve(configMeta.rootDir())
                    .resolve(fileName);

            try {
                Files.createDirectories(configPath.getParent());
            } catch (IOException e) {
                LOGGER.error("Couldn't create a directory for the config: ", e);
                return false;
            }

            CommentedFileConfig config = getOrCreateConfig(configPath);

            synchronized (config) {
                boolean changed = ensureHeaderBase(configMeta.modId(), config);
                changed |= processCategory(config, configClass, null, "");
                if (Boolean.FALSE.equals(HEADERS_INITIALIZED.getOrDefault(configPath, false))) {
                    changed |= tryApplyHeader(configMeta.modId(), config);
                }

                if (changed) {
                    config.save();
                }
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Fatal error in config init for: {}", configClass.getName(), e);
            return false;
        }
    }

    /**
     * Returns a cached NightConfig instance for the path, creating/loading it on first access.
     */
    private static CommentedFileConfig getOrCreateConfig(Path path) {
        return CONFIG_CACHE.computeIfAbsent(path, p -> {
            CommentedFileConfig config = CommentedFileConfig.builder(p)
                    .preserveInsertionOrder()
                    .sync()
                    .build();
            try {
                config.load();
            } catch (Exception e) {
                LOGGER.error("Failed to load config file: {}", p, e);
            }
            return config;
        });
    }

    /**
     * Recursively processes class fields to sync {@link ConfigValue} and
     * {@link ConfigCategory} annotations with the TOML file.
     *
     * @param config   The NightConfig file object.
     * @param clazz    The class to scan for fields.
     * @param instance The instance of the class (null for static root classes).
     * @param path     The current TOML path hierarchy (e.g., "features.machine").
     * @return {@code true} if the file was modified and requires saving.
     * <p>
     * Both value data and comments are synchronized. For existing keys this method may update:
     * <ul>
     *     <li>the runtime field value from TOML</li>
     *     <li>the TOML value back to default when conversion/range validation fails</li>
     *     <li>the field comment and allowed-range comment when annotation metadata changed</li>
     * </ul>
     */
    private static boolean processCategory(CommentedFileConfig config, Class<?> clazz, Object instance, String path) throws IllegalAccessException {
        boolean needsSave = false;

        try {
            for (FieldMeta meta : getFieldMetadata(clazz)) {
                Field field = meta.field;

                if (meta.categoryMeta != null) {
                    if (!meta.isStatic) {
                        LOGGER.fatal("Config field {} must be static!", field.getName());
                        continue;
                    }

                    Class<?> categoryClass = field.getType();
                    Object categoryInstance = field.get(null);

                    if (categoryInstance == null) {
                        try {
                            categoryInstance = categoryClass.getDeclaredConstructor().newInstance();
                            field.set(null, categoryInstance);
                        } catch (ReflectiveOperationException e) {
                            LOGGER.error("Failed to instantiate category class: {}", categoryClass.getName(), e);
                            continue;
                        }
                    }

                    String categoryPath = meta.categoryPath;
                    String fullCategoryPath = path.isEmpty() ? categoryPath : path + "." + categoryPath;

                    if (categoryPath.equals(HEADER_NAME)) {
                        LOGGER.error("Reserved identifier detected: '{}'. This name is used by the system core and cannot be assigned to a custom category.", HEADER_NAME);
                        continue;
                    }
                    needsSave |= setCommentIfDifferent(config, fullCategoryPath, meta.categoryDescription);

                    needsSave |= processCategory(config, categoryClass, categoryInstance, fullCategoryPath);

                } else if (meta.valueMeta != null) {
                    if (instance == null && !meta.isStatic) {
                        LOGGER.error("Config value field {}.{} must be static in root config class.", clazz.getName(), field.getName());
                        continue;
                    }

                    String configKey = meta.valueKey;
                    String fullPath = path.isEmpty() ? configKey : path + "." + configKey;
                    Object defaultValue = normalizeForToml(field.get(instance));

                    RangeInfo rangeInfo = rangeInfo(meta.valueRange, field.getType(), defaultValue, fullPath);
                    String comment = meta.valueComment;
                    if (!rangeInfo.display.isEmpty()) {
                        comment += getRangeComment(rangeInfo.display, defaultValue, comment.isEmpty());
                    }

                    if (config.contains(fullPath)) {
                        Object loadedValue = config.get(fullPath);
                        Object normalizedLoadedValue = normalizeLoadedValue(loadedValue, field.getType());
                        Types.ConversionResult conversion = Types.tryConvertType(normalizedLoadedValue, field.getType(), field.getGenericType());
                        Object resolvedValue = defaultValue;
                        boolean hasResolved = defaultValue != null || !field.getType().isPrimitive();
                        if (conversion.isSuccess()) {
                            Object converted = conversion.getValue();
                            if (RangeValidator.validate(rangeInfo.raw, converted, field.getType())) {
                                resolvedValue = converted;
                                hasResolved = true;
                            } else {
                                LOGGER.warn("Value for '{}' in {} is outside allowed range '{}'. Falling back to default.",
                                        fullPath, clazz.getSimpleName(), rangeInfo.display);
                            }
                        } else {
                            LOGGER.warn("Failed to convert config key '{}' to {}. Falling back to default.",
                                    fullPath, field.getType().getSimpleName());
                        }

                        if (hasResolved) {
                            field.set(instance, resolvedValue);
                        }

                        Object tomlValue = normalizeForToml(resolvedValue);
                        if (!Objects.equals(normalizedLoadedValue, tomlValue)) {
                            config.set(fullPath, tomlValue);
                            needsSave = true;
                        }
                        needsSave |= setCommentIfDifferent(config, fullPath, comment);
                    } else {
                        config.set(fullPath, defaultValue);

                        needsSave |= setCommentIfDifferent(config, fullPath, comment);

                        needsSave = true;
                    }
                }
            }
        } catch (IllegalAccessException e) {
            LOGGER.error("Failed to access config fields in class {}", clazz.getName(), e);
        }

        return needsSave;
    }

    /**
     * Scans the provided class for fields annotated with {@link ConfigCategory} or
     * {@link ConfigValue} and extracts their metadata.
     * <p>
     * This method performs the following operations:
     * <ul>
     * <li><b>Caching:</b> Uses {@link #FIELD_META_CACHE} to avoid redundant reflective
     * scans of the same class.</li>
     * <li><b>Filtering:</b> Only processes fields containing configuration annotations;
     * all other fields are ignored.</li>
     * <li><b>Naming:</b> Automatically converts category names and value keys to
     * {@code snake_case} to ensure TOML consistency.</li>
     * <li><b>Accessibility:</b> Forces fields to be accessible via {@link Field#setAccessible(boolean)}
     * to support private or protected configuration fields.</li>
     * </ul>
     *
     * @param clazz The class to be scanned for configuration fields.
     * @return A {@link List} of {@link FieldMeta} objects containing the reflected
     * field, its modifiers, and its associated annotation data.
     */
    private static List<FieldMeta> getFieldMetadata(Class<?> clazz) {
        return FIELD_META_CACHE.computeIfAbsent(clazz, c -> {
            List<FieldMeta> result = new ArrayList<>();
            for (Field field : c.getDeclaredFields()) {
                ConfigCategory categoryMeta = field.getAnnotation(ConfigCategory.class);
                ConfigValue valueMeta = field.getAnnotation(ConfigValue.class);
                if (categoryMeta == null && valueMeta == null) {
                    continue;
                }

                if (categoryMeta != null && valueMeta != null) {
                    LOGGER.error("Field {}.{} has both @ConfigCategory and @ConfigValue; only one annotation is allowed. Skipping.",
                            clazz.getName(), field.getName());
                    continue;
                }

                field.setAccessible(true);

                String categoryPath = null;
                String categoryDescription = "";
                if (categoryMeta != null) {
                    String categoryName = categoryMeta.name().isEmpty() ? field.getName() : categoryMeta.name();
                    categoryPath = toSnakeCase(categoryName);
                    categoryDescription = categoryMeta.description();
                }

                String valueKey = null;
                String valueComment = "";
                String valueRange = "";
                if (valueMeta != null) {
                    valueKey = toSnakeCase(valueMeta.key().isEmpty() ? field.getName() : valueMeta.key());
                    valueComment = valueMeta.comment();
                    valueRange = valueMeta.range();
                }

                result.add(new FieldMeta(
                        field,
                        Modifier.isStatic(field.getModifiers()),
                        categoryMeta,
                        valueMeta,
                        categoryPath,
                        categoryDescription,
                        valueKey,
                        valueComment,
                        valueRange
                ));
            }
            return result;
        });
    }

    /**
     * Applies {@code [mod_info].name/version} when Forge metadata is available.
     * <p>
     * Before {@link Loader} is ready, existing name/version values are preserved (no reset to empty).
     * This method only updates values when the current mod container can be resolved.
     *
     * @param modId  Forge mod id.
     * @param config target config file.
     * @return {@code true} if any header fields/comments were changed.
     */
    private static boolean tryApplyHeader(String modId, CommentedFileConfig config) {
        Path configPath = config.getFile().toPath();
        boolean changed = false;

        if (!isFMLReady()) {
            // Loader is not ready yet: keep existing values untouched.
            HEADERS_INITIALIZED.put(configPath, false);
        } else {
            try {
                Map<String, ModContainer> indexed = Loader.instance().getIndexedModList();
                if (indexed == null) {
                    HEADERS_INITIALIZED.put(configPath, false);
                } else {
                    ModContainer modContainer = indexed.get(modId);
                    if (modContainer != null) {
                        changed |= setIfDifferent(config, HEADER_NAME + ".name", modContainer.getName());
                        changed |= setIfDifferent(config, HEADER_NAME + ".version", modContainer.getVersion());

                        HEADERS_INITIALIZED.put(configPath, true);
                    } else {
                        LOGGER.warn("No ModContainer for modId \"{}\"; leaving mod_info name/version unchanged.", modId);
                        HEADERS_INITIALIZED.put(configPath, false);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not apply mod_info header for modId \"{}\"", modId, e);
                HEADERS_INITIALIZED.put(configPath, false);
            }
        }

        String headerComment = String.format("Generated by %s.", Tags.MOD_NAME);
        changed |= setCommentIfDifferent(config, HEADER_NAME, headerComment);

        return changed;
    }

    /**
     * Ensures mandatory base header fields that do not depend on Loader readiness.
     */
    private static boolean ensureHeaderBase(String modId, CommentedFileConfig config) {
        boolean changed = false;
        changed |= setIfDifferent(config, HEADER_NAME + ".id", modId);

        String headerComment = String.format("Generated by %s.", Tags.MOD_NAME);
        changed |= setCommentIfDifferent(config, HEADER_NAME, headerComment);

        return changed;
    }

    /**
     * Re-checks and applies header metadata ({@code mod_info}) for all initialized configs.
     * <p>
     * Called after mod loading has progressed so that name/version can be corrected if needed.
     */
    public static void applyPendingHeaders() {
        for (Class<?> configClass : INITIALIZED_CLASSES) {
            TOMLConfig configMeta = configClass.getAnnotation(TOMLConfig.class);
            if (configMeta == null) continue;

            String fileName = configMeta.name().endsWith(".toml") ? configMeta.name() : configMeta.name() + ".toml";
            Path configPath = Launch.minecraftHome.toPath()
                    .resolve(configMeta.rootDir())
                    .resolve(fileName);

            CommentedFileConfig config = CONFIG_CACHE.get(configPath);
            if (config != null) {
                synchronized (config) {
                    if (tryApplyHeader(configMeta.modId(), config)) {
                        config.save();
                    }
                }
            }
        }
    }

    /**
     * Trims internal helper caches to keep memory usage bounded in long sessions/reloads.
     * <p>
     * This also attempts to flush pending snake_case entries to disk when disk caching is enabled.
     */
    public static void trimCaches() {
        if (SNAKE_CASE_CACHE.size() > 4096) {
            SNAKE_CASE_CACHE.clear();
            snakeCaseCacheDirty = true;
        }
        persistSnakeCaseDiskCache();
    }

    /**
     * Configures optional disk persistence for {@link #SNAKE_CASE_CACHE}.
     * Cache file is stored under {@code <minecraft>/cache/toml_config/snake_case_cache.properties}.
     */
    public static synchronized void configureSnakeCaseDiskCache(Path mcDir, boolean enabled) {
        snakeCaseDiskCacheEnabled = enabled;
        if (!enabled) {
            snakeCaseCacheFile = null;
            snakeCaseCacheDirty = false;
            return;
        }

        try {
            Path cacheDir = mcDir.resolve("cache").resolve("toml_config");
            Files.createDirectories(cacheDir);
            snakeCaseCacheFile = cacheDir.resolve(SNAKE_CASE_CACHE_FILE_NAME);

            if (Files.exists(snakeCaseCacheFile)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(snakeCaseCacheFile)) {
                    props.load(in);
                }
                for (String key : props.stringPropertyNames()) {
                    String value = props.getProperty(key);
                    if (value != null && value.equals(computeSnakeCase(key))) {
                        SNAKE_CASE_CACHE.putIfAbsent(key, value);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize snake_case disk cache", e);
            snakeCaseCacheFile = null;
            snakeCaseDiskCacheEnabled = false;
        }
    }

    /**
     * Persists {@link #SNAKE_CASE_CACHE} to disk if disk caching is enabled and data changed.
     */
    private static synchronized void persistSnakeCaseDiskCache() {
        if (!snakeCaseDiskCacheEnabled || !snakeCaseCacheDirty || snakeCaseCacheFile == null) {
            return;
        }
        try {
            Properties props = new Properties();
            for (Map.Entry<String, String> e : SNAKE_CASE_CACHE.entrySet()) {
                props.setProperty(e.getKey(), e.getValue());
            }
            try (OutputStream out = Files.newOutputStream(snakeCaseCacheFile)) {
                props.store(out, "TOMLConfig snake_case cache");
            }
            snakeCaseCacheDirty = false;
        } catch (Exception e) {
            LOGGER.warn("Failed to persist snake_case disk cache", e);
        }
    }

    private static boolean setIfDifferent(CommentedFileConfig config, String key, Object newValue) {
        Object currentValue = config.get(key);
        if (Objects.equals(currentValue, newValue)) {
            return false;
        }

        config.set(key, newValue);
        return true;
    }

    private static boolean setCommentIfDifferent(CommentedFileConfig config, String key, String newComment) {
        String targetComment = (newComment == null || newComment.isEmpty()) ? null : newComment;
        String currentComment = config.getComment(key);
        if (Objects.equals(currentComment, targetComment)) {
            return false;
        }

        config.setComment(key, targetComment);
        return true;
    }

    private static Object normalizeForToml(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID) {
            return value.toString();
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).name();
        }
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                out.add(normalizeForToml(java.lang.reflect.Array.get(value, i)));
            }
            return out;
        }
        if (value instanceof Set) {
            List<Object> out = new ArrayList<>();
            for (Object item : (Set<?>) value) {
                out.add(normalizeForToml(item));
            }
            if (!(value instanceof SortedSet) && !(value instanceof LinkedHashSet)) {
                out.sort(ConfigManager::compareNormalizedTomlScalars);
            }
            return out;
        }
        if (value instanceof Iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                out.add(normalizeForToml(item));
            }
            return out;
        }
        return value;
    }

    /**
     * Deterministic ordering for {@link HashSet} and other non-ordered sets when writing TOML arrays,
     * so {@link Objects#equals} does not flip on repeated saves. {@link LinkedHashSet} and {@link SortedSet} keep iteration order.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareNormalizedTomlScalars(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Comparable && b instanceof Comparable && a.getClass() == b.getClass()) {
            return ((Comparable) a).compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static Object normalizeLoadedValue(Object loadedValue, Class<?> fieldType) {
        if (loadedValue == null) {
            return null;
        }
        if (fieldType.isEnum() && loadedValue instanceof String) {
            return ((String) loadedValue).trim();
        }
        return loadedValue;
    }

    /**
     * Resolves runtime and display range for a field.
     * <p>
     * Rules:
     * <ul>
     *     <li>boolean/Boolean always uses fixed {@code boolean{true,false}}</li>
     *     <li>explicit ranges for booleans are ignored and logged as warning</li>
     *     <li>otherwise explicit range wins over derived range</li>
     * </ul>
     */
    private static RangeInfo rangeInfo(String explicitRange, Class<?> fieldType, Object defaultValue, String fieldPath) {
        TypeInfo typeInfo = effectiveTypeInfo(fieldType, defaultValue);
        if (typeInfo == null) {
            return RangeInfo.EMPTY;
        }

        if (isBooleanType(typeInfo.type)) {
            if (explicitRange != null && !explicitRange.trim().isEmpty()) {
                String warnKey = explicitRange.trim() + "|" + fieldPath;
                if (BOOLEAN_RANGE_IGNORED_WARNED.add(warnKey)) {
                    LOGGER.warn("Range '{}' for boolean key '{}' is ignored. Allowed values are fixed to boolean{true,false}.",
                            explicitRange.trim(), fieldPath);
                }
            }
            // Do not apply range validation for booleans at runtime.
            // Keep only a fixed Allowed hint in comments.
            return new RangeInfo("", "boolean{true,false}");
        }

        if (explicitRange != null && !explicitRange.trim().isEmpty()) {
            String raw = explicitRange.trim();
            return new RangeInfo(raw, raw);
        }

        String raw = deriveRange(typeInfo.type);
        if (raw.isEmpty()) {
            return RangeInfo.EMPTY;
        }

        return new RangeInfo(raw, typeInfo.type.getSimpleName() + raw);
    }

    /**
     * Resolves the effective value type for scalar/array/iterable fields.
     * For iterables the type is inferred from the first default element when available.
     */
    private static TypeInfo effectiveTypeInfo(Class<?> fieldType, Object defaultValue) {
        if (fieldType == null) {
            return null;
        }

        Class<?> effectiveType = fieldType;
        if (fieldType.isArray()) {
            effectiveType = fieldType.getComponentType();
        } else if (Iterable.class.isAssignableFrom(fieldType)) {
            if (defaultValue instanceof Iterable) {
                Iterator<?> it = ((Iterable<?>) defaultValue).iterator();
                if (it.hasNext()) {
                    Object first = it.next();
                    if (first != null) {
                        effectiveType = first.getClass();
                    } else {
                        return null;
                    }
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }

        return new TypeInfo(effectiveType);
    }

    private static String deriveRange(Class<?> effectiveType) {
        if (effectiveType.isEnum()) {
            Object[] constants = effectiveType.getEnumConstants();
            if (constants != null && constants.length > 0 && constants.length <= ENUM_COMMENT_VALUES_LIMIT) {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(((Enum<?>) constants[i]).name());
                }
                sb.append('}');
                return sb.toString();
            }
            return "";
        }

        if (effectiveType == UUID.class || effectiveType == String.class) {
            return "";
        }

        if (effectiveType == byte.class || effectiveType == Byte.class) {
            return "[" + Byte.MIN_VALUE + ".." + Byte.MAX_VALUE + "]";
        }
        if (effectiveType == short.class || effectiveType == Short.class) {
            return "[" + Short.MIN_VALUE + ".." + Short.MAX_VALUE + "]";
        }
        if (effectiveType == int.class || effectiveType == Integer.class) {
            return "[" + Integer.MIN_VALUE + ".." + Integer.MAX_VALUE + "]";
        }
        if (effectiveType == long.class || effectiveType == Long.class) {
            return "[" + Long.MIN_VALUE + ".." + Long.MAX_VALUE + "]";
        }
        if (effectiveType == float.class || effectiveType == Float.class) {
            return "(" + (-Float.MAX_VALUE) + ".." + Float.MAX_VALUE + ")";
        }
        if (effectiveType == double.class || effectiveType == Double.class) {
            return "(" + (-Double.MAX_VALUE) + ".." + Double.MAX_VALUE + ")";
        }

        return "";
    }

    private static boolean isBooleanType(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private static final class TypeInfo {
        private final Class<?> type;

        private TypeInfo(Class<?> type) {
            this.type = type;
        }
    }

    private static final class RangeInfo {
        private static final RangeInfo EMPTY = new RangeInfo("", "");

        private final String raw;
        private final String display;

        private RangeInfo(String raw, String display) {
            this.raw = raw;
            this.display = display;
        }
    }

    /**
     * Converts a camelCase string to snake_case.
     * Example: {@code enableMachine} -> {@code enable_machine}.
     * <p>
     * Results are memoized in {@link #SNAKE_CASE_CACHE}; cache may also be persisted to disk.
     *
     * @param input The string to convert.
     * @return The snake_case formatted string.
     */
    private static String toSnakeCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        return SNAKE_CASE_CACHE.compute(input, (key, cached) -> {
            String computed = computeSnakeCase(key);
            if (!Objects.equals(cached, computed)) {
                snakeCaseCacheDirty = true;
                return computed;
            }
            return cached;
        });
    }

    private static String computeSnakeCase(String key) {
        StringBuilder out = new StringBuilder(key.length() + 8);
        char prev = 0;

        for (int i = 0; i < key.length(); i++) {
            char current = key.charAt(i);
            char next = i + 1 < key.length() ? key.charAt(i + 1) : 0;

            if (Character.isUpperCase(current)) {
                boolean hasPrev = i > 0;
                boolean prevIsLower = hasPrev && Character.isLowerCase(prev);
                boolean prevIsUpper = hasPrev && Character.isUpperCase(prev);
                boolean nextIsLower = next != 0 && Character.isLowerCase(next);

                if (hasPrev && (prevIsLower || (prevIsUpper && nextIsLower))) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(current));
            } else {
                out.append(current);
            }

            prev = current;
        }

        return out.toString();
    }

    public static boolean isFMLReady() {
        if (FML_READY_CACHED) {
            return true;
        }

        try {
            Method findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            findLoadedClass.setAccessible(true);
            boolean ready = findLoadedClass.invoke(Launch.classLoader, "net.minecraftforge.fml.common.Loader") != null;
            if (ready) {
                FML_READY_CACHED = true;
            }
            return ready;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getRangeComment(String range, Object value, boolean commentIsEmpty) {
        if (range == null || range.isEmpty()) return "";

        String placeholder = commentIsEmpty ? "@Allowed: " : "\n@Allowed: ";
        String displayRange = range.trim();
        String rawRange = stripTypePrefix(displayRange);

        Class<?> elementType = null;

        if (value != null) {
            if (value.getClass().isArray()) {
                elementType = value.getClass().getComponentType();
            } else if (value instanceof Iterable) {
                Iterator<?> iterator = ((Iterable<?>) value).iterator();
                if (iterator.hasNext()) {
                    Object firstElement = iterator.next();
                    if (firstElement != null) {
                        elementType = firstElement.getClass();
                    }
                }
            } else {
                elementType = value.getClass();
            }
        }

        if (elementType == null) {
            return placeholder + displayRange;
        }

        if (elementType == String.class || elementType == UUID.class || elementType.isEnum()) {
            if (rawRange.startsWith("{") && rawRange.endsWith("}")) {
                String content = rawRange.substring(1, rawRange.length() - 1);
                if (content.isEmpty()) {
                    LOGGER.error("Empty range for value type {}", elementType.getSimpleName());
                    return "";
                }

                if (elementType == UUID.class || elementType.isEnum()) {
                    String[] values = content.split(",");
                    for (String val : values) {
                        String trimmed = val.trim();
                        if (elementType == UUID.class && !isValidUUID(trimmed)) {
                            LOGGER.error("Invalid UUID in range: {}", trimmed);
                            return "";
                        }
                        if (elementType.isEnum() && !isValidEnum(trimmed, elementType)) {
                            LOGGER.error("Invalid Enum in range: {} for {}", trimmed, elementType.getSimpleName());
                            return "";
                        }
                    }
                }
                return placeholder + displayRange;
            }
            // Discrete DSL for String/UUID/Enum supports boolean expressions (!, &, |, grouping),
            // so non-set forms like "!{FAIL}" or "({a,b}|{c})&!{b}" are valid and should be preserved.
            return placeholder + displayRange;
        }

        if (isBooleanType(elementType)) {
            return placeholder + "boolean{true,false}";
        }

        if (Number.class.isAssignableFrom(elementType) || elementType.isPrimitive()) {
            return placeholder + displayRange;
        }

        LOGGER.warn("Unknown how to fully validate range '{}' for type {}. Appending anyway.", displayRange, elementType.getSimpleName());
        return placeholder + displayRange;
    }

    private static String stripTypePrefix(String range) {
        int brace = range.indexOf('{');
        int bracket = range.indexOf('[');
        int paren = range.indexOf('(');
        int idx = -1;
        if (brace >= 0) idx = brace;
        if (bracket >= 0) idx = idx < 0 ? bracket : Math.min(idx, bracket);
        if (paren >= 0) idx = idx < 0 ? paren : Math.min(idx, paren);
        if (idx <= 0) {
            return range;
        }

        // Only treat as prefix if before the opening is a single identifier with no spaces
        String prefix = range.substring(0, idx);
        if (!prefix.trim().equals(prefix)) {
            return range;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.')) {
                return range;
            }
        }

        return range.substring(idx);
    }

    private static boolean isValidUUID(String str) {
        try {
            UUID.fromString(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isValidEnum(String str, Class<?> enumClass) {
        if (!enumClass.isEnum()) return false;
        try {
            return Arrays.stream(enumClass.getEnumConstants())
                    .anyMatch(e -> ((Enum<?>) e).name().equalsIgnoreCase(str));
        } catch (Exception e) {
            return false;
        }
    }

    private static final class FieldMeta {
        private final Field field;
        private final boolean isStatic;
        private final ConfigCategory categoryMeta;
        private final ConfigValue valueMeta;
        private final String categoryPath;
        private final String categoryDescription;
        private final String valueKey;
        private final String valueComment;
        private final String valueRange;

        private FieldMeta(
                Field field,
                boolean isStatic,
                ConfigCategory categoryMeta,
                ConfigValue valueMeta,
                String categoryPath,
                String categoryDescription,
                String valueKey,
                String valueComment,
                String valueRange
        ) {
            this.field = field;
            this.isStatic = isStatic;
            this.categoryMeta = categoryMeta;
            this.valueMeta = valueMeta;
            this.categoryPath = categoryPath;
            this.categoryDescription = categoryDescription;
            this.valueKey = valueKey;
            this.valueComment = valueComment;
            this.valueRange = valueRange;
        }
    }

}