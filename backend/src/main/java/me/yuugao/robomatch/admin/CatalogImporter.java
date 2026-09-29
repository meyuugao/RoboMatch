package me.yuugao.robomatch.admin;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.domain.Process;
import me.yuugao.robomatch.dto.CatalogImportSummaryDto;
import me.yuugao.robomatch.dto.ImportErrorDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.ImportException;
import me.yuugao.robomatch.exception.PayloadTooLargeException;
import me.yuugao.robomatch.repository.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Импорт таблицы каталога организатора.
 * <p>
 * ПОРТ ПРАВИЛ scripts/seed/mappers/catalog_mapper.py + repositories/
 * catalog_repo.py: семантика обязана совпадать с первичным seed, иначе
 * повторный импорт через админку даст другие данные:
 * - дедупликация по external_id (§5.1): зерно строки CSV = решение x
 * применение, зерно solution = external_id; конфликты полей внутри
 * группы - первое значение + предупреждение;
 * - описание - самое длинное непустое; цена - минимальная (§5.5);
 * - карта опечаток подтипов (assumptions.md §10/§13);
 * - разрез «Сценарий» по «, » с картой исключений (§12) и картой
 * опечаток процессов (§10);
 * - применения - по уникальной тройке (solution, industry, process),
 * конфликт цен -> минимальная;
 * - кейсы - уникальные тексты + связи (external_id, кейс);
 * - upsert: solution по external_id (фолбэк по (vendor, name) - файл,
 * воссозданный с новыми UUID, не плодит дубли), справочники - по
 * каноническому имени (новые записи получают транслит-код: конвенция
 * assumptions.md §20 уже в БД от seed, транслит - фолбэк slugify).
 * <p>
 * ОТЛИЧИЕ ОТ SEED (осознанное, architecture.md §11): повторный импорт
 * НЕ обнуляет 9 зеркальных колонок ТТХ и completeness_pct - файл
 * организатора этих колонок не содержит, а значения в них могли быть
 * дозаполнены из открытых источников; seed мог позволить
 * себе обнуление, потому что за ним всегда шёл шаг обогащения.
 * <p>
 * ИДЕМПОТЕНТНОСТЬ: повторный импорт того же файла - 0 добавлений и
 * обновлений (всё skipped). In-flight импорт (status='running') -> 409;
 * зависший после рестарта 'running' старше 15 минут помечается failed.
 * <p>
 * РАСШИРЕННЫЙ ФОРМАТ (docs/test-fixtures/README.md): файл может нести
 * колонки ТТХ - по одной на код characteristic_type (заголовок = код,
 * например payload_kg, или с префиксом «char:»). Значения попадают в
 * EAV solution_characteristic с провенансом organizer_catalog;
 * 9 зеркальных колонок solution синхронизируются (§5.8). Провенанс
 * всех ТТХ решения - из колонок того же файла: source (URL),
 * source_date (ISO-дата), confirmation_status («подтверждено» ->
 * is_confirmed=true, иначе false - assumptions.md §7 «при импорте
 * не подтверждено»). Пустая ячейка ТТХ не создаёт строку и НЕ
 * затирает существующее значение. Тип значения обязан совпадать с
 * data_type (number/text/boolean/date) - иначе ошибка строки 400.
 * <p>
 * История: каждая загрузка - строка admin_import_log (V7); копия файла
 * в data/admin-imports/{importId}.{ext} - источник для refresh.
 */
@Service
public class CatalogImporter {

