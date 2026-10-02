package cn.jehorstudio.minetale.contentpack;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

// 外部归档必须先在临时副本上验证，再原子提交到安装库。
public final class ContentPackInstaller {
    private ContentPackInstaller() {}

    public static ContentPackArchiveValidator.ValidatedArchive install(Path source, Path library) throws IOException {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(library, "library");
        Path normalizedLibrary = library.toAbsolutePath().normalize(); Files.createDirectories(normalizedLibrary);
        Path temporary = normalizedLibrary.resolve(".install-" + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            ContentPackArchiveValidator.ValidatedArchive validated = ContentPackArchiveValidator.validate(temporary);
            if (validated.manifest().development() != null) throw new IOException("开发部署不能通过正式 Content Pack 安装入口导入。");
            String fileName = validated.manifest().contentPackId().replaceAll("[^a-z0-9._-]", "-")
                    + "-" + validated.manifest().version() + ".mtpack";
            Path target = normalizedLibrary.resolve(fileName).normalize();
            if (!target.getParent().equals(normalizedLibrary)) throw new IOException("Content Pack 安装目标越出安装目录。");
            if (Files.exists(target)) {
                ContentPackArchiveValidator.ValidatedArchive existing = ContentPackArchiveValidator.validate(target);
                if (!existing.manifest().contentDigest().equals(validated.manifest().contentDigest())) throw new IOException("同一 Content Pack ID/version 已安装不同摘要的内容。");
                return existing;
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target); }
            return ContentPackArchiveValidator.validate(target);
        } finally { Files.deleteIfExists(temporary); }
    }
}
