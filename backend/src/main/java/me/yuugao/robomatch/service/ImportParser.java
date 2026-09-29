package me.yuugao.robomatch.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Разбор файла импорта параметров в сырую матрицу строк:
 * заголовок (коды параметров) + строки данных. Слепой к бизнес-правилам:
 * валидация значений — в ProjectParameterService (одна точка правды
 * для ручного ввода и импорта).
 * <p>
 * Формат («шаблон wide»): строка 1 — коды параметров
 * (parameter_type.code), строка 2+ — значения; импорт берёт первую
 * строку данных, лишние — предупреждения сервиса.
 * <p>
 * Решения парсера:
 * - CSV: BOM срезается (Excel кладёт UTF-8 BOM), разделитель ';' или
 * ',' определяется по строке заголовка (русская локаль Excel
 * экспортирует ';', наш шаблон — ';'), кавычки RFC 4180 («"» внутри — «""»);
 * - XLSX/XLS: Apache POI WorkbookFactory (оба формата), только первый
 * лист; числовые ячейки — без хвостового «.0», даты не допускаются
 * (параметры объектов не датированы — ячейка-дата станет ошибкой
 * валидации значения);
 * - формат файла определяется по MIME (белый список — в сервисе), здесь —
 * по расширению: CSV читается как текст, остальное открывается через
 * POI WorkbookFactory (распознаёт xlsx и xls по содержимому).
 */
@Component
public class ImportParser {

    /**
 * Потолок колонок/строк: защита от гигантских файлов (лимит 10 МБ и так режет объём).
 */
    private static final int MAX_COLUMNS = 300;
    private static final int MAX_ROWS = 1000;

    /**
 * Разделитель ';' или ',' — какой чаще встречается в заголовке.
 */
    private static char detectDelimiter(String headerLine) {
        int semicolons = count(headerLine, ';');
        int commas = count(headerLine, ',');
        return semicolons >= commas ? ';' : ',';
    }

    private static int count(String line, char ch) {
        return (int) line.chars().filter(c -> c == ch).count();
    }

    // ------------------------------------------------------------------
    // CSV
    // ------------------------------------------------------------------

    /**
 * Сплит строки CSV с кавычками RFC 4180; BOM срезается на первом поле.
 * Потолок полей — MAX_COLUMNS: строка из одних разделителей не может
 * раздуться в миллионы String до верхней обрезки (memory-amplification).
 */
    private static List<String> splitCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == delimiter && !inQuotes) {
                if (fields.size() >= MAX_COLUMNS) {
                    break; // хвост сверх потолка колонок игнорируется
                }
                fields.add(current.toString());
                current.setLength(0);
            } else {
                if (fields.size() < MAX_COLUMNS || inQuotes) {
                    current.append(ch);
                }
            }
        }
        fields.add(current.toString());
        if (!fields.isEmpty()) {
            fields.set(0, stripBom(fields.get(0)));
        }
        return fields;
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    /**
 * Ячейки строки в текстовом виде (числа — без «.0», пустые — «»).
 */
    private static List<String> excelRowToStrings(Row row) {
        short last = row.getLastCellNum();
        List<String> cells = new ArrayList<>(Math.max(last, 0));
        for (int i = 0; i < last && i < MAX_COLUMNS; i++) {
            cells.add(cellToString(row.getCell(i)));
        }
        return cells;
    }

    private static String cellToString(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    // даты в параметрах объектов не предусмотрены —
                    // отдаём отображаемое значение, валидация отклонит
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double value = cell.getNumericCellValue();
                if (value == Math.rint(value) && !Double.isInfinite(value)) {
                    yield String.valueOf((long) value);
                }
                yield trimDouble(value);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /**
 * Число double без хвостовых нулей и без экспоненты (бытовые величины).
 */
    private static String trimDouble(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.6f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    // ------------------------------------------------------------------
    // Excel (xlsx + xls через WorkbookFactory)
    // ------------------------------------------------------------------

    /**
 * Первая строка — заголовок, остальные — данные; хвостовые пустые колонки срезаются.
 */
    private static ParsedFile toParsedFile(List<List<String>> rows) {
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            throw new IllegalStateException("В файле нет строки заголовка");
        }
        List<String> header = trimTrailingBlanks(rows.get(0));
        if (header.isEmpty()) {
            throw new IllegalStateException("Строка заголовка пустая");
        }
        List<List<String>> dataRows = new ArrayList<>(rows.subList(1, rows.size()));
        return new ParsedFile(header.subList(0, Math.min(header.size(), MAX_COLUMNS)),
                dataRows);
    }

    /**
 * Убирает пустые ячейки с конца строки (хвостовые «;» и лишние колонки).
 */
    private static List<String> trimTrailingBlanks(List<String> cells) {
        int last = cells.size();
        while (last > 0 && cells.get(last - 1).isBlank()) {
            last--;
        }
        return new ArrayList<>(cells.subList(0, last));
    }

    /**
 * Разбор по фактическому формату файла (MIME — уже проверен белым списком).
 *
 * @param file временный файл на диске (из AttachmentStorage.saveTemp)
 * @param fileName исходное имя файла пользователя (расширение решает парсер)
 * @return заголовок и строки данных
 */
    public ParsedFile parse(Path file, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
            return parseCsv(file);
        }
        return parseExcel(file);
    }

    private ParsedFile parseCsv(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null) {
                throw new IllegalStateException("Файл пустой");
            }
            char delimiter = detectDelimiter(firstLine);
            List<List<String>> rows = new ArrayList<>();
            rows.add(splitCsvLine(firstLine, delimiter));
            String line;
            while ((line = reader.readLine()) != null && rows.size() < MAX_ROWS) {
                rows.add(splitCsvLine(line, delimiter));
            }
            return toParsedFile(rows);
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось прочитать CSV-файл", ex);
        }
    }

    // ------------------------------------------------------------------
    // Общее
    // ------------------------------------------------------------------

    private ParsedFile parseExcel(Path file) {
        try (InputStream in = Files.newInputStream(file); Workbook workbook =
                WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalStateException("В файле нет листов");
            }
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                rows.add(excelRowToStrings(row));
            }
            return toParsedFile(rows);
        } catch (IOException | RuntimeException ex) {
            // буквы не читаются как Excel — честная ошибка «не тот формат»
            throw new IllegalStateException("Не удалось прочитать Excel-файл", ex);
        }
    }

    /**
 * Результат разбора: заголовок (коды, порядок как в файле) и строки
 * данных (сырые строки; пустая строка — пустая ячейка).
 *
 * @param header коды параметров в порядке файла
 * @param dataRows строки данных — сырые значения ячеек
 */
    public record ParsedFile(List<String> header, List<List<String>> dataRows) {
    }
}