    /**
 * Карта опечаток подтипов - assumptions.md §10/§13 (порт catalog_mapper).
 */
    static final Map<String, String> SUBTYPE_TYPO_FIXES = Map.of(
            "Робот уборщик", "Робот-уборщик",
            "Робот инвентаризатор", "Робот-инвентаризатор",
            "Модульная наводная многофункциональная платформа",
            "Модульная надводная многофункциональная платформа",
            "Роботизированный 3D принтер", "Роботизированный 3D-принтер");
    /**
 * Опечатки в процессах - assumptions.md §10 (порт processes.py).
 */
    static final Map<String, String> SCENARIO_TYPO_FIXES = Map.of(
            "Доставка биоматериаловм", "Доставка биоматериалов");
    /**
 * Значения, где «, » - внутри названия одного процесса (§12).
 */
    static final Set<String> SCENARIO_EXCEPTIONS = Set.of(
            "Доставка в удаленные, труднодоступные районы",
            "Мониторинг, патрулирование, перевозка грузов",
            "Выполнение контрольных мероприятий на этапе строительно-монтажных работ "
                    + "с использованием фотограмметрии, воздушного лазерного сканирования "
                    + "и тепловизионной съемки");
    /**
 * Источник данных организатора (config.py seed: SOURCE_KIND/URL).
 */
    static final String SOURCE_KIND = "organizer_catalog";
    static final String SOURCE_URL = "catalog_export_v4.csv";
    private static final Logger log = LoggerFactory.getLogger(CatalogImporter.class);
    private static final Set<String> PRODUCT_CLASSES = Set.of("brs", "bas", "software");
    private static final Set<String> STATUSES = Set.of("operation", "piloting", "rnd");
    /**
 * Зависший 'running' старше этого порога помечается failed (перезапуск).
 */
    private static final Duration STALE_RUNNING = Duration.ofMinutes(15);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "xlsx", "xls");

    /**
 * Разделитель составных ключей в памяти (не встречается в данных).
 */
    private static final char KEY_SEP = '\u001f';

    private final CatalogFileParser parser;
    private final AdminImportRepository importRepository;
    private final VendorRepository vendorRepository;
    private final IndustryRepository industryRepository;
    private final ProcessRepository processRepository;
    private final RegionRepository regionRepository;
    private final SolutionTypeRepository solutionTypeRepository;
    private final SolutionSubtypeRepository solutionSubtypeRepository;
    private final SolutionRepository solutionRepository;
    private final SolutionApplicationRepository applicationRepository;
    private final SolutionCaseRepository caseRepository;
    private final SolutionCaseLinkRepository caseLinkRepository;
    private final CharacteristicTypeRepository characteristicTypeRepository;
    private final SolutionCharacteristicRepository characteristicRepository;
    private final TransactionTemplate transactionTemplate;

    private final Path root;
    private final long maxBytes;

    /**
 * Конструктор с зависимостями и настройками хранилища (Spring DI).
 *
 * @param parser разбор файла каталога в сырые строки
 * @param importRepository журнал импортов
 * @param vendorRepository производителей
 * @param industryRepository отраслей
 * @param processRepository процессов
 * @param regionRepository регионов
 * @param solutionTypeRepository типов решений
 * @param solutionSubtypeRepository подтипов решений
 * @param solutionRepository решений каталога
 * @param applicationRepository применений решений
 * @param caseRepository кейсов
 * @param caseLinkRepository связей кейсов с решениями
 * @param characteristicTypeRepository типов ТТХ
 * @param characteristicRepository значений ТТХ (EAV)
 * @param transactionTemplate шаблон транзакций (одна транзакция импорта)
 * @param root корень хранилища копий (app.admin-import.root)
 * @param maxBytes лимит файла (app.admin-import.max-bytes)
 */
    public CatalogImporter(CatalogFileParser parser,
                           AdminImportRepository importRepository,
                           VendorRepository vendorRepository,
                           IndustryRepository industryRepository,
                           ProcessRepository processRepository,
                           RegionRepository regionRepository,
                           SolutionTypeRepository solutionTypeRepository,
                           SolutionSubtypeRepository solutionSubtypeRepository,
                           SolutionRepository solutionRepository,
                           SolutionApplicationRepository applicationRepository,
                           SolutionCaseRepository caseRepository,
                           SolutionCaseLinkRepository caseLinkRepository,
                           CharacteristicTypeRepository characteristicTypeRepository,
                           SolutionCharacteristicRepository characteristicRepository,
                           TransactionTemplate transactionTemplate,
                           @Value("${app.admin-import.root:./data/admin-imports}") String root,
                           @Value("${app.admin-import.max-bytes:52428800}") long maxBytes) {
        this.parser = parser;
        this.importRepository = importRepository;
        this.vendorRepository = vendorRepository;
        this.industryRepository = industryRepository;
        this.processRepository = processRepository;
        this.regionRepository = regionRepository;
        this.solutionTypeRepository = solutionTypeRepository;
        this.solutionSubtypeRepository = solutionSubtypeRepository;
        this.solutionRepository = solutionRepository;
        this.applicationRepository = applicationRepository;
        this.caseRepository = caseRepository;
        this.caseLinkRepository = caseLinkRepository;
        this.characteristicTypeRepository = characteristicTypeRepository;
        this.characteristicRepository = characteristicRepository;
        this.transactionTemplate = transactionTemplate;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    // ==================================================================
    // Публичный API
    // ==================================================================

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
 * ТТХ решения из колонок расширенного формата: первое непустое значение
 * на код в группе дублей (конфликт - предупреждение), типобезопасный
 * разбор по data_type. Провенанс - колонки source/source_date/
 * confirmation_status той же группы.
 */
    private static void mapCharacteristics(MergedSolution s,
                                           List<CatalogFileParser.CatalogRawRow> group,
                                           Map<String, CharacteristicType> typesByCode,
                                           MappedCatalog data,
                                           Errors errors) {
        // коды колонок ТТХ, встретившиеся в группе (порядок строк файла)
        Map<String, String> firstRaw = new LinkedHashMap<>();
        Map<String, Integer> firstRow = new HashMap<>();
        Map<String, List<String>> conflicts = new LinkedHashMap<>();
        for (CatalogFileParser.CatalogRawRow r : group) {
            for (Map.Entry<String, String> e : r.characteristics().entrySet()) {
                String prev = firstRaw.putIfAbsent(e.getKey(), e.getValue());
                if (prev != null && !prev.equals(e.getValue())) {
                    conflicts.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                            .add(e.getValue());
                }
                firstRow.putIfAbsent(e.getKey(), r.rowNumber());
            }
        }
        for (Map.Entry<String, List<String>> c : conflicts.entrySet()) {
            data.warnings.add("КОНФЛИКТ поля ТТХ «" + c.getKey()
                    + "» в группе дублей " + s.externalId + ": [" + firstRaw.get(c.getKey())
                    + ", " + String.join(", ", c.getValue()) + "] -> взято первое");
        }
        for (Map.Entry<String, String> e : firstRaw.entrySet()) {
            String code = e.getKey();
            CharacteristicType type = typesByCode.get(code);
            if (type == null) {
                // невозможно после валидации заголовков парсером - защита
                errors.add(firstRow.get(code), code,
                        "неизвестный код ТТХ «" + code + "» (нет в characteristic_type)");
                continue;
            }
            String raw = AdminTextUtil.normText(e.getValue());
            if (raw == null) {
                continue; // пустая ячейка - не создаёт значение
            }
            switch (type.getDataType()) {
                case "number" -> {
                    BigDecimal num = parseDecimal(raw);
                    if (num == null) {
                        errors.add(firstRow.get(code), code,
                                "ТТХ «" + code + "» = «" + raw + "» не является числом "
                                        + "(у решения " + s.externalId + ")");
                    } else {
                        s.characteristics.add(new MergedCharacteristic(code, num,
                                null, null, null));
                    }
                }
                case "text" -> s.characteristics.add(new MergedCharacteristic(code,
                        null, raw, null, null));
                case "boolean" -> {
                    Boolean bool = parseBoolean(raw);
                    if (bool == null) {
                        errors.add(firstRow.get(code), code,
                                "ТТХ «" + code + "» = «" + raw + "» не да/нет (true/false, "
                                        + "у решения " + s.externalId + ")");
                    } else {
                        s.characteristics.add(new MergedCharacteristic(code, null,
                                null, bool, null));
                    }
                }
                case "date" -> {
                    LocalDate date = parseIsoDate(raw);
                    if (date == null) {
                        errors.add(firstRow.get(code), code,
                                "ТТХ «" + code + "» = «" + raw + "» не дата ISO (ГГГГ-ММ-ДД, "
                                        + "у решения " + s.externalId + ")");
                    } else {
                        s.characteristics.add(new MergedCharacteristic(code, null,
                                null, null, date));
                    }
                }
                default -> errors.add(firstRow.get(code), code,
                        "у типа «" + code + "» неизвестный data_type "
                                + type.getDataType());
            }
        }
        // провенанс всех ТТХ решения - из data_quality-колонок той же группы
        for (MergedCharacteristic c : s.characteristics) {
            if ("source".equals(c.code()) && c.text() != null) {
                s.charSourceUrl = c.text();
            } else if ("source_date".equals(c.code()) && c.date() != null) {
                s.charSourceDate = c.date();
            } else if ("confirmation_status".equals(c.code()) && c.text() != null) {
                s.charConfirmed = AdminTextUtil.canonicalKey(c.text())
                        .equals("подтверждено");
            }
        }
    }

    // ==================================================================
    // Жизненный цикл лога и файла
    // ==================================================================

    /**
 * да/нет (true/false, 1/0) -> Boolean; иначе null.
 */
    static Boolean parseBoolean(String value) {
        String key = AdminTextUtil.canonicalKey(value);
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "да", "true", "1" -> Boolean.TRUE;
            case "нет", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
 * Дата ISO ГГГГ-ММ-ДД; иначе null.
 */
    static LocalDate parseIsoDate(String value) {
        try {
            return LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            return null;
        }
    }

    /**
 * Первое непустое значение поля в группе; расхождения - предупреждение.
 */
    private static String mergeField(MappedCatalog data, String ext, String csvColumn,
                                     String internalField,
                                     List<CatalogFileParser.CatalogRawRow> group) {
        List<String> values = new ArrayList<>();
        for (CatalogFileParser.CatalogRawRow r : group) {
            String v = AdminTextUtil.normText(r.get(internalField));
            if (v != null && !values.contains(v)) {
                values.add(v);
            }
        }
        if (values.size() > 1) {
            data.warnings.add("КОНФЛИКТ поля «" + csvColumn + "» в группе дублей " + ext
                    + ": " + values + " -> взято первое");
        }
        return values.isEmpty() ? null : values.get(0);
    }

    /**
 * Автоправка подтипа (assumptions.md §10/§13).
 */
    private static String fixSubtype(String value, MappedCatalog data) {
        if (value == null) {
            return null;
        }
        String fixed = SUBTYPE_TYPO_FIXES.get(value);
        if (fixed != null) {
            data.warnings.add("подтип объединён: «"
                    + value + "» -> «" + fixed + "»");
            return fixed;
        }
        return value;
    }

    // ==================================================================
    // Основной конвейер
    // ==================================================================

    /**
 * Разрез «Сценарий» по «, » с картами исключений/опечаток (§10/§12).
 */
    static List<String> splitScenarios(String raw, MappedCatalog data) {
        String s = AdminTextUtil.normText(raw);
        if (s == null) {
            return List.of();
        }
        String fixed = SCENARIO_TYPO_FIXES.get(s);
        if (fixed != null) {
            data.warnings.add("опечатка исправлена: «"
                    + s + "» -> «" + fixed + "»");
            s = fixed;
        }
        if (SCENARIO_EXCEPTIONS.contains(s)) {
            return List.of(s);
        }
        List<String> result = new ArrayList<>();
        for (String part : s.split(", ")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
 * Дедупликация имён по canonical_key; хранится первое написание (порт).
 */
    private static List<String> uniqueNames(List<String> warnings,
                                            java.util.stream.Stream<String> names) {
        Map<String, String> seen = new LinkedHashMap<>();
        names.forEach(name -> {
            String key = AdminTextUtil.canonicalKey(name);
            String first = seen.get(key);
            if (first == null) {
                seen.put(key, name);
            } else if (!first.equals(name)) {
                // паритет с _unique_names - вариант
                // написания того же имени схлопнут, это видно в warnings
                warnings.add("объединены варианты написания: «" + first + "» + «"
                        + name + "» (канонический ключ «" + key + "»)");
            }
        });
        // похожие названия (дефис/пробел) НЕ объединяем - фиксируем (§10)
        Map<String, List<String>> folded = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : seen.entrySet()) {
            folded.computeIfAbsent(e.getKey().replace("-", " "),
                    k -> new ArrayList<>()).add(e.getValue());
        }
        for (List<String> group : folded.values()) {
            if (group.size() > 1) {
                warnings.add("ПОХОЖИЕ НАЗВАНИЯ (дефис/пробел), НЕ объединены: "
                        + String.join(" / ", group) + " - проверить у организатора");
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
 * parse_decimal из numbers.py: '-', '', null -> null.
 */
    static BigDecimal parseDecimal(String value) {
        String s = AdminTextUtil.normText(value);
        if (s == null || s.equals("-")) {
            return null;
        }
        s = s.replace(" ", "").replace(",", ".");
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ==================================================================
    // Маппинг: порт catalog_mapper.map_catalog
    // ==================================================================

    /**
 * parse_int с диапазоном: нецелое/вне диапазона -> null (ошибка выше).
 */
    static Short parseIntInRange(String value, int lo, int hi) {
        BigDecimal d = parseDecimal(value);
        if (d == null) {
            return null;
        }
        try {
            long exact = d.longValueExact();
            if (exact < lo || exact > hi) {
                return null;
            }
            return (short) exact;
        } catch (ArithmeticException ex) {
            return null; // нецелое
        }
    }

    private static String key(String... parts) {
        return String.join(String.valueOf(KEY_SEP), parts);
    }

    private static String[] splitKey(String composite, int expected) {
        String[] parts = composite.split(String.valueOf(KEY_SEP), expected);
        if (parts.length != expected) {
            throw new IllegalStateException("Битый составной ключ импорта");
        }
        return parts;
    }

    /**
 * Транслит-код для новой записи; коллизия слага - суффикс _2, _3…
 */
    private static String nextCode(String name, DictSnapshots dicts) {
        String base = AdminTextUtil.slugify(name);
        if (base == null || base.isEmpty()) {
            base = "x";
        }
        String code = base;
        int n = 2;
        while (dicts.usedCodes.contains(code)) {
            code = base + "_" + n++;
        }
        dicts.usedCodes.add(code);
        return code;
    }

    /**
 * Зеркальные ТТХ-колонки solution из числовых характеристик файла.
 */
    private static void applyMirrorColumns(Solution solution,
                                           List<MergedCharacteristic> characteristics) {
        for (MergedCharacteristic c : characteristics) {
            if (c.numeric() == null) {
                continue;
            }
            var setter = AdminCharacteristicService.MIRROR_SETTERS.get(c.code());
            if (setter != null) {
                setter.accept(solution, c.numeric());
            }
        }
    }

    /**
 * Текущее значение зеркальной ТТХ-колонки решения по коду.
 */
    private static BigDecimal mirrorValueOf(Solution solution, String code) {
        return switch (code) {
            case "payload_kg" -> solution.getPayloadKg();
            case "mass_kg" -> solution.getMassKg();
            case "length_mm" -> solution.getLengthMm();
            case "width_mm" -> solution.getWidthMm();
            case "height_mm" -> solution.getHeightMm();
            case "positioning_accuracy_mm" -> solution.getPositioningAccuracyMm();
            case "speed_m_s" -> solution.getSpeedMs();
            case "charging_power_kw" -> solution.getChargingPowerKw();
            case "noise_level_dba" -> solution.getNoiseLevelDba();
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // ТТХ расширенного формата: разбор + провенанс
    // ------------------------------------------------------------------

    /**
 * Значение EAV-строки равно значению из файла (без учёта scale чисел).
 */
    private static boolean valueEquals(SolutionCharacteristic row, MergedCharacteristic c) {
        if (c.numeric() != null) {
            return row.getValueNumeric() != null
                    && row.getValueNumeric().compareTo(c.numeric()) == 0;
        }
        if (c.text() != null) {
            return Objects.equals(row.getValueText(), c.text());
        }
        if (c.bool() != null) {
            return Objects.equals(row.getValueBool(), c.bool());
        }
        return Objects.equals(row.getValueDate(), c.date());
    }

    private static CatalogImportSummaryDto.EntityCountersDto counters(
            CatalogImportSummaryDto summary, String entity) {
        return summary.getEntities().computeIfAbsent(entity,
                k -> new CatalogImportSummaryDto.EntityCountersDto());
    }

    /**
 * Сравнение numeric без учёта scale (4 == 4.0): СУБД возвращает
 * прочитанное из numeric(3,1) со scale 1, а парсер даёт scale 0 -
 * Objects.equals посчитал бы равные значения изменением (ловушка
 * идемпотентности, выявлена тестом repeatedImportIsIdempotent).
 */
    private static boolean decimalChanged(BigDecimal existing, BigDecimal incoming) {
        if (existing == null && incoming == null) {
            return false;
        }
        if (existing == null || incoming == null) {
            return true;
        }
        return existing.compareTo(incoming) != 0;
    }

    /**
 * Загрузка и импорт нового файла (POST /api/admin/catalog/import).
 *
 * @param file CSV/XLSX каталога (multipart)
 * @param userId администратор, запустивший импорт
 * @return счётчики импорта по сущностям
 */
    public CatalogImportSummaryDto importFile(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Файл не передан или пустой");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BadRequestException(
                    "Допустимы только файлы CSV или XLSX (получено: ." + extension + ")");
        }
        if (file.getSize() > maxBytes) {
            throw new PayloadTooLargeException(
                    "Файл больше " + (maxBytes / 1024 / 1024) + " МБ");
        }
        checkNoInFlight();
        AdminImportLog logRow = startLog(file.getOriginalFilename(), file.getSize(), userId);
        Path saved;
        try {
            saved = saveUploaded(file, logRow, extension);
        } catch (RuntimeException ex) {
            failLog(logRow); // не оставляем running-блокировку
            throw ex;
        }
        return runImport(logRow, saved, file.getOriginalFilename());
    }

    /**
 * Повторный импорт последнего загруженного файла (,
 * POST /api/admin/catalog/refresh).
 *
 * @param userId администратор, запустивший повтор
 * @return счётчики импорта по сущностям
 */
    public CatalogImportSummaryDto refreshLast(Long userId) {
        AdminImportLog last = importRepository.findFirstByOrderByStartedAtDesc()
                .orElseThrow(() -> new BadRequestException(
                        "Ранее загруженных файлов каталога нет - сначала загрузите файл"));
        Path path = Path.of(last.getFilePath());
        if (!Files.isReadable(path)) {
            throw new BadRequestException(
                    "Сохранённая копия файла больше не доступна: " + last.getFileName());
        }
        checkNoInFlight();
        AdminImportLog logRow = startLog(last.getFileName(), last.getSizeBytes(), userId);
        Path saved;
        try {
            saved = copyForRefresh(path, logRow, extensionOf(last.getFileName()));
        } catch (RuntimeException ex) {
            failLog(logRow);
            throw ex;
        }
        return runImport(logRow, saved, last.getFileName());
    }

    /**
 * In-flight импорт -> 409; зависший running старше порога - failed.
 */
    private void checkNoInFlight() {
        importRepository.findFirstByStatusOrderByStartedAtDesc("running").ifPresent(running -> {
            if (Duration.between(running.getStartedAt(), Instant.now()).compareTo(STALE_RUNNING)
                    < 0) {
                throw new ConflictException(
                        "Импорт каталога уже выполняется - дождитесь завершения");
            }
            log.warn("Помечаем зависший импорт #{} (running c {}) как failed",
                    running.getId(), running.getStartedAt());
            running.setStatus("failed");
            running.setFinishedAt(Instant.now());
            importRepository.save(running);
        });
    }

    /**
 * Строка истории: фиксируется отдельно - история переживает откат.
 */
    private AdminImportLog startLog(String fileName, long sizeBytes, Long userId) {
        AdminImportLog row = AdminImportLog.builder()
                .fileName(fileName)
                .filePath("pending") // уточним после генерации id
                .sizeBytes(sizeBytes)
                .startedAt(Instant.now())
                .status("running")
                .createdByUserId(userId)
                .build();
        return importRepository.save(row);
    }

    /**
 * Копия файла: {root}/{importId}.{ext}; имя администратора НЕ в пути.
 */
    private Path saveUploaded(MultipartFile file, AdminImportLog logRow, String extension) {
        try {
            Files.createDirectories(root);
            Path target = root.resolve(logRow.getId() + "." + extension);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            logRow.setFilePath(target.toString());
            importRepository.save(logRow);
            return target;
        } catch (IOException ex) {
            throw new UncheckedIOException("Не удалось сохранить файл импорта", ex);
        }
    }

    private Path copyForRefresh(Path source, AdminImportLog logRow, String extension) {
        try {
            Files.createDirectories(root);
            Path target = root.resolve(logRow.getId() + "." + extension);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            logRow.setFilePath(target.toString());
            importRepository.save(logRow);
            return target;
        } catch (IOException ex) {
            throw new UncheckedIOException("Не удалось подготовить файл обновления", ex);
        }
    }

    private CatalogImportSummaryDto runImport(AdminImportLog logRow, Path file, String fileName) {
        Instant startedAt = logRow.getStartedAt();
        try {
            // коды ТТХ из БД: заголовки расширенного формата валидируются
            // по фактическому справочнику characteristic_type
            Map<String, CharacteristicType> typesByCode = loadCharacteristicTypes();
            List<CatalogFileParser.CatalogRawRow> rows = parser.parse(file, fileName,
                    typesByCode.keySet());
            MappedCatalog mapped = mapCatalog(rows, typesByCode);
            CatalogImportSummaryDto summary = transactionTemplate.execute(tx ->
                    applyMapped(mapped));
            summary.setFileName(fileName);
            summary.setImportId(logRow.getId());
            summary.setStartedAt(startedAt);
            summary.setFinishedAt(Instant.now());
            logRow.setStatus("completed");
            logRow.setFinishedAt(summary.getFinishedAt());
            logRow.setSummaryJson(CatalogJson.toJson(summary));
            importRepository.save(logRow);
            return summary;
        } catch (ImportException ex) {
            failLog(logRow);
            throw ex;
        } catch (CatalogFileParser.CatalogFormatException ex) {
            failLog(logRow);
            throw new ImportException(ex.getMessage(), List.of(new ImportErrorDto(
                    1, 1, null, ex.getMessage())));
        } catch (RuntimeException ex) {
            failLog(logRow);
            throw ex;
        }
    }

    private void failLog(AdminImportLog logRow) {
        logRow.setStatus("failed");
        logRow.setFinishedAt(Instant.now());
        importRepository.save(logRow);
    }

    private MappedCatalog mapCatalog(List<CatalogFileParser.CatalogRawRow> rows,
                                     Map<String, CharacteristicType> typesByCode) {
        MappedCatalog data = new MappedCatalog();
        data.csvRows = rows.size();
        Errors errors = new Errors();

        // --- 1. Группировка по external_id (дедупликация, §5.1) --------
        Map<String, List<CatalogFileParser.CatalogRawRow>> groups = new LinkedHashMap<>();
        for (CatalogFileParser.CatalogRawRow row : rows) {
            String ext = AdminTextUtil.normText(row.get("external_id"));
            if (ext == null) {
                errors.add(row.rowNumber(), "id",
                        "строка без id (external_id): компания="
                                + AdminTextUtil.normText(row.get("vendor")));
                continue;
            }
            groups.computeIfAbsent(ext, k -> new ArrayList<>()).add(row);
        }
        data.dupGroups = (int) groups.values().stream().filter(g -> g.size() > 1).count();
        if (!errors.list.isEmpty()) {
            throw errors.toException();
        }

        // --- 2. Объединение групп -> решения -----------------------------
        for (Map.Entry<String, List<CatalogFileParser.CatalogRawRow>> entry
                : groups.entrySet()) {
            String ext = entry.getKey();
            List<CatalogFileParser.CatalogRawRow> group = entry.getValue();
            int firstRow = group.get(0).rowNumber();
            MergedSolution s = new MergedSolution();
            s.externalId = ext;
            s.name = mergeField(data, ext, "Название", "name", group);
            s.vendor = mergeField(data, ext, "компания", "vendor", group);
            s.productClass = mergeField(data, ext, "тип", "product_class", group);
            s.solutionType = mergeField(data, ext, "Тип", "solution_type", group);
            s.solutionSubtype = fixSubtype(mergeField(data, ext, "Подтип",
                    "solution_subtype", group), data);
            s.region = mergeField(data, ext, "Регион", "region", group);
            s.status = mergeField(data, ext, "статус", "status", group);

            // описание - самое длинное непустое в группе
            s.description = group.stream()
                    .map(r -> AdminTextUtil.normText(r.get("description")))
                    .filter(Objects::nonNull)
                    .max(java.util.Comparator.comparingInt(String::length))
                    .orElse(null);
            if (s.description == null && s.name != null) {
                data.warnings.add("у решения " + ext + " («" + s.name
                        + "») нет ни одного непустого описания -> NULL");
            }

            // цена: минимальная в группе (§5.5); каждая цена - в применении
            List<BigDecimal> prices = new ArrayList<>();
            for (CatalogFileParser.CatalogRawRow r : group) {
                BigDecimal price = parseDecimal(r.get("price_raw"));
                if (price == null) {
                    errors.add(r.rowNumber(), "Цена изделия",
                            "у решения " + ext + " не парсится цена: " + r.get("price_raw"));
                    continue;
                }
                prices.add(price);
            }
            if (prices.isEmpty()) {
                continue; // ошибка уже собрана
            }

            // обязательные текстовые поля (NOT NULL в схеме)
            if (s.name == null) {
                errors.add(firstRow, "Название",
                        "у решения " + ext + " пустое «Название» (name NOT NULL)");
                continue;
            }
            if (s.vendor == null) {
                errors.add(firstRow, "компания",
                        "у решения " + ext + " пустая «компания» (vendor NOT NULL)");
                continue;
            }

            s.priceRub = prices.stream().min(BigDecimal::compareTo).orElseThrow();
            // distinct у BigDecimal сравнивает scale
            // («900000» != «900000.00») - нормализуем перед сравнением
            if (prices.stream().map(BigDecimal::stripTrailingZeros).distinct()
                    .count() > 1) {
                data.warnings.add("КОНФЛИКТ цен у " + ext
                        + " -> price_rub=min, все цены сохранены в offer_price_rub "
                        + "применений");
            }

            if (!PRODUCT_CLASSES.contains(s.productClass)) {
                errors.add(firstRow, "тип",
                        "у решения " + ext + " неизвестный класс «" + s.productClass
                                + "» (допустимо brs/bas/software)");
                continue;
            }
            if (!STATUSES.contains(s.status)) {
                errors.add(firstRow, "статус",
                        "у решения " + ext + " неизвестный статус «" + s.status
                                + "» (допустимо operation/piloting/rnd)");
                continue;
            }

            String trlRaw = mergeField(data, ext, "УГТ", "trl_raw", group);
            if (trlRaw != null) {
                Short trl = parseIntInRange(trlRaw, 1, 9);
                if (trl == null) {
                    errors.add(firstRow, "УГТ",
                            "УГТ «" + trlRaw + "» не является целым в [1, 9] у решения "
                                    + ext);
                } else {
                    s.trl = trl;
                }
            }
            String mpRaw = mergeField(data, ext, "Рын Потенциал",
                    "market_potential_raw", group);
            if (mpRaw != null) {
                Short mp = parseIntInRange(mpRaw, 2, 5);
                if (mp == null) {
                    errors.add(firstRow, "Рын Потенциал",
                            "«Рын Потенциал» «" + mpRaw + "» не является целым в [2, 5] "
                                    + "у решения " + ext);
                } else {
                    s.marketPotential = BigDecimal.valueOf(mp);
                }
            } else {
                data.warnings.add("у решения " + ext + " («" + s.name
                        + "») пустой «Рын Потенциал» -> NULL");
            }

            // --- ТТХ расширенного формата (типобезопасно по data_type) --
            mapCharacteristics(s, group, typesByCode, data, errors);
            data.solutions.add(s);
        }
        if (!errors.list.isEmpty()) {
            throw errors.toException();
        }

        // --- 3. Применения и кейсы (по исходным строкам, §5.3-5.4) -----
        Map<String, BigDecimal> appPrice = new LinkedHashMap<>();
        Set<String> caseSet = new LinkedHashSet<>();
        Set<String> linkSet = new LinkedHashSet<>();
        for (CatalogFileParser.CatalogRawRow row : rows) {
            String ext = AdminTextUtil.normText(row.get("external_id"));
            String industry = AdminTextUtil.normText(row.get("industry"));
            if (industry == null) {
                errors.add(row.rowNumber(), "Отрасль",
                        "у строки " + ext + " пустая «Отрасль» (industry NOT NULL)");
                continue;
            }
            BigDecimal price = parseDecimal(row.get("price_raw"));
            if (price == null) {
                continue; // уже поймано на шаге 2
            }
            for (String proc : splitScenarios(row.get("scenario"), data)) {
                String key = key(ext, industry, proc);
                BigDecimal prev = appPrice.get(key);
                if (prev != null && prev.compareTo(price) != 0) {
                    data.warnings.add("КОНФЛИКТ: дубликат применения " + industry
                            + " + «" + proc + "» с ценой " + prev + " vs " + price
                            + " -> оставлена минимальная");
                    appPrice.put(key, prev.min(price));
                } else {
                    appPrice.put(key, price);
                }
            }
            String caseText = AdminTextUtil.normText(row.get("case"));
            if (caseText != null) {
                caseSet.add(caseText);
                linkSet.add(key(ext, caseText));
            }
        }
        if (!errors.list.isEmpty()) {
            throw errors.toException();
        }
        for (Map.Entry<String, BigDecimal> e : appPrice.entrySet()) {
            String[] parts = splitKey(e.getKey(), 3);
            data.applications.add(new MergedApplication(parts[0], parts[1], parts[2],
                    e.getValue()));
        }
        data.cases.addAll(caseSet);
        for (String link : linkSet) {
            data.caseLinks.add(splitKey(link, 2));
        }

        // --- 4. Справочники из фактических значений файла ---------------
        data.vendors.addAll(uniqueNames(data.warnings,
                data.solutions.stream().map(s -> s.vendor)));
        data.industries.addAll(uniqueNames(data.warnings,
                data.applications.stream().map(MergedApplication::industry)));
        data.regions.addAll(uniqueNames(data.warnings,
                data.solutions.stream().map(s -> s.region).filter(Objects::nonNull)));
        data.solutionTypes.addAll(uniqueNames(data.warnings,
                data.solutions.stream().map(s -> s.solutionType)
                        .filter(Objects::nonNull)));
        data.solutionSubtypes.addAll(uniqueNames(data.warnings,
                data.solutions.stream().map(s -> s.solutionSubtype)
                        .filter(Objects::nonNull)));
        data.processes.addAll(uniqueNames(data.warnings,
                data.applications.stream().map(MergedApplication::process)));
        return data;
    }

    // ==================================================================
    // Запись в БД: порт repositories/catalog_repo.py
    // ==================================================================

    /**
 * Справочник ТТХ из БД: код -> тип (27 записей characteristic_type).
 */
    private Map<String, CharacteristicType> loadCharacteristicTypes() {
        Map<String, CharacteristicType> byCode = new LinkedHashMap<>();
        for (CharacteristicType type : characteristicTypeRepository.findAll()) {
            byCode.put(type.getCode(), type);
        }
        return byCode;
    }

    private DictSnapshots loadDictSnapshots() {
        DictSnapshots d = new DictSnapshots();
        vendorRepository.findAll().forEach(v -> d.vendors.put(DictSnapshots.ck(v.getName()),
                v));
        industryRepository.findAll().forEach(i -> {
            d.industries.put(DictSnapshots.ck(i.getName()), i);
            d.usedCodes.add(i.getCode());
        });
        processRepository.findAll().forEach(p -> {
            d.processes.put(DictSnapshots.ck(p.getName()), p);
            d.usedCodes.add(p.getCode());
        });
        regionRepository.findAll().forEach(r -> {
            d.regions.put(DictSnapshots.ck(r.getName()), r);
            d.usedCodes.add(r.getCode());
        });
        solutionTypeRepository.findAll().forEach(t -> {
            d.solutionTypes.put(DictSnapshots.ck(t.getName()), t);
            d.usedCodes.add(t.getCode());
        });
        solutionSubtypeRepository.findAll().forEach(st -> {
            d.solutionSubtypes.put(DictSnapshots.ck(st.getName()), st);
            d.usedCodes.add(st.getCode());
        });
        caseRepository.findAll().forEach(c ->
                d.cases.put(DictSnapshots.ck(c.getName()), c));
        applicationRepository.findAll().forEach(a -> d.applications.put(
                key(String.valueOf(a.getSolutionId()), String.valueOf(a.getIndustryId()),
                        String.valueOf(a.getProcessId())), a));
        return d;
    }

    /**
 * Применение промапленных данных: upsert'ы + счётчики (в транзакции).
 */
    private CatalogImportSummaryDto applyMapped(MappedCatalog data) {
        CatalogImportSummaryDto summary = CatalogImportSummaryDto.builder()
                .status("completed")
                .csvRows(data.csvRows)
                .solutions(data.solutions.size())
                .dupGroups(data.dupGroups)
                .entities(new LinkedHashMap<>())
                .warnings(data.warnings)
                .build();

        DictSnapshots dicts = loadDictSnapshots();

        upsertVendors(data, summary, dicts);
        upsertIndustries(data, summary, dicts);
        upsertProcesses(data, summary, dicts);
        upsertRegions(data, summary, dicts);
        upsertSolutionTypes(data, summary, dicts);
        upsertSolutionSubtypes(data, summary, dicts);

        Map<String, CharacteristicType> typesByCode = loadCharacteristicTypes();
        Map<String, Long> solutionIds = upsertSolutions(data, summary, dicts, typesByCode);
        upsertCharacteristics(data, summary, solutionIds, typesByCode);
        upsertApplications(data, summary, dicts, solutionIds);
        Map<String, Long> caseIds = upsertCases(data, summary, dicts);
        upsertCaseLinks(data, summary, solutionIds, caseIds);
        return summary;
    }

    private void upsertVendors(MappedCatalog data, CatalogImportSummaryDto summary,
                               DictSnapshots dicts) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "vendors");
        for (String name : data.vendors) {
            Vendor existing = dicts.vendors.get(DictSnapshots.ck(name));
            if (existing == null) {
                Vendor created = Vendor.builder().name(name).build();
                dicts.vendors.put(DictSnapshots.ck(name), vendorRepository.save(created));
                counters.incAdded();
            } else {
                counters.incSkipped();
            }
        }
    }

    private void upsertIndustries(MappedCatalog data, CatalogImportSummaryDto summary,
                                  DictSnapshots dicts) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "industries");
        for (String name : data.industries) {
            String ck = DictSnapshots.ck(name);
            if (dicts.industries.containsKey(ck)) {
                counters.incSkipped();
                continue;
            }
            Industry created = Industry.builder()
                    .code(nextCode(name, dicts)).name(name).build();
            dicts.industries.put(ck, industryRepository.save(created));
            counters.incAdded();
        }
    }

    private void upsertProcesses(MappedCatalog data, CatalogImportSummaryDto summary,
                                 DictSnapshots dicts) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "processes");
        for (String name : data.processes) {
            String ck = DictSnapshots.ck(name);
            if (dicts.processes.containsKey(ck)) {
                counters.incSkipped();
                continue;
            }
            Process created = Process.builder()
                    .code(nextCode(name, dicts)).name(name).isActive(true).build();
            dicts.processes.put(ck, processRepository.save(created));
            counters.incAdded();
        }
    }

    private void upsertRegions(MappedCatalog data, CatalogImportSummaryDto summary,
                               DictSnapshots dicts) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "regions");
        for (String name : data.regions) {
            String ck = DictSnapshots.ck(name);
            if (dicts.regions.containsKey(ck)) {
                counters.incSkipped();
                continue;
            }
            Region created = Region.builder()
                    .code(nextCode(name, dicts)).name(name).build();
            dicts.regions.put(ck, regionRepository.save(created));
            counters.incAdded();
        }
    }

    private void upsertSolutionTypes(MappedCatalog data, CatalogImportSummaryDto summary,
                                     DictSnapshots dicts) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "solution_types");
        for (String name : data.solutionTypes) {
            String ck = DictSnapshots.ck(name);
            if (dicts.solutionTypes.containsKey(ck)) {
                counters.incSkipped();
                continue;
            }
            SolutionType created = SolutionType.builder()
                    .code(nextCode(name, dicts)).name(name).build();
            dicts.solutionTypes.put(ck, solutionTypeRepository.save(created));
            counters.incAdded();
        }
    }

    private void upsertSolutionSubtypes(MappedCatalog data, CatalogImportSummaryDto summary,
                                        DictSnapshots dicts) {
        CatalogImportSummaryDto.EntityCountersDto counters =
                counters(summary, "solution_subtypes");
        for (String name : data.solutionSubtypes) {
            String ck = DictSnapshots.ck(name);
            if (dicts.solutionSubtypes.containsKey(ck)) {
                counters.incSkipped();
                continue;
            }
            SolutionSubtype created = SolutionSubtype.builder()
                    .code(nextCode(name, dicts)).name(name).build();
            dicts.solutionSubtypes.put(ck, solutionSubtypeRepository.save(created));
            counters.incAdded();
        }
    }

    private Map<String, Long> upsertSolutions(MappedCatalog data,
                                              CatalogImportSummaryDto summary,
                                              DictSnapshots dicts,
                                              Map<String, CharacteristicType> typesByCode) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "solutions");
        Map<String, Long> solutionIds = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (MergedSolution s : data.solutions) {
            UUID extUuid;
            try {
                extUuid = UUID.fromString(s.externalId);
            } catch (IllegalArgumentException ex) {
                throw new ImportException("external_id не является UUID", List.of(
                        new ImportErrorDto(1, 1, "id",
                                "external_id «" + s.externalId + "» не является UUID "
                                        + "(решение «" + s.name + "»)")));
            }
            Vendor vendor = dicts.vendors.get(DictSnapshots.ck(s.vendor));
            if (vendor == null) {
                throw new IllegalStateException(
                        "Вендор «" + s.vendor + "» не найден в снимке (решение " + s.name
                                + ") - синхронизация справочников нарушена");
            }
            SolutionType type = s.solutionType == null ? null
                    : dicts.solutionTypes.get(DictSnapshots.ck(s.solutionType));
            SolutionSubtype subtype = s.solutionSubtype == null ? null
                    : dicts.solutionSubtypes.get(DictSnapshots.ck(s.solutionSubtype));
            Region region = s.region == null ? null
                    : dicts.regions.get(DictSnapshots.ck(s.region));

            Solution existing = solutionRepository.findByExternalId(extUuid).orElse(null);
            boolean reLinked = false;
            if (existing == null) {
                // Фолбэк: тот же вендор+название, но новый UUID (файл
                // воссоздан организатором) - обновляем существующую карточку,
                // иначе INSERT упал бы в UNIQUE (vendor_id, name)
                existing = solutionRepository
                        .findByVendorIdAndName(vendor.getId(), s.name).orElse(null);
                reLinked = existing != null;
            }

            Long typeId = type == null ? null : type.getId();
            Long subtypeId = subtype == null ? null : subtype.getId();
            Long regionId = region == null ? null : region.getId();

            if (existing == null) {
                Solution created = Solution.builder()
                        .externalId(extUuid)
                        .name(s.name)
                        .vendorId(vendor.getId())
                        .productClass(s.productClass)
                        .solutionTypeId(typeId)
                        .solutionSubtypeId(subtypeId)
                        .regionId(regionId)
                        .status(s.status)
                        .description(s.description)
                        .priceRub(s.priceRub)
                        .trl(s.trl)
                        .marketPotential(s.marketPotential)
                        .sourceKind(SOURCE_KIND)
                        .sourceUrl(SOURCE_URL)
                        .sourceDate(today)
                        .build();
                // зеркальные ТТХ-колонки - из колонок ТТХ файла (§5.8)
                applyMirrorColumns(created, s.characteristics);
                solutionIds.put(s.externalId, solutionRepository.save(created).getId());
                counters.incAdded();
                continue;
            }

            // расхождение зеркальных ТТХ - тоже изменение карточки (только
            // для кодов, ПРИСУТСТВУЮЩИХ в файле; отсутствие не обнуляет)
            boolean mirrorsChanged = false;
            for (MergedCharacteristic c : s.characteristics) {
                if (c.numeric() == null
                        || !AdminCharacteristicService.MIRROR_SETTERS.containsKey(c.code())) {
                    continue;
                }
                if (decimalChanged(mirrorValueOf(existing, c.code()), c.numeric())) {
                    mirrorsChanged = true;
                    break;
                }
            }

            boolean changed = reLinked
                    || !Objects.equals(existing.getName(), s.name)
                    || !Objects.equals(existing.getVendorId(), vendor.getId())
                    || !Objects.equals(existing.getProductClass(), s.productClass)
                    || !Objects.equals(existing.getSolutionTypeId(), typeId)
                    || !Objects.equals(existing.getSolutionSubtypeId(), subtypeId)
                    || !Objects.equals(existing.getRegionId(), regionId)
                    || !Objects.equals(existing.getStatus(), s.status)
                    || !Objects.equals(existing.getDescription(), s.description)
                    || existing.getPriceRub().compareTo(s.priceRub) != 0
                    || !Objects.equals(existing.getTrl(), s.trl)
                    || decimalChanged(existing.getMarketPotential(), s.marketPotential)
                    || !SOURCE_KIND.equals(existing.getSourceKind())
                    || !Objects.equals(existing.getSourceUrl(), SOURCE_URL)
                    || mirrorsChanged;
            if (changed) {
                existing.setExternalId(extUuid);
                existing.setName(s.name);
                existing.setVendorId(vendor.getId());
                existing.setProductClass(s.productClass);
                existing.setSolutionTypeId(typeId);
                existing.setSolutionSubtypeId(subtypeId);
                existing.setRegionId(regionId);
                existing.setStatus(s.status);
                existing.setDescription(s.description);
                existing.setPriceRub(s.priceRub);
                existing.setTrl(s.trl);
                existing.setMarketPotential(s.marketPotential);
                existing.setSourceKind(SOURCE_KIND);
                existing.setSourceUrl(SOURCE_URL);
                existing.setSourceDate(today);
                // зеркальные ТТХ - значение из файла, отсутствующие коды не трогаем
                applyMirrorColumns(existing, s.characteristics);
                solutionRepository.save(existing);
                counters.incUpdated();
            } else {
                counters.incSkipped();
            }
            solutionIds.put(s.externalId, existing.getId());
        }
        return solutionIds;
    }

    /**
 * EAV-значения ТТХ из расширенного формата: upsert по (solution, type)
 * с провенансом organizer_catalog. Существующее значение затирается
 * ТОЛЬКО если файл объявляет колонку этого кода; провенанс - из
 * колонок source/source_date/confirmation_status того же файла.
 */
    private void upsertCharacteristics(MappedCatalog data, CatalogImportSummaryDto summary,
                                       Map<String, Long> solutionIds,
                                       Map<String, CharacteristicType> typesByCode) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "characteristics");
        // id типа -> код (справочник мал - один проход)
        Map<Long, String> codeByTypeId = new HashMap<>();
        for (CharacteristicType t : typesByCode.values()) {
            codeByTypeId.put(t.getId(), t.getCode());
        }
        for (MergedSolution s : data.solutions) {
            if (s.characteristics.isEmpty()) {
                continue;
            }
            Long solutionId = solutionIds.get(s.externalId);
            if (solutionId == null) {
                continue; // решение с ошибкой - уже зафиксировано выше
            }
            Map<String, SolutionCharacteristic> existingByCode = new LinkedHashMap<>();
            for (SolutionCharacteristic row : characteristicRepository
                    .findBySolutionId(solutionId)) {
                String code = codeByTypeId.get(row.getCharacteristicTypeId());
                if (code != null) {
                    existingByCode.put(code, row);
                }
            }
            for (MergedCharacteristic c : s.characteristics) {
                CharacteristicType type = typesByCode.get(c.code());
                if (type == null) {
                    continue; // защита (не после валидации заголовков)
                }
                SolutionCharacteristic row = existingByCode.get(c.code());
                boolean exists = row != null;
                if (exists && valueEquals(row, c)
                        && SOURCE_KIND.equals(row.getSourceKind())
                        && Objects.equals(row.getSourceUrl(), s.charSourceUrl)
                        && Objects.equals(row.getSourceDate(), s.charSourceDate)
                        && Objects.equals(row.getIsConfirmed(), s.charConfirmed)) {
                    counters.incSkipped();
                    continue;
                }
                if (!exists) {
                    row = SolutionCharacteristic.builder()
                            .solutionId(solutionId)
                            .characteristicTypeId(type.getId())
                            .build();
                }
                row.setValueNumeric(c.numeric());
                row.setValueText(c.text());
                row.setValueBool(c.bool());
                row.setValueDate(c.date());
                row.setSourceKind(SOURCE_KIND);
                row.setSourceUrl(s.charSourceUrl);
                row.setSourceDate(s.charSourceDate);
                row.setIsConfirmed(s.charConfirmed);
                characteristicRepository.save(row);
                if (exists) {
                    counters.incUpdated();
                } else {
                    counters.incAdded();
                }
            }
        }
    }

    private void upsertApplications(MappedCatalog data, CatalogImportSummaryDto summary,
                                    DictSnapshots dicts, Map<String, Long> solutionIds) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "applications");
        for (MergedApplication a : data.applications) {
            Long solutionId = solutionIds.get(a.externalId());
            Industry industry = dicts.industries.get(DictSnapshots.ck(a.industry()));
            Process process = dicts.processes.get(DictSnapshots.ck(a.process()));
            if (solutionId == null || industry == null || process == null) {
                throw new IllegalStateException("Снимок справочников рассинхронизован "
                        + "при импорте применений: " + a.externalId());
            }
            String mapKey = key(String.valueOf(solutionId), String.valueOf(industry.getId()),
                    String.valueOf(process.getId()));
            SolutionApplication existing = dicts.applications.get(mapKey);
            if (existing == null) {
                SolutionApplication created = SolutionApplication.builder()
                        .solutionId(solutionId)
                        .industryId(industry.getId())
                        .processId(process.getId())
                        .offerPriceRub(a.offerPriceRub())
                        .build();
                applicationRepository.save(created);
                dicts.applications.put(mapKey, created);
                counters.incAdded();
            } else if (existing.getOfferPriceRub().compareTo(a.offerPriceRub()) != 0) {
                existing.setOfferPriceRub(a.offerPriceRub());
                applicationRepository.save(existing);
                counters.incUpdated();
            } else {
                counters.incSkipped();
            }
        }
    }

    private Map<String, Long> upsertCases(MappedCatalog data, CatalogImportSummaryDto summary,
                                          DictSnapshots dicts) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "cases");
        Map<String, Long> caseIds = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (String name : data.cases) {
            String ck = DictSnapshots.ck(name);
            SolutionCase existing = dicts.cases.get(ck);
            if (existing == null) {
                SolutionCase created = SolutionCase.builder()
                        .name(name)
                        .description(null)
                        .sourceUrl(SOURCE_URL)
                        .sourceDate(today)
                        .build();
                Long id = caseRepository.save(created).getId();
                dicts.cases.put(ck, created);
                caseIds.put(ck, id);
                counters.incAdded();
            } else {
                caseIds.put(ck, existing.getId());
                if (!Objects.equals(existing.getSourceUrl(), SOURCE_URL)) {
                    existing.setSourceUrl(SOURCE_URL);
                    existing.setSourceDate(today);
                    caseRepository.save(existing);
                    counters.incUpdated();
                } else {
                    counters.incSkipped();
                }
            }
        }
        return caseIds;
    }

    private void upsertCaseLinks(MappedCatalog data, CatalogImportSummaryDto summary,
                                 Map<String, Long> solutionIds, Map<String, Long> caseIds) {
        CatalogImportSummaryDto.EntityCountersDto counters = counters(summary, "case_links");
        Set<String> existingLinks = new HashSet<>();
        for (SolutionCaseLink l : caseLinkRepository.findAll()) {
            existingLinks.add(key(String.valueOf(l.getSolutionId()),
                    String.valueOf(l.getCaseId())));
        }
        for (String[] link : data.caseLinks) {
            Long solutionId = solutionIds.get(link[0]);
            Long caseId = caseIds.get(DictSnapshots.ck(link[1]));
            if (solutionId == null || caseId == null) {
                continue; // решение с ошибкой - уже зафиксировано выше
            }
            String mapKey = key(String.valueOf(solutionId), String.valueOf(caseId));
            if (existingLinks.contains(mapKey)) {
                counters.incSkipped();
                continue;
            }
            caseLinkRepository.save(SolutionCaseLink.builder()
                    .solutionId(solutionId)
                    .caseId(caseId)
                    .build());
            existingLinks.add(mapKey);
            counters.incAdded();
        }
    }

    /**
 * Промежуточное представление (аналог CatalogData в Python).
 */
    static class MappedCatalog {
        List<MergedSolution> solutions = new ArrayList<>();
        List<MergedApplication> applications = new ArrayList<>();
        List<String> cases = new ArrayList<>();       // уникальные тексты кейсов
        List<String[]> caseLinks = new ArrayList<>(); // [externalId, caseText]
        List<String> vendors = new ArrayList<>();
        List<String> industries = new ArrayList<>();
        List<String> regions = new ArrayList<>();
        List<String> solutionTypes = new ArrayList<>();
        List<String> solutionSubtypes = new ArrayList<>();
        List<String> processes = new ArrayList<>();
        int csvRows;
        int dupGroups;
        List<String> warnings = new ArrayList<>();
    }

    static class MergedSolution {
        String externalId;
        String name;
        String vendor;
        String productClass;
        String solutionType;
        String solutionSubtype;
        String region;
        String status;
        String description;
        BigDecimal priceRub;
        Short trl;
        BigDecimal marketPotential;
        /**
 * ТТХ расширенного формата (типобезопасные значения по data_type).
 */
        List<MergedCharacteristic> characteristics = new ArrayList<>();
        /**
 * Провенанс всех ТТХ решения из колонок source/source_date.
 */
        String charSourceUrl;
        LocalDate charSourceDate;
        /**
 * is_confirmed из колонки confirmation_status (§7: по умолчанию false).
 */
        boolean charConfirmed;
    }

    /**
 * Значение ТТХ из файла: ровно одно поле под data_type типа.
 */
    record MergedCharacteristic(String code, BigDecimal numeric, String text,
                                Boolean bool, LocalDate date) {
    }

    record MergedApplication(String externalId, String industry, String process,
                             BigDecimal offerPriceRub) {
    }

    /**
 * Сбор ошибок строк: в ответ уходит ≤ 20 (ImportException), прочее в лог.
 */
    private static class Errors {
        final List<ImportErrorDto> list = new ArrayList<>();

        void add(int row, String column, String message) {
            if (list.size() < 200) {
                list.add(new ImportErrorDto(row, 1, column, message));
            }
            log.warn("Импорт каталога, строка {}: {}", row, message);
        }

        ImportException toException() {
            return new ImportException("Файл содержит ошибки ("
                    + list.size() + ") - исправьте ячейки и загрузите снова", list);
        }
    }

    /**
 * Снимки справочников на время импорта (справочники малы - целиком).
 */
    private static class DictSnapshots {
        final Map<String, Vendor> vendors = new LinkedHashMap<>();
        final Map<String, Industry> industries = new LinkedHashMap<>();
        final Map<String, Process> processes = new LinkedHashMap<>();
        final Map<String, Region> regions = new LinkedHashMap<>();
        final Map<String, SolutionType> solutionTypes = new LinkedHashMap<>();
        final Map<String, SolutionSubtype> solutionSubtypes = new LinkedHashMap<>();
        final Map<String, SolutionCase> cases = new LinkedHashMap<>();
        final Map<String, SolutionApplication> applications = new LinkedHashMap<>();
        final Set<String> usedCodes = new HashSet<>();

        static String ck(String name) {
            return AdminTextUtil.canonicalKey(name);
        }
    }
}
