package com.qsteam.toml_config.core;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.qsteam.toml_config.Tags;
import com.qsteam.toml_config.TOMLConfigCfg;
import com.qsteam.toml_config.api.TOMLConfig;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Coremod bootstrap plugin.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Discover {@code @TOMLConfig} classes by scanning jars/directories using ASM (header-only).</li>
 *   <li>Optionally cache jar scan results under {@code .minecraft/cache/toml_config} to speed up subsequent launches.</li>
 *   <li>Honor {@link TOMLConfigCfg.MainCategory#caching} to enable/disable disk cache writes.</li>
 *   <li>Early load discovered config classes to trigger injected {@code <clinit>} init hooks.</li>
 * </ul>
 */
@IFMLLoadingPlugin.Name(TOMLLoadingPlugin.CORE_MOD_NAME)
@IFMLLoadingPlugin.SortingIndex(Integer.MIN_VALUE + 1)
public class TOMLLoadingPlugin implements IFMLLoadingPlugin {

    protected static final String CORE_MOD_NAME = "TOMLConfigCore";
    private static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME + "/" + CORE_MOD_NAME);

    public static final Set<String> discoveredConfigs = new HashSet<>();
    private static final String ANNOTATION_DESC = "Lcom/qsteam/toml_config/api/TOMLConfig;";
    private static final String CACHE_FILE_NAME = "jar_scan_cache.properties";
    /** Cache entry value prefix: CRC32 of jar bytes (hex) then class list, so same size/mtime with different content rescans. */
    private static final String JAR_CACHE_V2_PREFIX = "v2:";

    private final Properties jarScanCache = new Properties();
    private boolean jarScanCacheDirty = false;
    private Path jarScanCacheFile;
    private boolean diskCachingEnabled = true;

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{TOMLClassTransformer.class.getName()};
    }

    @Override
    public void injectData(Map<String, Object> data) {
        File mcDir = (File) data.get("mcLocation");
        File modsDir = new File(mcDir, "mods");
        diskCachingEnabled = isDiskCachingEnabled();
        if (diskCachingEnabled) {
            initJarScanCache(mcDir.toPath());
        } else {
            jarScanCache.clear();
            jarScanCacheDirty = false;
            jarScanCacheFile = null;
        }
        ConfigManager.configureSnakeCaseDiskCache(mcDir.toPath(), diskCachingEnabled);

        if (modsDir.exists() && modsDir.isDirectory()) {
            scanDirectory(modsDir);
        }

        Boolean isDevEnv = (Boolean) Launch.blackboard.get("fml.deobfuscatedEnvironment");
        if (isDevEnv != null && isDevEnv) {
            scanDevEnvironment();
        }

        for (String className : discoveredConfigs) {
            try {
                // <clinit> already calls ConfigManager.init via TOMLClassTransformer.
                Class.forName(className, true, Launch.classLoader);
            } catch (Exception e) {
                LOGGER.error("Failed to early-load config class: {}", className, e);
            }
        }

        persistJarScanCache();
    }

    private void scanDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file);
            } else if (file.getName().endsWith(".jar") || file.getName().endsWith(".zip")) {
                scanJar(file);
            }
        }
    }

    /**
     * Scans one jar/zip for {@code @TOMLConfig} classes.
     * <p>
     * When disk caching is enabled this method first attempts a cache hit and then
     * writes a v2 entry with CRC32 verification metadata.
     */
    private void scanJar(File jarFile) {
        String cacheKey = createJarCacheKey(jarFile);
        if (diskCachingEnabled) {
            String cachedValue = jarScanCache.getProperty(cacheKey);
            if (cachedValue != null) {
                if (tryApplyCachedJarScan(jarFile, cachedValue)) {
                    return;
                }
            }
        }

        Set<String> foundInJar = new HashSet<>();
        CRC32 crc32 = new CRC32();
        try (InputStream raw = Files.newInputStream(jarFile.toPath());
             CheckedInputStream cis = new CheckedInputStream(raw, crc32);
             ZipInputStream zis = new ZipInputStream(cis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) {
                    String className = findAnnotatedConfigClass(zis);
                    if (className != null) {
                        discoveredConfigs.add(className);
                        foundInJar.add(className);
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to scan jar: {}", jarFile.getName(), e);
            return;
        }

        if (diskCachingEnabled) {
            String payload = JAR_CACHE_V2_PREFIX + Long.toHexString(crc32.getValue()) + ";" + String.join(";", foundInJar);
            jarScanCache.setProperty(cacheKey, payload);
            jarScanCacheDirty = true;
        }
    }

    /**
     * Applies cached scan when still valid. {@code v2:}&lt;crchex&gt;;&lt;classes&gt; verifies CRC32 of the jar file;
     * legacy values (plain class list) are accepted without CRC check.
     */
    private boolean tryApplyCachedJarScan(File jarFile, String cachedValue) {
        if (cachedValue.isEmpty()) {
            return true;
        }
        if (cachedValue.startsWith(JAR_CACHE_V2_PREFIX)) {
            String body = cachedValue.substring(JAR_CACHE_V2_PREFIX.length());
            int semi = body.indexOf(';');
            if (semi < 0) {
                return false;
            }
            try {
                long expectedCrc = Long.parseUnsignedLong(body.substring(0, semi), 16);
                if (expectedCrc != crc32File(jarFile)) {
                    return false;
                }
                String rest = body.substring(semi + 1);
                for (String className : rest.split(";")) {
                    if (!className.isEmpty()) {
                        discoveredConfigs.add(className);
                    }
                }
                return true;
            } catch (NumberFormatException | IOException e) {
                return false;
            }
        }
        for (String className : cachedValue.split(";")) {
            if (!className.isEmpty()) {
                discoveredConfigs.add(className);
            }
        }
        return true;
    }

    private static long crc32File(File jarFile) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[65536];
        try (InputStream in = Files.newInputStream(jarFile.toPath())) {
            int r;
            while ((r = in.read(buf)) != -1) {
                crc.update(buf, 0, r);
            }
        }
        return crc.getValue();
    }

    private void initJarScanCache(Path mcDir) {
        try {
            Path cacheDir = mcDir.resolve("cache").resolve("toml_config");
            Files.createDirectories(cacheDir);
            jarScanCacheFile = cacheDir.resolve(CACHE_FILE_NAME);
            if (Files.exists(jarScanCacheFile)) {
                try (InputStream is = Files.newInputStream(jarScanCacheFile)) {
                    jarScanCache.load(is);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize TOML config scan cache", e);
            jarScanCacheFile = null;
        }
    }

    private void persistJarScanCache() {
        if (!diskCachingEnabled || !jarScanCacheDirty || jarScanCacheFile == null) {
            return;
        }

        try (OutputStream os = Files.newOutputStream(jarScanCacheFile)) {
            jarScanCache.store(os, "TOMLConfig jar annotation scan cache");
            jarScanCacheDirty = false;
        } catch (Exception e) {
            LOGGER.warn("Failed to persist TOML config scan cache", e);
        }
    }

    private String createJarCacheKey(File jarFile) {
        return jarFile.getAbsolutePath() + "|" + jarFile.length() + "|" + jarFile.lastModified();
    }

    /**
     * Reads the runtime switch for disk caching from {@link TOMLConfigCfg}.
     *
     * @return {@code true} when disk caching should be used; falls back to {@code true} on read failure.
     */
    private boolean isDiskCachingEnabled() {
        Boolean fromDisk = readCachingFlagFromToml();
        if (fromDisk != null) {
            return fromDisk;
        }
        try {
            return TOMLConfigCfg.MAIN.caching;
        } catch (Throwable t) {
            LOGGER.warn("Could not read TOMLConfigCfg.main.caching, falling back to caching enabled.", t);
            return true;
        }
    }

    /**
     * Reads {@code [main].caching} directly from TOML to avoid early-init races
     * where {@link TOMLConfigCfg} fields still have Java defaults.
     */
    private Boolean readCachingFlagFromToml() {
        try {
            TOMLConfig meta = TOMLConfigCfg.class.getAnnotation(TOMLConfig.class);
            if (meta == null) {
                return null;
            }
            String fileName = meta.name().endsWith(".toml") ? meta.name() : meta.name() + ".toml";
            Path configPath = Launch.minecraftHome.toPath()
                    .resolve(meta.rootDir())
                    .resolve(fileName);
            if (!Files.exists(configPath)) {
                return null;
            }

            CommentedFileConfig cfg = CommentedFileConfig.builder(configPath).sync().build();
            try {
                cfg.load();
                Object raw = cfg.get("main.caching");
                if (raw instanceof Boolean) {
                    return (Boolean) raw;
                }
                if (raw instanceof String) {
                    return Boolean.parseBoolean(((String) raw).trim());
                }
            } finally {
                cfg.close();
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not read caching flag from TOML file, fallback to runtime value.", t);
        }
        return null;
    }

    private String findAnnotatedConfigClass(InputStream is) throws IOException {
        ClassReader cr = new ClassReader(is);
        AnnotationDetectingVisitor visitor = new AnnotationDetectingVisitor();
        cr.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (visitor.annotated) {
            return visitor.className;
        }
        return null;
    }

    private void checkClassForAnnotation(InputStream is) throws IOException {
        String className = findAnnotatedConfigClass(is);
        if (className != null) {
            discoveredConfigs.add(className);
        }
    }

    private void scanDevEnvironment() {
        String classPath = System.getProperty("java.class.path");
        for (String path : classPath.split(File.pathSeparator)) {
            File file = new File(path);
            if (file.isDirectory()) {
                scanDirectoryForClasses(file);
            } else if ((file.getName().endsWith(".jar") || file.getName().endsWith(".zip")) && !path.contains(".gradle") && !path.contains("jre") && !path.contains("jdk")) {
                scanJar(file);
            }
        }
    }

    private void scanDirectoryForClasses(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectoryForClasses(file);
            } else if (file.getName().endsWith(".class")) {
                try (InputStream is = Files.newInputStream(file.toPath())) {
                    checkClassForAnnotation(is);
                } catch (Exception e) {
                    LOGGER.error("Failed to scan class file in dev env: {}", file.getName(), e);
                }
            }
        }
    }

    private static final class AnnotationDetectingVisitor extends ClassVisitor {
        private String className;
        private boolean annotated;

        private AnnotationDetectingVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name.replace('/', '.');
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (ANNOTATION_DESC.equals(descriptor)) {
                annotated = true;
            }
            return super.visitAnnotation(descriptor, visible);
        }
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
