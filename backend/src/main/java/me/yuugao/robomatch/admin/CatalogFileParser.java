package me.yuugao.robomatch.admin;

import me.yuugao.robomatch.service.ImportParser;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Разбор таблицы каталога организатора.
 * <p>
 * Формат — как у docs/source/catalog_export_v4.csv: 15 русских колонок,
 * разделитель «;», UTF-8 (BOM срезается), кавычки RFC 4180. Зерно
 * строки = решение x применение (одно решение в нескольких строках).
 * <p>
 * Расширенный формат (фикстуры docs/test-fixtures): к 15 колонкам
 * добавляются колонки ТТХ — по одной на код из справочника
 * characteristic_type (заголовок = код, например payload_kg, или с
 * префиксом «char:»). Провенанс характеристик задаётся колонками
 * source (URL), source_date (ISO-дата) и confirmation_status
 * («подтверждено»/«не подтверждено»).
 * <p>
 * Отличие от ImportParser (шаблоны параметров): ОПЕРАЦИОННЫЕ ДАННЫЕ
 * каталога содержат многострочные ячейки в кавычках (описания с \n) —
 * построчный BufferedReader режет их на куски, поэтому CSV здесь
 * читается конечным автоматом по всему файлу (как pandas в seed).
 * XLSX переиспользует ImportParser (POI, однозначный формат).
 * <p>
 * Маппинг колонок — порт CSV_COLUMNS из scripts/seed/loaders/csv_loader.py
 * (1:1, без выдумок). Отсутствие обязательной колонки — ошибка 400
 * со списком недостающих (как в seed). Неизвестный непустой заголовок
 * (не 15 колонок, не код ТТХ) — ошибка формата: опечатка в колонке
 * не должна молча терять данные.
 */
@Component
public class CatalogFileParser {

    /**
 * Русские заголовки CSV -> внутренние поля (порт csv_loader.CSV_COLUMNS).
 */
    static final Map<String, String> COLUMNS = buildColumns();

    /**
 * Префикс явной колонки ТТХ: «char:payload_kg».
 */
    static final String CHAR_PREFIX = "char:";
    private final ImportParser importParser;

    /**
 * Конструктор с зависимостью (Spring DI).
 *
 * @param importParser низкоуровневый разбор CSV/XLSX
 */
    public CatalogFileParser(ImportParser importParser) {
        this.importParser = importParser;
    }

