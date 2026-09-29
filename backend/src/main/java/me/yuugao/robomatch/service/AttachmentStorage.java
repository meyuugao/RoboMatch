package me.yuugao.robomatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Файловое хранилище вложений проекта.
 * <p>
 * РЕШЕНИЕ: локальный диск под корнем UPLOAD_ROOT
 * (env, дефолт ./data/attachments - внутри контейнера том compose).
 * Все операции над файлами собраны здесь за одним интерфейсом -
 * замена на S3/MinIO в будущем не затронет сервисы.
 * <p>
 * БЕЗОПАСНОСТЬ:
 * - имя файла пользователя НЕ участвует в построении пути напрямую:
 * финальный путь = {projectId}/{attachmentId}_{safeName}, где safeName
 * санитизируется (без каталогов, управляющих символов, ограничение
 * длины) - path traversal исключён конструкцией;
 * - в БД хранится ОТНОСИТЕЛЬНЫЙ путь; абсолютный корень - только env;
 * - resolve дополнительно проверяет, что итоговый путь остался под
 * корнем (защита в глубину на случай битых данных в БД).
 */
@Component
public class AttachmentStorage {

    /**
 * Подкаталог для входящих файлов до commit транзакции.
 */
    static final String TMP_DIR = "tmp";
    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);
    /**
 * Максимальная длина санитизированного имени файла (без расширения запаса).
 */
    private static final int MAX_FILE_NAME_LENGTH = 128;

    private final Path root;
    private final long maxBytes;

    /**
 * Создаёт хранилище на локальном диске.
 *
 * @param root корень UPLOAD_ROOT (env, дефолт ./data/attachments)
 * @param maxBytes лимит размера файла (env UPLOAD_MAX_BYTES, дефолт 10 МБ)
 */
    public AttachmentStorage(
            @Value("${app.upload.root:./data/attachments}") String root,
            @Value("${app.upload.max-bytes:10485760}") long maxBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    /**
 * Санитизация имени файла: только отображаемые символы, без
 * разделителей путей и «..», длина до 128. Пустой результат -
 * нейтральное «file» (id вложения всё равно делает путь уникальным).
 */
    static String sanitize(String fileName) {
        if (fileName == null) {
            return "file";
        }
        String name = fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        String base = name.replaceAll("\\.[^.]*$", "");
        // одиночные точки и «..» как имя целиком - тоже нейтрализуем
        if (base.isEmpty() || base.chars().allMatch(ch -> ch == '.')) {
            return "file";
        }
        return name.length() <= MAX_FILE_NAME_LENGTH ? name
                : name.substring(0, MAX_FILE_NAME_LENGTH);
    }

    /**
 * Лимит загрузки в байтах (env UPLOAD_MAX_BYTES, дефолт 10 МБ).
 *
 * @return максимум размера файла, байт
 */
    public long maxBytes() {
        return maxBytes;
    }

    /**
 * Сохраняет входящий файл во временную зону {root}/tmp/{uuid}.
 * Возвращённый путь живёт до promote/deleteQuietly - вызывающий
 * обязан убрать файл в любом исходе (валидация не прошла, БД
 * отказала, успех - promote).
 *
 * @param file входящий multipart-файл
 * @return абсолютный путь временного файла под {root}/tmp
 */
    public Path saveTemp(MultipartFile file) {
        try {
            Path tmpDir = root.resolve(TMP_DIR);
            Files.createDirectories(tmpDir);
            Path target = Files.createTempFile(tmpDir, "upload-", ".part");
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException ex) {
            throw new UncheckedIOException("Не удалось сохранить входящий файл", ex);
        }
    }

    /**
 * Переносит временный файл в постоянное место
 * {root}/{projectId}/{attachmentId}_{safeName} и возвращает
 * ОТНОСИТЕЛЬНЫЙ путь для БД ({projectId}/{attachmentId}_{safeName}).
 *
 * @param tmpFile временный файл из saveTemp
 * @param projectId идентификатор проекта (каталог)
 * @param attachmentId идентификатор вложения (уникальность имени)
 * @param originalFileName исходное имя файла пользователя (санитизируется)
 * @return относительный путь для БД
 */
    public String promote(Path tmpFile, Long projectId, Long attachmentId,
                          String originalFileName) {
        try {
            Path dir = root.resolve(projectId.toString());
            Files.createDirectories(dir);
            Path target = dir.resolve(attachmentId + "_" + sanitize(originalFileName));
            Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING);
            return projectId + "/" + target.getFileName();
        } catch (IOException ex) {
            throw new UncheckedIOException("Не удалось сохранить файл вложения", ex);
        }
    }

    /**
 * Абсолютный путь по относительному из БД (с защитой в глубину).
 *
 * @param relativePath относительный путь из БД
 * @return абсолютный путь под корнем хранилища
 */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            // данные в БД теоретически могут быть испорчены - не даём
            // вырваться за корень хранилища (path traversal, глубина)
            throw new IllegalArgumentException("Недопустимый путь вложения");
        }
        return resolved;
    }

    /**
 * Удаление файла «по возможности»: ошибка - в лог, не выше.
 *
 * @param file файл (null игнорируется)
 */
    public void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("Не удалось удалить файл {}: {}", file, ex.toString());
        }
    }

    /**
 * Удаление каталога проекта целиком (
 * вместе с загруженными файлами). Best-effort: строки БД уже
 * удалены каскадом, ошибка файловой системы не должна откатывать
 * транзакцию и пугать пользователя - логируем (компенсация).
 *
 * @param projectId идентификатор проекта (каталог в хранилище)
 */
    public void deleteProjectFiles(Long projectId) {
        Path dir = root.resolve(projectId.toString()).normalize();
        if (!dir.startsWith(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietlyUnchecked);
        } catch (IOException ex) {
            log.warn("Не удалось удалить файлы проекта {}: {}", projectId, ex.toString());
        }
    }

    private void deleteQuietlyUnchecked(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Не удалось удалить {}: {}", path, ex.toString());
        }
    }

    /**
 * Тестовый доступ к корню (проверки в IT).
 */
    Path root() {
        return root;
    }
}
