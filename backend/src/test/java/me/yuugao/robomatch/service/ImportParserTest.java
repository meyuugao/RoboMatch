package me.yuugao.robomatch.service;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit-тест ImportParser: регрессия на потолок
 * полей CSV (строка из миллионов разделителей
 * не должна раздуваться в миллионы String - memory amplification) и
 * базовые случаи разбора (разделитель по заголовку, кавычки, BOM).
 */
class ImportParserTest {

    private final ImportParser parser = new ImportParser();
    @TempDir
    Path dir;

    private Path csvFile(String content) throws IOException {
        Path file = Files.createTempFile(dir, "import", ".csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void delimiterFlood_cappedAtMaxColumns() throws IOException {
        // 2 млн разделителей в одной строке - до фикса это 2 млн String
        String flood = ";".repeat(2_000_000) + "\n";
        ImportParser.ParsedFile parsed =
                parser.parse(csvFile("a;b;c\n" + flood), "import.csv");

        assertThat(parsed.header()).containsExactly("a", "b", "c");
        assertThat(parsed.dataRows()).hasSize(1);
        // строка-флуд обрезана до потолка колонок, а не до миллионов полей
        assertThat(parsed.dataRows().get(0)).hasSizeLessThanOrEqualTo(301);
    }

    @Test
    void delimiterDetectedFromHeader_quotedAndBom() throws IOException {
        Path file = csvFile("\uFEFFcode_one;code_two\r\n"
                + "\"текст;с разделителем\";\"кавычка \"\"внутри\"\"\r\n");
        ImportParser.ParsedFile parsed = parser.parse(file, "import.csv");

        // BOM срезан, разделитель ';' выбран по заголовку (не запятая)
        assertThat(parsed.header()).containsExactly("code_one", "code_two");
        assertThat(parsed.dataRows().get(0)).containsExactly(
                "текст;с разделителем", "кавычка \"внутри\"");
    }

    @Test
    void commaDelimiterAndTrailingBlanks() throws IOException {
        Path file = csvFile("a,b,c,\n1,2,3,\n");
        ImportParser.ParsedFile parsed = parser.parse(file, "import.csv");

        // запятая чаще в заголовке - она и выбрана; хвостовые пустые
        // колонки срезаны у ЗАГОЛОВКА; в строке данных пустая ячейка
        // остаётся (сервис трактует её как «параметр не трогаем»)
        assertThat(parsed.header()).containsExactly("a", "b", "c");
        assertThat(parsed.dataRows().get(0)).containsExactly("1", "2", "3", "");
    }

    @Test
    void excelExtension_unreadableContent_failsWithIllegalState()
            throws IOException {
        // текстовый файл с расширением .xlsx - POI откажется читать;
        // сервис превращает IllegalStateException в 400 «не удалось прочитать»
        Path fake = dir.resolve("fake.xlsx");
        Files.writeString(fake, "это не Excel", StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> parser.parse(fake, "fake.xlsx"))
                .isInstanceOf(IllegalStateException.class);
    }
}
