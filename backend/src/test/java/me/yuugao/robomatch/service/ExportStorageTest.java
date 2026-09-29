package me.yuugao.robomatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit-тест хранилища отчётов:
 * path traversal исключён конструкцией и защитой в глубину, белый
 * список форматов, лимит размера, удаление каталога проекта best-effort.
 */
class ExportStorageTest {

    @TempDir
    Path root;

    private ExportStorage storage() {
        return new ExportStorage(root.toString(), 50 * 1024 * 1024);
    }

    @Test
    void saveAndRead_roundTrip_relativePath() throws IOException {
        ExportStorage storage = storage();
        String relative = storage.save(3L, 11L, "pdf",
                new byte[]{1, 2, 3});
        assertThat(relative).isEqualTo("3/11.pdf");
        assertThat(storage.read(relative)).isEqualTo(new byte[]{1, 2, 3});
        assertThat(storage.sizeOf(relative)).isEqualTo(3);
        assertThat(Files.exists(root.resolve("3/11.pdf"))).isTrue();
    }

    @Test
    void pathTraversal_rejected() {
        ExportStorage storage = storage();
        // попытка выйти за корень через относительный путь из БД
        assertThatThrownBy(() -> storage.read("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимый путь");
        assertThatThrownBy(() -> storage.read("3/../../secret.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownFormat_rejected_whitelist() {
        ExportStorage storage = storage();
        assertThatThrownBy(() -> storage.save(3L, 12L, "exe",
                new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимый формат");
        assertThat(storage.mimeOf("exe")).isNull();
        assertThat(storage.mimeOf("pdf")).isEqualTo("application/pdf");
        assertThat(storage.mimeOf("xlsx")).contains("spreadsheetml");
        assertThat(storage.mimeOf("csv")).isEqualTo("text/csv; charset=UTF-8");
    }

    @Test
    void sizeLimit_enforced() {
        ExportStorage storage = new ExportStorage(root.toString(), 8);
        // понятный 413 (PayloadTooLargeException),
        // а не 500 через catch-all
        assertThatThrownBy(() -> storage.save(3L, 13L, "csv",
                new byte[16]))
                .isInstanceOf(
                        me.yuugao.robomatch.exception.PayloadTooLargeException
                                .class)
                .hasMessageContaining("лимита");
    }

    @Test
    void deleteProjectFiles_bestEffort() throws IOException {
        ExportStorage storage = storage();
        storage.save(5L, 1L, "pdf", new byte[]{1});
        storage.save(5L, 2L, "csv", new byte[]{2});
        storage.save(6L, 3L, "xlsx", new byte[]{3});
        storage.deleteProjectFiles(5L);
        assertThat(Files.exists(root.resolve("5"))).isFalse();
        // чужой каталог не задет
        assertThat(Files.exists(root.resolve("6/3.xlsx"))).isTrue();
        // повторный вызов по отсутствующему каталогу — без ошибок
        storage.deleteProjectFiles(5L);
        storage.deleteProjectFiles(999L);
        assertThat(Files.exists(root.resolve("6/3.xlsx"))).isTrue();
    }

    @Test
    void delete_singleFile() throws IOException {
        ExportStorage storage = storage();
        String relative = storage.save(7L, 4L, "pdf", new byte[]{9});
        storage.delete(relative);
        assertThat(storage.read(relative)).isNull();
        assertThat(Files.exists(root.resolve("7"))).isTrue(); // каталог
        // остался (в нём могут быть другие отчёты)
    }

    @Test
    void read_missingFile_returnsNull() {
        assertThat(storage().read("3/404.pdf")).isNull();
        assertThat(storage().sizeOf("3/404.pdf")).isEqualTo(-1);
    }
}
