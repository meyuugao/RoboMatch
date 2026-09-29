package me.yuugao.robomatch.service;

import me.yuugao.robomatch.exception.PayloadTooLargeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Файловое хранилище сгенерированных отчётов.
 * Модель - {@link AttachmentStorage}/{@link SimulationStorage}:
 * локальный диск под корнем EXPORT_ROOT (env, дефолт ./data/exports),
 * путь задаётся ТОЛЬКО серверными идентификаторами
 * {root}/{projectId}/{exportId}.{ext} - path traversal исключён
 * конструкцией.
 *
 * <p>БЕЗОПАСНОСТЬ: белый список MIME по расширению (pdf/xlsx/csv -
 * только то, что генерируют свои генераторы, произвольные типы не
 * сохраняются), лимит размера 50 МБ (превышение - понятная ошибка,
 * отчёт такого размера невозможен на реальных данных - защита от
 * аномалий), resolve проверяет, что итоговый путь остался под
 * корнем (защита в глубину на случай битых данных в БД).
 *
 * <p>Удаление проекта - каскад строк БД (V1) + физическое удаление
 * каталога проекта best-effort: ошибка файловой системы
 * логируется, но не откатывает удаление проекта.
 */
@Component
public class ExportStorage {

    /**
 * Лимит размера отчёта (env EXPORT_MAX_BYTES, дефолт 50 МБ).
 */
    static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;
    private static final Logger log =
            LoggerFactory.getLogger(ExportStorage.class);
    /**
 * Разрешённые форматы и их MIME (генераторы создают только их).
 */
    private static final Map<String, String> MIME_BY_FORMAT = Map.of(
            "pdf", "application/pdf",
            "xlsx", "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet",
            "csv", "text/csv; charset=UTF-8");

    private final Path root;
    private final long maxBytes;

    /**
 * Создаёт хранилище на локальном диске.
 *
 * @param root корень EXPORT_ROOT (env, дефолт ./data/exports)
 * @param maxBytes лимит размера отчёта (env EXPORT_MAX_BYTES, дефолт 50 МБ)
 */
    public ExportStorage(
            @Value("${app.export.root:./data/exports}") String root,
            @Value("${app.export.max-bytes:52428800}") long maxBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    /**
 * MIME-тип формата (для Content-Type скачивания); null - чужой формат.
 *
 * @param format pdf/xlsx/csv
 * @return MIME-тип или null для неразрешённого формата
 */
    public String mimeOf(String format) {
        return MIME_BY_FORMAT.get(format);
    }

    /**
 * Лимит размера (для понятной ошибки при аномально большом отчёте).
 *
 * @return максимум размера отчёта, байт
 */
    public long maxBytes() {
        return maxBytes;
    }

    /**
 * Сохранить файл отчёта: {root}/{projectId}/{exportId}.{ext}.
 * Возвращает ОТНОСИТЕЛЬНЫЙ путь для БД. Проверяет формат по белому
 * списку и лимит размера.
 *
 * @param projectId идентификатор проекта (каталог)
 * @param exportId идентификатор выгрузки (имя файла)
 * @param format pdf/xlsx/csv
 * @param content байты отчёта
 * @return относительный путь для БД
 * @throws IllegalArgumentException неизвестный формат
 * @throws PayloadTooLargeException файл больше лимита - 413
 * (иначе было бы 500 через catch-all)
 */
    public String save(Long projectId, Long exportId, String format,
                       byte[] content) {
        if (!MIME_BY_FORMAT.containsKey(format)) {
            throw new IllegalArgumentException(
                    "Недопустимый формат отчёта: " + format);
        }
        if (content != null && content.length > maxBytes) {
            throw new PayloadTooLargeException("Отчёт больше лимита "
                    + (maxBytes / 1024 / 1024) + " МБ - уменьшите состав "
                    + "проекта и повторите экспорт");
        }
        String relative = projectId + "/" + exportId + "." + format;
        try {
            Path target = root.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return relative;
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Не удалось сохранить файл отчёта", ex);
        }
    }

    /**
 * Прочитать файл по относительному пути из БД; null - файл не
 * существует (404 вызывающего).
 *
 * @param relativePath относительный путь из БД
 * @return содержимое файла или null
 */
    public byte[] read(String relativePath) {
        try {
            Path target = resolve(relativePath);
            if (!Files.exists(target)) {
                return null;
            }
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Не удалось прочитать файл отчёта", ex);
        }
    }

    /**
 * Размер файла (для истории без чтения содержимого); -1 - файла
 * нет ИЛИ путь битый (повреждённая строка БД
 * не должна ронять всю историю проекта - потому и здесь защита
 * в глубину, как в read).
 *
 * @param relativePath относительный путь из БД
 * @return размер в байтах или -1
 */
    public long sizeOf(String relativePath) {
        try {
            Path target = resolve(relativePath);
            return Files.exists(target) ? Files.size(target) : -1;
        } catch (IOException | IllegalArgumentException ex) {
            return -1;
        }
    }

    /**
 * Удалить один файл (DELETE выгрузки).
 *
 * @param relativePath относительный путь из БД
 */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException ex) {
            log.warn("Не удалось удалить файл отчёта {}: {}", relativePath,
                    ex.toString());
        }
    }

    /**
 * Удалить каталог всех отчётов проекта (удаление проекта).
 * Best-effort: строки БД уже удалены каскадом, ошибка файловой
 * системы логируется и не мешает удалению.
 *
 * @param projectId идентификатор проекта (каталог в хранилище)
 */
    public void deleteProjectFiles(Long projectId) {
        Path dir = root.resolve(String.valueOf(projectId)).normalize();
        if (!dir.startsWith(root) || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    log.warn("Не удалось удалить {}: {}", p, ex.toString());
                }
            });
        } catch (IOException ex) {
            log.warn("Не удалось удалить каталог отчётов {}: {}",
                    dir, ex.toString());
        }
    }

    /**
 * Абсолютный путь с проверкой выхода за корень (path traversal).
 */
    private Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Недопустимый путь файла отчёта");
        }
        return target;
    }

    /**
 * Тестовый доступ к корню (проверки в тестах хранилища).
 */
    Path root() {
        return root;
    }
}
