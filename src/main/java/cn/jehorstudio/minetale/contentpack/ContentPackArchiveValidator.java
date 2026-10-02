package cn.jehorstudio.minetale.contentpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// 归档进入 PackRepository 前必须完成大小、路径与摘要验证。
public final class ContentPackArchiveValidator {
    public static final int MAX_ENTRIES = 8192;
    public static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    public static final double MAX_COMPRESSION_RATIO = 100.0D;

    private ContentPackArchiveValidator() {}

    public static ValidatedArchive validate(Path archive) throws IOException {
        Map<String, ZipEntry> entries = new HashMap<>(); Set<String> caseFolded = new HashSet<>(); long total = 0L;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                ContentPackManifest.require(!entry.isDirectory(), "Content Pack 不接受目录占位条目：" + entry.getName());
                String path = ContentPackManifest.safePath(entry.getName());
                ContentPackManifest.require(entries.size() < MAX_ENTRIES, "Content Pack 条目数量超过限制。");
                ContentPackManifest.require(caseFolded.add(path.toLowerCase(Locale.ROOT)), "Content Pack 路径重复或发生大小写碰撞：" + path);
                long size = entry.getSize(); long compressed = entry.getCompressedSize();
                ContentPackManifest.require(size >= 0L && size <= MAX_ENTRY_BYTES, "Content Pack 条目大小无效：" + path);
                ContentPackManifest.require(compressed >= 0L, "Content Pack 条目压缩大小无效：" + path);
                if (size > 0L) ContentPackManifest.require(compressed > 0L && (double) size / compressed <= MAX_COMPRESSION_RATIO, "Content Pack 条目压缩比过高：" + path);
                total = Math.addExact(total, size); ContentPackManifest.require(total <= MAX_TOTAL_BYTES, "Content Pack 总展开大小超过限制。");
                entries.put(path, entry);
            }
            ContentPackManifest.require(entries.containsKey("contentpack.json"), "Content Pack 缺少 contentpack.json。");
            ContentPackManifest.require(entries.containsKey("pack.mcmeta"), "Content Pack 缺少 pack.mcmeta。");
            ContentPackManifest manifest;
            try (InputStream stream = zip.getInputStream(entries.get("contentpack.json")); InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject(); manifest = ContentPackManifest.parse(root);
            } catch (RuntimeException exception) { throw new IllegalArgumentException("无法解析 Content Pack 清单。", exception); }

            Set<String> declared = new HashSet<>();
            for (ContentPackManifest.Resource resource : manifest.resources()) {
                declared.add(resource.path()); ZipEntry entry = entries.get(resource.path());
                ContentPackManifest.require(entry != null, "Content Pack 清单引用缺失条目：" + resource.path());
                String actual = digest(zip.getInputStream(entry), MAX_ENTRY_BYTES);
                ContentPackManifest.require(actual.equals(resource.sha256()), "Content Pack 条目摘要不匹配：" + resource.path());
            }
            Set<String> actual = new HashSet<>(entries.keySet()); actual.remove("contentpack.json");
            ContentPackManifest.require(actual.equals(declared), "Content Pack 清单未精确覆盖全部归档条目。");
            ContentPackManifest.require(inventoryDigest(manifest.resources()).equals(manifest.contentDigest()), "Content Pack contentDigest 不匹配。");
            return new ValidatedArchive(archive.toAbsolutePath().normalize(), manifest);
        }
    }

    static String inventoryDigest(List<ContentPackManifest.Resource> resources) {
        List<ContentPackManifest.Resource> ordered = new ArrayList<>(resources); ordered.sort(Comparator.comparing(ContentPackManifest.Resource::path));
        StringBuilder canonical = new StringBuilder();
        for (ContentPackManifest.Resource resource : ordered) canonical.append(resource.path()).append('\0').append(resource.sha256()).append('\n');
        return digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(InputStream stream, long limit) throws IOException {
        MessageDigest digest = sha256(); byte[] buffer = new byte[8192]; long total = 0L;
        try (stream) { int read; while ((read = stream.read(buffer)) >= 0) { total += read; ContentPackManifest.require(total <= limit, "Content Pack 条目读取大小超过限制。"); digest.update(buffer, 0, read); } }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digest(byte[] bytes) { MessageDigest digest = sha256(); return HexFormat.of().formatHex(digest.digest(bytes)); }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("JVM 缺少 SHA-256。", exception); } }

    public record ValidatedArchive(Path path, ContentPackManifest manifest) {}
}