    private static Map<String, String> buildColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "external_id");
        m.put("Название", "name");
        m.put("тип", "product_class");
        m.put("статус", "status");
        m.put("компания", "vendor");
        m.put("описание", "description");
        m.put("Тип", "solution_type");
        m.put("Подтип", "solution_subtype");
        m.put("Сценарий", "scenario");
        m.put("Кейсы", "case");
        m.put("УГТ", "trl_raw");
        m.put("Рын Потенциал", "market_potential_raw");
        m.put("Регион", "region");
        m.put("Отрасль", "industry");
        m.put("Цена изделия", "price_raw");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static char detectDelimiter(String content) {
        int firstLineEnd = content.indexOf('\n');
        String header = firstLineEnd < 0 ? content : content.substring(0, firstLineEnd);
        long semis = header.chars().filter(c -> c == ';').count();
        long commas = header.chars().filter(c -> c == ',').count();
        return semis >= commas ? ';' : ',';
    }

    /**
 * Код ТТХ из заголовка: «char:code» или сам код (латиница snake_case).
 */
    private static String charCodeOf(String header) {
        String lower = header.toLowerCase(java.util.Locale.ROOT);
        String candidate = lower.startsWith(CHAR_PREFIX)
                ? lower.substring(CHAR_PREFIX.length())
                : lower;
        return candidate.matches("^[a-z][a-z0-9_]*$") ? candidate : null;
    }

    /**
 * Для тестов: список внутренних имён полей в порядке конвенции.
 */
    static List<String> internalFields() {
        return Arrays.asList("external_id", "name", "product_class", "status", "vendor",
                "description", "solution_type", "solution_subtype", "scenario", "case",
                "trl_raw", "market_potential_raw", "region", "industry", "price_raw");
    }

    /**
 * Разбор файла по расширению: .csv -> автомат состояний, иначе XLSX/XLS.
 *
 * @param file сохранённая копия (data/admin-imports/{id}.{ext})
 * @param fileName имя от администратора (для выбора ветки парсера)
 * @param characteristicCodes коды ТТХ из characteristic_type (заголовки
 * расширенного формата), пустой набор — только
 * 15 базовых колонок
 * @return строки файла (сырые значения колонок)
 */
    public List<CatalogRawRow> parse(Path file, String fileName,
                                     Set<String> characteristicCodes) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        List<List<String>> rows = lower.endsWith(".csv") || lower.endsWith(".txt")
                ? parseCsvFile(file)
                : parseExcelFile(file);
        return mapRows(rows, characteristicCodes);
    }

    // ------------------------------------------------------------------
    // CSV: конечный автомат по всему файлу (многострочные кавычки)
    // ------------------------------------------------------------------

    /**
 * Совместимость со старым форматом: только 15 базовых колонок.
 *
 * @param file сохранённая копия файла
 * @param fileName имя от администратора (выбор ветки парсера)
 * @return строки файла (сырые значения колонок)
 */
    public List<CatalogRawRow> parse(Path file, String fileName) {
        return parse(file, fileName, Set.of());
    }

    private List<List<String>> parseCsvFile(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new CatalogFormatException("Не удалось прочитать CSV-файл: " + ex.getMessage());
        }
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        if (content.isBlank()) {
            throw new CatalogFormatException("Файл пустой");
        }
        char delimiter = detectDelimiter(content);
        List<List<String>> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean rowStarted = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
                rowStarted = true;
            } else if (ch == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
                rowStarted = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(current.toString());
                current.setLength(0);
                // строка из одного перевода строки (после quoted-блока
                // с \n) не становится пустой записью
                if (rowStarted || fields.size() > 1 || !fields.get(0).isBlank()) {
                    rows.add(fields);
                    fields = new ArrayList<>();
                } else {
                    fields = new ArrayList<>();
                }
                rowStarted = false;
            } else {
                current.append(ch);
                rowStarted = true;
            }
        }
        // последний ряд без перевода строки в конце
        if (rowStarted || current.length() > 0 || !fields.isEmpty()) {
            fields.add(current.toString());
            rows.add(fields);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // XLSX/XLS: переиспользование POI-парсера параметров
    // ------------------------------------------------------------------

    private List<List<String>> parseExcelFile(Path file) {
        ImportParser.ParsedFile parsed = importParser.parse(file, "book.xlsx");
        List<List<String>> rows = new ArrayList<>();
        rows.add(parsed.header());
        rows.addAll(parsed.dataRows());
        return rows;
    }

    // ------------------------------------------------------------------
    // Маппинг заголовков -> внутренние поля + колонки ТТХ
    // ------------------------------------------------------------------

    private List<CatalogRawRow> mapRows(List<List<String>> rows,
                                        Set<String> characteristicCodes) {
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            throw new CatalogFormatException("В файле нет строки заголовка");
        }
        List<String> header = rows.get(0);
        // индекс колонки по внутреннему имени поля (null — колонки нет)
        Map<String, Integer> index = new HashMap<>();
        // индекс колонки ТТХ по коду characteristic_type
        Map<String, Integer> charIndex = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        for (int col = 0; col < header.size(); col++) {
            String raw = AdminTextUtil.normText(header.get(col));
            if (raw == null) {
                continue; // пустой заголовок (хвостовые «;») — игнор
            }
            String internal = COLUMNS.get(raw);
            if (internal == null) {
                // расширенный формат: код ТТХ как заголовок (прямо или с
                // префиксом «char:») — распознаётся по справочнику из БД
                String code = charCodeOf(raw);
                if (code != null && characteristicCodes.contains(code)) {
                    charIndex.putIfAbsent(code, col);
                } else {
                    unknown.add(raw);
                }
                continue;
            }
            if (!index.containsKey(internal)) {
                index.put(internal, col);
            }
        }
        if (!unknown.isEmpty()) {
            throw new CatalogFormatException(
                    "Неизвестные колонки файла: " + unknown
                            + "; допустимы 15 колонок таблицы организатора и коды "
                            + "ТТХ (characteristic_type) как заголовки, при желании "
                            + "с префиксом «char:»");
        }
        List<String> missing = COLUMNS.values().stream()
                .filter(f -> !index.containsKey(f))
                .toList();
        if (!missing.isEmpty()) {
            throw new CatalogFormatException(
                    "В файле отсутствуют колонки: " + missing
                            + "; фактические заголовки: " + header);
        }
        List<CatalogRawRow> result = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            if (cells.stream().allMatch(String::isBlank)) {
                continue; // хвостовые пустые строки
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : index.entrySet()) {
                int col = e.getValue();
                fields.put(e.getKey(), col < cells.size() ? cells.get(col) : "");
            }
            Map<String, String> chars = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : charIndex.entrySet()) {
                int col = e.getValue();
                String cell = col < cells.size() ? cells.get(col) : "";
                if (AdminTextUtil.normText(cell) != null) {
                    chars.put(e.getKey(), cell);
                }
            }
            result.add(new CatalogRawRow(r + 1, fields, chars));
        }
        return result;
    }

    /**
 * Одна строка файла: внутренние поля + значения ТТХ (код → ячейка).
 *
 * @param rowNumber номер строки в файле (для ошибок валидации)
 * @param fields базовые колонки (внутренние имена)
 * @param characteristics сырые ячейки ТТХ (код → значение)
 */
    public record CatalogRawRow(int rowNumber, Map<String, String> fields,
                                Map<String, String> characteristics) {
        /**
 * Значение базовой колонки.
 *
 * @param field внутреннее имя колонки
 * @return сырая ячейка или null
 */
        public String get(String field) {
            return fields.get(field);
        }

        /**
 * Сырая ячейка ТТХ (null — колонки нет в файле).
 *
 * @param code код ТТХ (characteristic_type.code)
 * @return сырая ячейка или null
 */
        public String charValue(String code) {
            return characteristics.get(code);
        }
    }

    /**
 * Ошибка формата файла: отсутствуют колонки.
 */
    public static class CatalogFormatException extends RuntimeException {
        /**
 * Создаёт ошибку формата каталога.
 *
 * @param message что именно не так с колонками
 */
        public CatalogFormatException(String message) {
            super(message);
        }
    }
}
