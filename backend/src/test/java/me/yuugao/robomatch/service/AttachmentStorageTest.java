package me.yuugao.robomatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Unit-тест файлового хранилища вложений
 * (включая прямой тест sanitize):
 * санитизация имени (path traversal), перенос tmp -> постоянное место,
 * containment-проверка resolve, удаление каталога проекта.
 * <p>
 * Ключевой инвариант безопасности: sanitized-имя не содержит
 * разделителей путей и control-символов - оно всегда ровно один
 * сегмент пути, поэтому «..» внутри имени (например,
 * «.._.._file.txt») безопасно: выйти из каталога {projectId} нельзя.
 */
class AttachmentStorageTest {

    @TempDir
    Path root;

    private AttachmentStorage storage() {
        return new AttachmentStorage(root.toAbsolutePath().toString(), 10_000_000L);
    }

    // --- санитизация имени (path traversal) ------------------------------

    @Test
    void sanitize_stripsPathSeparatorsAndControlChars() {
        // unix-путь: разделители срезаны - остаётся ОДИН безопасный сегмент
        // (имя с «..» внутри сегмента выйти из каталога не может)
        assertThat(AttachmentStorage.sanitize("../../etc/passwd"))
                .isEqualTo(".._.._etc_passwd")
                .doesNotContain("/");
        // windows-путь: разделители срезаны, остаётся ОДИН безопасный сегмент
        assertThat(AttachmentStorage.sanitize("..\\..\\windows\\system32"))
                .isEqualTo(".._.._windows_system32");
        // путь не содержит \ / после санитизации
        assertThat(AttachmentStorage.sanitize("..\\..\\windows\\system32"))
                .doesNotContain("\\")
                .doesNotContain("/");
        assertThat(AttachmentStorage.sanitize("..")).isEqualTo("file");
        assertThat(AttachmentStorage.sanitize(".")).isEqualTo("file");
        assertThat(AttachmentStorage.sanitize("   ")).isEqualTo("file");
        assertThat(AttachmentStorage.sanitize(null)).isEqualTo("file");
        // контрольные символы (в т.ч. перевод строки) - заменяются
        assertThat(AttachmentStorage.sanitize("импорт\n.csv")).isEqualTo("импорт_.csv");
        // нормальное имя не калечится
        assertThat(AttachmentStorage.sanitize("склад_параметры.xlsx"))
                .isEqualTo("склад_параметры.xlsx");
    }

    @Test
    void sanitize_capsLength() {
        String longName = "a".repeat(500) + ".csv";
        assertThat(AttachmentStorage.sanitize(longName)).hasSize(128);
    }

    // --- жизненный цикл файла --------------------------------------------

    @Test
    void saveTemp_promote_andRelativePath() throws IOException {
        AttachmentStorage storage = storage();
        Path tmp = storage.saveTemp(new MockMultipartFile("file", "импорт.csv",
                "text/csv", "code\n1\n".getBytes()));
        assertThat(tmp).exists();

        String relative = storage.promote(tmp, 5L, 7L, "../../вредное имя.csv");
        // путь строится из серверных id + санитизированного имени;
        // сегмент имени не содержит разделителей - traversal невозможен
        assertThat(relative).isEqualTo("5/7_.._.._вредное имя.csv");
        assertThat(storage.resolve(relative)).exists();
        assertThat(tmp).doesNotExist();
        // файлы лежат под корнем хранилища
        assertThat(storage.resolve(relative).toString())
                .startsWith(root.toAbsolutePath().toString());
    }

    @Test
    void resolve_outsideRoot_rejected() {
        AttachmentStorage storage = storage();
        // попытка выйти за корень (битые данные в БД) - отказ, не выход в ФС
        assertThatThrownBy(() -> storage.resolve("../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.resolve("5/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteProjectFiles_removesDirectoryTree() throws IOException {
        AttachmentStorage storage = storage();
        Path tmp = storage.saveTemp(new MockMultipartFile("file", "a.csv",
                "text/csv", new byte[]{1}));
        storage.promote(tmp, 9L, 1L, "a.csv");
        Path dir = root.resolve("9");
        assertThat(dir).exists();

        storage.deleteProjectFiles(9L);

        assertThat(dir).doesNotExist();
        // повторное удаление пустого каталога - безвредно
        storage.deleteProjectFiles(9L);
        // сам корень не удалён
        assertThat(root).exists();
    }

    @Test
    void maxBytes_fromConfig() {
        assertThat(storage().maxBytes()).isEqualTo(10_000_000L);
        assertThat(new AttachmentStorage(root.toString(), 42L).maxBytes()).isEqualTo(42L);
    }
}
