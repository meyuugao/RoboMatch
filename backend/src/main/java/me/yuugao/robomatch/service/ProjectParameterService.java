package me.yuugao.robomatch.service;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.*;
import me.yuugao.robomatch.repository.*;
import me.yuugao.robomatch.service.ImportParser.ParsedFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

/**
 * Бизнес-логика параметров объекта (–
 * 3.2.5): ручной ввод с валидацией по метаданным, атомарный импорт
 * Excel/CSV, вложения.
 * <p>
 * ИЗОЛЯЦИЯ: как в ProjectService - userId из JWT, чужой
 * проект неотличим от несуществующего (404, не 403).
 * <p>
 * ВАЛИДАЦИЯ: единственная точка правды - метаданные
 * object_type_parameter + parameter_type.value_type. Ручной ввод и
 * импорт проходят одни и те же проверки (тип, диапазон, обязательность),
 * сообщения - понятные, со способом исправления.
 * <p>
 * UPSERT: UNIQUE (project_id, object_type_parameter_id) в V1. Ручная
 * установка и сброс - однострочные операции БЕЗ внешней транзакции:
 * атомарность даёт сам INSERT/UPDATE, а гонка двух одновременных
 * вставок ловится DataIntegrityViolationException и повторяется как
 * UPDATE в новой транзакции (внешний @Transactional пометил бы tx
 * rollback-only и повтор стал бы невозможен - грабля PostgreSQL).
 * <p>
 * WORST-CASE ПАРАМЕТРЫ: is_fixed - константа
 * (коэффициент начислений на ФОТ 1.302, рабочих дней в году 365,
 * наличие WMS) - PUT/DELETE значения 400, currentValue = defaultValue,
 * пользовательские строки игнорируются; is_derived - вычисляется из
 * источника (объём отбора штук/сутки = строки/сутки x 1.5, площадь
 * активной зоны = общая площадь x 0.5 - примечания датасета
 * организатора): PUT/DELETE 400, currentValue считается на GET,
 * формулы зашиты в код (DERIVED_SPECS), из БД не парсятся.
 * <p>
 * ИМПОРТ - АТОРМАН: разбор и валидация ВСЕГО файла до любых
 * изменений БД. Есть ошибки - 400 со списком (до 20 в теле, остальное в
 * лог), данные не тронуты. Нет ошибок - ОДНА транзакция (Transaction-
 * Template): строка вложения + перенос файла из tmp в постоянное место
 * + UPSERT всех значений (source='import'). Ошибка БД - откат, файлы
 * удалены, наружу - 500 без стектрейса. Проект и прежние значения
 * пользователя не теряются.
 */
@Service
@RequiredArgsConstructor
public class ProjectParameterService {

    private static final Logger log = LoggerFactory.getLogger(ProjectParameterService.class);

    /**
 * Белый список MIME для импорта (data_model.md §10.4: форматы Excel/CSV).
 */
    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_XLS = "application/vnd.ms-excel";
    private static final String MIME_CSV = "text/csv";
    private static final Set<String> ALLOWED_MIME = Set.of(MIME_XLSX, MIME_XLS, MIME_CSV);

    /**
 * Производные параметры: формулы зашиты в код по коду
 * parameter_type (НЕ парсятся из БД). Коэффициенты - из примечаний
 * Датасеты_хакатон.xlsx: «Среднее кол-во штук на строку ~1,5»
 * (лист «Склад», объём отбора) и «50% от общей площади» (площадь
 * активной зоны). Источник значения - эффективное значение
 * параметра-источника: строка проекта или дефолт метаданных.
 */
    private static final Map<String, DerivedSpec> DERIVED_SPECS = Map.of(
            "picking_units_per_day",
            new DerivedSpec("picking_lines_per_day", new BigDecimal("1.5")),
            "active_zone_area",
            new DerivedSpec("total_warehouse_area", new BigDecimal("0.5")));
    private final ProjectRepository projectRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final ObjectTypeParameterRepository objectTypeParameterRepository;
    private final ParameterTypeRepository parameterTypeRepository;
    private final ProjectParameterValueRepository valueRepository;
    private final ProjectAttachmentRepository attachmentRepository;
    private final AttachmentStorage storage;
    private final ImportParser importParser;
    private final TransactionTemplate transactionTemplate;

    /**
 * Фиксированные и производные параметры не редактируются:
 * PUT и DELETE значения - 400 с понятной причиной. Строка проекта,
 * оставшаяся от старых версий (до V4), не читается и не чистится -
 * мёртвые данные игнорируются везде.
 */
    private static void rejectNotEditable(ParameterMeta meta) {
        if (Boolean.TRUE.equals(meta.definition().getIsFixed())) {
            throw new BadRequestException("Параметр «" + meta.type().getName()
                    + "» фиксированный: значение задаётся системой и не редактируется.");
        }
        if (Boolean.TRUE.equals(meta.definition().getIsDerived())) {
            throw new BadRequestException("Параметр «" + meta.type().getName()
                    + "» рассчитывается автоматически и не редактируется вручную.");
        }
    }

    /**
 * Число из JSON-значения (Integer/Long/Double/BigDecimal) в numeric(16,4).
 */
    private static BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return BigDecimal.valueOf(number.doubleValue());
        } catch (NumberFormatException ex) {
            // NaN/Infinity из JSON-парсера - числом это считать нельзя
            throw new BadRequestException("Число вне допустимого диапазона точности");
        }
    }

    /**
 * Число из ячейки: пробелы срезаются, запятая - десятичный разделитель (русская локаль).
 */
    private static BigDecimal parseNumber(String raw) {
        String normalized = raw.replace('\u00A0', ' ')
                .replace(" ", "")
                .replace(',', '.');
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Список параметров (форма)
    // ------------------------------------------------------------------

    /**
 * «да»/«нет» и общепринятые написания (импорт из Excel).
 */
    private static Boolean parseBoolean(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "да", "д", "true", "1", "yes", "y" -> Boolean.TRUE;
            case "нет", "н", "false", "0", "no", "n" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // Ручной ввод
    // ------------------------------------------------------------------

    private static boolean inRange(ParameterMeta meta, BigDecimal value) {
        BigDecimal min = meta.definition().getMinValue();
        BigDecimal max = meta.definition().getMaxValue();
        return (min == null || value.compareTo(min) >= 0)
                && (max == null || value.compareTo(max) <= 0);
    }

    private static void checkRange(ParameterMeta meta, BigDecimal value) {
        if (!inRange(meta, value)) {
            throw new BadRequestException(rangeMessage(meta));
        }
    }

    /**
 * Понятное сообщение о диапазоне: «от X до Y (единица)».
 */
    private static String rangeMessage(ParameterMeta meta) {
        BigDecimal min = meta.definition().getMinValue();
        BigDecimal max = meta.definition().getMaxValue();
        String unit = meta.type().getUnit() == null ? "" : " " + meta.type().getUnit();
        String name = meta.type().getName();
        if (min != null && max != null) {
            return "Параметр «" + name + "»: значение должно быть от " + min.stripTrailingZeros()
                    .toPlainString() + " до " + max.stripTrailingZeros().toPlainString()
                    + unit + ". Исправьте значение и попробуйте снова.";
        }
        if (min != null) {
            return "Параметр «" + name + "»: значение не может быть меньше "
                    + min.stripTrailingZeros().toPlainString() + unit + ".";
        }
        if (max != null) {
            return "Параметр «" + name + "»: значение не может быть больше "
                    + max.stripTrailingZeros().toPlainString() + unit + ".";
        }
        return "Параметр «" + name + "»: значение вне допустимого диапазона.";
    }

    // ------------------------------------------------------------------
    // Импорт Excel/CSV - атомарный
    // ------------------------------------------------------------------

    /**
 * Короткая подсказка диапазона для сообщений о типе.
 */
    private static String rangeHint(ParameterMeta meta) {
        BigDecimal min = meta.definition().getMinValue();
        BigDecimal max = meta.definition().getMaxValue();
        if (min != null && max != null) {
            return " от " + min.stripTrailingZeros().toPlainString()
                    + " до " + max.stripTrailingZeros().toPlainString();
        }
        return "";
    }

    /**
 * Подсказка «например, <дефолт>» для обязательного параметра.
 */
    private static String defaultHint(ParameterMeta meta) {
        String value = defaultHintValue(meta);
        return value == null ? "" : " (например, " + value + ")";
    }

    /**
 * Дефолт метаданных в человекочитаемом виде.
 */
    private static String defaultHintValue(ParameterMeta meta) {
        return switch (meta.type().getValueType()) {
            case NUMBER -> meta.definition().getDefaultValueNumeric() == null ? null
                    : meta.definition().getDefaultValueNumeric()
                    .stripTrailingZeros().toPlainString();
            case BOOLEAN -> meta.definition().getDefaultValueBool() == null ? null
                    : (meta.definition().getDefaultValueBool() ? "да" : "нет");
            case TEXT -> meta.definition().getDefaultValueText();
        };
    }

    // ------------------------------------------------------------------
    // Вложения
    // ------------------------------------------------------------------

    /**
 * Заполнение ровно одной value-колонки по типу (CHECK EAV, §10.3).
 */
    private static void applyValue(ProjectParameterValue row, ValidatedValue value) {
        row.setValueNumeric(value.numeric());
        row.setValueText(value.text());
        row.setValueBool(value.bool());
    }

    /**
 * Параметр с метаданными и значением -> DTO для ответа PUT (только
 * редактируемые параметры: фиксированные/производные отсечены
 * rejectNotEditable до этой точки).
 */
    private static ParameterDto toView(ParameterMeta meta, ProjectParameterValue value) {
        return toView(meta, value, Map.of(), Map.of());
    }

    // ------------------------------------------------------------------
    // Шаблон импорта
    // ------------------------------------------------------------------

    /**
 * Полная сборка DTO (список GET): фиксированные показывают константу
 * (default), производные - вычисленное значение из эффективного
 * значения источника (строка проекта или дефолт) с множителем.
 * Пользовательские строки fixed/derived игнорируются (могли остаться
 * от версий до V4).
 */
    private static ParameterDto toView(ParameterMeta meta, ProjectParameterValue value,
                                       Map<String, ParameterMeta> metasByCode,
                                       Map<Long, ProjectParameterValue> valuesById) {
        ParameterValueType type = meta.type().getValueType();
        String kind = type == ParameterValueType.BOOLEAN ? "bool" : type.name().toLowerCase();
        TypedValueDto defaultValue = switch (type) {
            case NUMBER -> meta.definition().getDefaultValueNumeric() == null ? null
                    : new TypedValueDto(kind, meta.definition().getDefaultValueNumeric());
            case BOOLEAN -> meta.definition().getDefaultValueBool() == null ? null
                    : new TypedValueDto(kind, meta.definition().getDefaultValueBool());
            case TEXT -> meta.definition().getDefaultValueText() == null ? null
                    : new TypedValueDto(kind, meta.definition().getDefaultValueText());
        };
        TypedValueDto currentValue = value == null ? null : switch (type) {
            case NUMBER -> new TypedValueDto(kind, value.getValueNumeric());
            case BOOLEAN -> new TypedValueDto(kind, value.getValueBool());
            case TEXT -> new TypedValueDto(kind, value.getValueText());
        };
        Instant updatedAt = value == null ? null : value.getUpdatedAt();
        String derivedFromName = null;
        boolean fixed = Boolean.TRUE.equals(meta.definition().getIsFixed());
        boolean derived = Boolean.TRUE.equals(meta.definition().getIsDerived());
        if (fixed) {
            // константа: значение - дефолт метаданных, строка проекта игнорируется
            currentValue = defaultValue;
            updatedAt = null;
        } else if (derived) {
            DerivedSpec spec = DERIVED_SPECS.get(meta.type().getCode());
            ParameterMeta source = spec == null ? null : metasByCode.get(spec.sourceCode());
            if (source != null) {
                derivedFromName = source.type().getName();
                BigDecimal sourceNumeric = effectiveNumeric(source, valuesById);
                currentValue = sourceNumeric == null ? null
                        : new TypedValueDto(kind, sourceNumeric.multiply(spec.factor()));
            } else {
                // производный без источника в метаданных (рассинхрон
                // DERIVED_SPECS и seed) - контракт «строки не читаются»
                // держим и здесь: показываем null, а не мёртвую строку
                // (чтобы не показывать мёртвую строку)
                log.warn("Производный параметр «{}» без источника «{}» в "
                                + "метаданных типа объекта - значение не вычислено",
                        meta.type().getCode(),
                        spec == null ? "?" : spec.sourceCode());
                currentValue = null;
            }
            updatedAt = null;
        }
        return ParameterDto.builder()
                .id(meta.definition().getId())
                .code(meta.type().getCode())
                .name(meta.type().getName())
                .unit(meta.type().getUnit())
                .groupName(meta.definition().getGroupName())
                .valueType(type.name().toLowerCase())
                .isRequired(meta.definition().getIsRequired())
                .isFixed(fixed)
                .isDerived(derived)
                .derivedFromName(derivedFromName)
                .defaultValue(defaultValue)
                .minValue(meta.definition().getMinValue())
                .maxValue(meta.definition().getMaxValue())
                .sourceNote(meta.definition().getSourceNote())
                .currentValue(currentValue)
                .updatedAt(updatedAt)
                .build();
    }

    // ------------------------------------------------------------------
    // Валидация значений
    // ------------------------------------------------------------------

    /**
 * Эффективное числовое значение параметра: строка проекта, иначе дефолт
 * метаданных (конвенция подбора и экономики: пользовательское значение
 * перекрывает дефолт; selection_algorithm.md раздел 4).
 */
    private static BigDecimal effectiveNumeric(ParameterMeta meta,
                                               Map<Long, ProjectParameterValue> valuesById) {
        ProjectParameterValue row = valuesById.get(meta.definition().getId());
        if (row != null && row.getValueNumeric() != null) {
            return row.getValueNumeric();
        }
        return meta.definition().getDefaultValueNumeric();
    }

    /**
 * MIME файла: белый список; пустой/octet-stream судится по
 * расширению (браузеры и некоторые ОС не всегда проставляют тип).
 */
    private static String allowedMime(MultipartFile file) {
        String raw = file.getContentType();
        String mime = raw == null ? "" : raw.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_MIME.contains(mime)) {
            return mime;
        }
        if (mime.isEmpty() || "application/octet-stream".equals(mime)) {
            String name = file.getOriginalFilename() == null ? ""
                    : file.getOriginalFilename().toLowerCase(Locale.ROOT);
            if (name.endsWith(".csv")) {
                return MIME_CSV;
            }
            if (name.endsWith(".xlsx")) {
                return MIME_XLSX;
            }
            if (name.endsWith(".xls")) {
                return MIME_XLS;
            }
        }
        throw new BadRequestException("Файл должен быть Excel (.xlsx, .xls) или CSV (.csv).");
    }

    /**
 * Все параметры типа объекта проекта с метаданными и текущими
 * значениями. Три запроса, без N+1: определения
 * одного типа, справочник типов параметров одним IN, значения
 * проекта одним запросом.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return параметры с метаданными и эффективными значениями
 */
    @Transactional(readOnly = true)
    public List<ParameterDto> getParameters(Long userId, Long projectId) {
        Project project = loadOwnedProject(userId, projectId);
        Map<Long, ParameterMeta> metas = loadMetadata(project.getObjectTypeId());
        Map<Long, ProjectParameterValue> values = valueRepository
                .findAllByProjectId(projectId).stream()
                .collect(Collectors.toMap(ProjectParameterValue::getObjectTypeParameterId,
                        Function.identity()));
        Map<String, ParameterMeta> metasByCode = new HashMap<>();
        for (ParameterMeta meta : metas.values()) {
            metasByCode.put(meta.type().getCode(), meta);
        }
        return metas.values().stream()
                .map(meta -> toView(meta, values.get(meta.definition().getId()),
                        metasByCode, values))
                .toList();
    }

    /**
 * Установка значения вручную. Валидация типа/диапазона/обязательности
 * по метаданным, идемпотентный UPSERT, source='manual'.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param parameterId идентификатор параметра (object_type_parameter)
 * @param request новое значение (тип по data_type)
 * @return параметр с обновлённым значением
 */
    public ParameterDto setParameterValue(Long userId, Long projectId,
                                          Long parameterId, ParameterSetRequest request) {
        Project project = loadOwnedProject(userId, projectId);
        ParameterMeta meta = loadMetaForProject(project, parameterId);
        rejectNotEditable(meta);
        Object value = request == null ? null : request.value();
        if (value == null) {
            if (Boolean.TRUE.equals(meta.definition().getIsRequired())) {
                throw new BadRequestException("Параметр «" + meta.type().getName()
                        + "» - обязательный. Введите значение"
                        + defaultHint(meta) + ".");
            }
            throw new BadRequestException("Значение не передано. Чтобы сбросить параметр, "
                    + "используйте удаление значения (кнопка сброса).");
        }
        ValidatedValue validated = validateManualValue(meta, value);
        upsert(projectId, meta.definition().getId(), validated, ParameterValueSource.MANUAL);
        return toView(meta,
                valueRepository.findByProjectIdAndObjectTypeParameterId(
                        projectId, parameterId).orElse(null));
    }

    /**
 * Сброс значения (DELETE /parameters/{parameterId}): строка удаляется, дефолт снова из метаданных.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param parameterId идентификатор параметра
 */
    public void deleteParameterValue(Long userId, Long projectId, Long parameterId) {
        Project project = loadOwnedProject(userId, projectId);
        ParameterMeta meta = loadMetaForProject(project, parameterId); // 404 для чужого типа
        rejectNotEditable(meta);
        valueRepository.deleteByProjectIdAndObjectTypeParameterId(projectId, parameterId);
    }

    /**
 * Импорт значений из файла по шаблону. См. javadoc класса: валидация
 * всего файла до записи; успех - одна транзакция (вложение + значения).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param file XLSX/CSV по шаблону (multipart)
 * @return итог импорта (счётчики, предупреждения, вложение)
 */
    public ImportResultDto importParameters(Long userId, Long projectId, MultipartFile file) {
        Project project = loadOwnedProject(userId, projectId);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Файл не передан. Выберите XLSX или CSV файл, "
                    + "заполненный по шаблону.");
        }
        if (file.getSize() > storage.maxBytes()) {
            throw new PayloadTooLargeException("Файл больше лимита "
                    + (storage.maxBytes() / 1024 / 1024) + " МБ. Уменьшите файл "
                    + "и повторите загрузку.");
        }
        String mime = allowedMime(file);
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "import.csv" : file.getOriginalFilename();

        Path tmp = storage.saveTemp(file);
        try {
            ParsedFile parsed;
            try {
                parsed = importParser.parse(tmp, fileName);
            } catch (IllegalStateException ex) {
                log.info("Импорт: нечитаемый файл ({}): {}", fileName, ex.toString());
                throw new BadRequestException("Не удалось прочитать файл. Убедитесь, что это "
                        + "Excel (.xlsx, .xls) или CSV, заполненный по шаблону.");
            }
            List<ImportErrorDto> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            List<PendingValue> pending = validateImport(project, parsed, errors, warnings);
            if (!errors.isEmpty()) {
                throw new ImportException("Файл содержит ошибки (" + errors.size()
                        + "). Ничего не сохранено - исправьте перечисленные ячейки "
                        + "и повторите загрузку.", errors);
            }
            return commitImport(projectId, mime, fileName, file.getSize(), tmp,
                    pending, warnings);
        } catch (ImportException | BadRequestException | PayloadTooLargeException ex) {
            storage.deleteQuietly(tmp);
            throw ex;
        } catch (RuntimeException ex) {
            // неожиданный сбой до транзакции (рассинхрон справочника и т.п.) -
            // tmp не должен протекать
            storage.deleteQuietly(tmp);
            throw ex;
        }
    }

    /**
 * Заголовок + первая строка данных: проверка каждой непустой ячейки
 * по метаданным; лишние строки и незаполненные обязательные - в
 * warnings. Все ошибки собираются (до записи в БД -).
 */
    private List<PendingValue> validateImport(Project project, ParsedFile parsed,
                                              List<ImportErrorDto> errors,
                                              List<String> warnings) {
        Map<Long, ParameterMeta> metas = loadMetadata(project.getObjectTypeId());
        Map<String, ParameterMeta> byCode = new HashMap<>();
        for (ParameterMeta meta : metas.values()) {
            byCode.put(meta.type().getCode(), meta);
        }
        Map<Long, ProjectParameterValue> existing = valueRepository
                .findAllByProjectId(project.getId()).stream()
                .collect(Collectors.toMap(ProjectParameterValue::getObjectTypeParameterId,
                        Function.identity()));

        // --- строка 1: заголовок с кодами ---
        Set<String> seenCodes = new HashSet<>();
        List<String> header = parsed.header();
        for (int col = 0; col < header.size(); col++) {
            String code = header.get(col).trim();
            int humanColumn = col + 1;
            if (code.isEmpty()) {
                errors.add(new ImportErrorDto(1, humanColumn, null,
                        "Пустой заголовок колонки. Скачайте шаблон заново и не удаляйте "
                                + "строку с кодами."));
                continue;
            }
            if (!seenCodes.add(code)) {
                errors.add(new ImportErrorDto(1, humanColumn, code,
                        "Колонка с кодом " + code + " повторяется. Оставьте одну."));
                continue;
            }
            if (!byCode.containsKey(code)) {
                errors.add(new ImportErrorDto(1, humanColumn, code,
                        "Параметр с кодом " + code + " не относится к типу объекта этого "
                                + "проекта. Скачайте шаблон для своего типа объекта."));
            }
        }

        // --- первая непустая строка данных ---
        List<String> dataRow = null;
        int dataRowNumber = -1;
        for (int r = 0; r < parsed.dataRows().size(); r++) {
            if (parsed.dataRows().get(r).stream().anyMatch(cell -> !cell.isBlank())) {
                dataRow = parsed.dataRows().get(r);
                dataRowNumber = r + 2; // файловая нумерация с 1 + строка заголовка
                break;
            }
        }
        if (dataRow == null) {
            errors.add(new ImportErrorDto(2, 1, null,
                    "В файле нет строки со значениями. Заполните строку под заголовком "
                            + "и повторите загрузку."));
            return List.of();
        }
        for (int r = 0; r < parsed.dataRows().size(); r++) {
            int fileRow = r + 2; // нумерация файла с 1 + строка заголовка
            if (fileRow <= dataRowNumber) {
                continue; // строки до первой строки данных (пустые) не интересуют
            }
            List<String> row = parsed.dataRows().get(r);
            if (row.stream().anyMatch(cell -> !cell.isBlank())) {
                warnings.add("Строка " + fileRow + ": лишняя строка проигнорирована - "
                        + "импорт берёт только первую строку значений.");
            }
        }

        // --- ячейки первой строки данных ---
        List<PendingValue> pending = new ArrayList<>();
        for (int col = 0; col < header.size(); col++) {
            String raw = col < dataRow.size() ? dataRow.get(col) : "";
            if (raw == null || raw.isBlank()) {
                continue; // пустая ячейка - параметр не трогаем
            }
            String code = header.get(col).trim();
            ParameterMeta meta = byCode.get(code);
            if (meta == null || !seenCodes.contains(code)) {
                continue; // проблема колонки уже зафиксирована в заголовке
            }
            if (Boolean.TRUE.equals(meta.definition().getIsFixed())
                    || Boolean.TRUE.equals(meta.definition().getIsDerived())) {
                // фиксированные/производные не импортируются;
                // актуальный шаблон этих колонок не содержит - старый файл
                // с заполненной ячейкой отвергается целиком (атомарность)
                errors.add(new ImportErrorDto(dataRowNumber, col + 1, code,
                        "Параметр «" + meta.type().getName() + "» "
                                + (Boolean.TRUE.equals(meta.definition().getIsFixed())
                                ? "фиксированный - значение задаётся системой"
                                : "рассчитывается автоматически")
                                + ". Уберите колонку «" + code + "» из файла: актуальный "
                                + "шаблон её не содержит."));
                continue;
            }
            ValidatedValue validated =
                    validateImportValue(meta, raw.trim(), dataRowNumber, col + 1, errors);
            if (validated != null) {
                pending.add(new PendingValue(meta.definition(),
                        validated.numeric(), validated.text(), validated.bool()));
            }
        }

        // --- обязательные без значения после импорта ---
        for (ParameterMeta meta : metas.values()) {
            if (Boolean.TRUE.equals(meta.definition().getIsRequired())
                    && !Boolean.TRUE.equals(meta.definition().getIsFixed())
                    && !Boolean.TRUE.equals(meta.definition().getIsDerived())) {
                boolean hasNew = pending.stream()
                        .anyMatch(p -> p.definition().getId().equals(meta.definition().getId()));
                boolean hasOld = existing.containsKey(meta.definition().getId());
                if (!hasNew && !hasOld) {
                    warnings.add("Обязательный параметр «" + meta.type().getName()
                            + "» остался без значения - без него расчёт не запустится.");
                }
            }
        }
        return pending;
    }

    /**
 * Единственная транзакция импорта: строка вложения (id для имени
 * файла), перенос файла из tmp, UPSERT всех значений source='import'.
 * Ответственность очистки РАЗДЕЛЕНА - commitImport при сбое
 * удаляет только ПЕРЕНЕСЁННЫЙ файл (tmp ему уже не принадлежит),
 * временный файл убирает внешний catch importParameters в любом
 * исходе - каждый файл удаляется ровно один раз, без двойных вызовов.
 * Нарушение UNIQUE при параллельном импорте/PUT - 409 с понятным
 * текстом (повтор безопасен: значения идемпотентны), прочее -
 * исходное исключение наружу (500 без деталей).
 */
    private ImportResultDto commitImport(Long projectId, String mime, String fileName,
                                         long sizeBytes, Path tmp,
                                         List<PendingValue> pending, List<String> warnings) {
        java.util.concurrent.atomic.AtomicReference<Path> promoted =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            ImportResultDto result = transactionTemplate.execute(tx -> {
                ProjectAttachment attachment = attachmentRepository.save(
                        ProjectAttachment.builder()
                                .projectId(projectId)
                                .fileName(fileName)
                                .filePath("tmp") // заменяется сразу после promote
                                .mimeType(mime)
                                .sizeBytes(sizeBytes)
                                .uploadedAt(Instant.now())
                                .build());
                String relativePath = storage.promote(tmp, projectId, attachment.getId(),
                        fileName);
                promoted.set(storage.resolve(relativePath));
                attachment.setFilePath(relativePath);
                attachmentRepository.save(attachment);

                Map<Long, ProjectParameterValue> existing = valueRepository
                        .findAllByProjectId(projectId).stream()
                        .collect(Collectors.toMap(
                                ProjectParameterValue::getObjectTypeParameterId,
                                Function.identity()));
                List<ProjectParameterValue> toSave = new ArrayList<>();
                for (PendingValue value : pending) {
                    ProjectParameterValue row = existing.get(
                            value.definition().getId());
                    if (row == null) {
                        row = ProjectParameterValue.builder()
                                .projectId(projectId)
                                .objectTypeParameterId(value.definition().getId())
                                .build();
                    }
                    applyValue(row, new ValidatedValue(value.numeric(), value.text(),
                            value.bool()));
                    row.setSource(ParameterValueSource.IMPORT);
                    row.setUpdatedAt(Instant.now());
                    toSave.add(row);
                }
                valueRepository.saveAll(toSave);
                return ImportResultDto.builder()
                        .importedCount(toSave.size())
                        .attachmentId(attachment.getId())
                        .warnings(warnings)
                        .build();
            });
            return result;
        } catch (DataIntegrityViolationException ex) {
            // гонка с параллельной записью значений - откат + компенсация
            storage.deleteQuietly(promoted.get());
            log.warn("Импорт: гонка с параллельной записью значений, изменения "
                    + "отменены: {}", ex.toString());
            throw new ConflictException("Параллельное изменение параметров этого проекта "
                    + "(другая загрузка или ввод). Повторите загрузку - ничего не потеряно.");
        } catch (RuntimeException ex) {
            storage.deleteQuietly(promoted.get());
            log.error("Импорт: ошибка записи в БД, изменения отменены: {}", ex.toString());
            throw ex;
        }
    }

    /**
 * История загрузок: файл, размер, дата.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return вложения проекта свежими сверху
 */
    @Transactional(readOnly = true)
    public List<AttachmentDto> listAttachments(Long userId, Long projectId) {
        loadOwnedProject(userId, projectId);
        return attachmentRepository.findAllByProjectIdOrderByUploadedAtDesc(projectId).stream()
                .map(attachment -> AttachmentDto.builder()
                        .id(attachment.getId())
                        .fileName(attachment.getFileName())
                        .mimeType(attachment.getMimeType())
                        .sizeBytes(attachment.getSizeBytes())
                        .uploadedAt(attachment.getUploadedAt())
                        .build())
                .toList();
    }

    /**
 * Удаление вложения: строка БД в транзакции, файл - вне (ошибка
 * файловой системы логируется, но не ломает ответ; -
 * «удаление вместе с файлами», компенсация - см. AttachmentStorage).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param attachmentId идентификатор вложения
 */
    public void deleteAttachment(Long userId, Long projectId, Long attachmentId) {
        loadOwnedProject(userId, projectId);
        ProjectAttachment attachment = attachmentRepository
                .findByProjectIdAndId(projectId, attachmentId)
                .orElseThrow(() -> new NotFoundException("Вложение не найдено"));
        transactionTemplate.executeWithoutResult(
                tx -> attachmentRepository.delete(attachment));
        storage.deleteQuietly(storage.resolve(attachment.getFilePath()));
    }

    /**
 * Шаблон для проекта: колонки-коды параметров его типа объекта.
 * xlsx - два листа (Значения + Параметры с единицами, диапазонами,
 * дефолтами и источником норматива -), csv - строка кодов.
 * Формат - «шаблон wide» (см. ImportParser).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param format xlsx (по умолчанию) или csv
 * @return готовый файл шаблона (имя, MIME, байты)
 */
    @Transactional(readOnly = true)
    public ParameterTemplateService.TemplateFile buildTemplate(Long userId, Long projectId,
                                                               String format) {
        Project project = loadOwnedProject(userId, projectId);
        Map<Long, ParameterMeta> metas = loadMetadata(project.getObjectTypeId());
        ObjectType type = objectTypeRepository.findById(project.getObjectTypeId())
                .orElse(null);
        String typeCode = type == null ? "object" : type.getCode();
        // фиксированные и производные параметры не импортируются:
        // их нет ни в строке кодов CSV, ни в листах xlsx-шаблона
        List<ParameterTemplateService.Meta> rows = metas.values().stream()
                .filter(meta -> !Boolean.TRUE.equals(meta.definition().getIsFixed())
                        && !Boolean.TRUE.equals(meta.definition().getIsDerived()))
                .map(meta -> new ParameterTemplateService.Meta(
                        meta.type().getCode(),
                        meta.type().getName(),
                        meta.type().getUnit(),
                        Boolean.TRUE.equals(meta.definition().getIsRequired()),
                        meta.definition().getMinValue(),
                        meta.definition().getMaxValue(),
                        defaultHintValue(meta),
                        meta.definition().getSourceNote()))
                .toList();
        return "csv".equalsIgnoreCase(format)
                ? ParameterTemplateService.csv(typeCode, rows)
                : ParameterTemplateService.xlsx(typeCode, rows);
    }

    /**
 * Валидация значения из формы (типизированный JSON).
 */
    private ValidatedValue validateManualValue(ParameterMeta meta, Object value) {
        String name = meta.type().getName();
        return switch (meta.type().getValueType()) {
            case NUMBER -> {
                if (!(value instanceof Number number)) {
                    throw new BadRequestException("Параметр «" + name + "» - числовое. "
                            + "Введите число" + rangeHint(meta) + ".");
                }
                BigDecimal decimal = toBigDecimal(number);
                checkRange(meta, decimal);
                yield new ValidatedValue(decimal, null, null);
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean bool)) {
                    throw new BadRequestException("Параметр «" + name + "» - логическое. "
                            + "Передайте true или false (в форме - «да» или «нет»).");
                }
                yield new ValidatedValue(null, null, bool);
            }
            case TEXT -> {
                if (!(value instanceof String text) || text.trim().isEmpty()) {
                    throw new BadRequestException("Параметр «" + name + "» - текстовый. "
                            + "Введите непустой текст.");
                }
                yield new ValidatedValue(null, text.trim(), null);
            }
        };
    }

    // ------------------------------------------------------------------
    // UPSERT
    // ------------------------------------------------------------------

    /**
 * Валидация ячейки импорта (строка); ошибка - в общий список, не исключение.
 */
    private ValidatedValue validateImportValue(ParameterMeta meta, String raw, int row,
                                               int column, List<ImportErrorDto> errors) {
        String code = meta.type().getCode();
        String name = meta.type().getName();
        return switch (meta.type().getValueType()) {
            case NUMBER -> {
                BigDecimal decimal = parseNumber(raw);
                if (decimal == null) {
                    errors.add(new ImportErrorDto(row, column, code,
                            "Параметр «" + name + "» - числовое. Введите число"
                                    + rangeHint(meta) + "."));
                    yield null;
                }
                if (!inRange(meta, decimal)) {
                    errors.add(new ImportErrorDto(row, column, code, rangeMessage(meta)));
                    yield null;
                }
                yield new ValidatedValue(decimal, null, null);
            }
            case BOOLEAN -> {
                Boolean bool = parseBoolean(raw);
                if (bool == null) {
                    errors.add(new ImportErrorDto(row, column, code,
                            "Параметр «" + name + "» - логическое. Введите «да» или «нет»."));
                    yield null;
                }
                yield new ValidatedValue(null, null, bool);
            }
            case TEXT -> {
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) {
                    errors.add(new ImportErrorDto(row, column, code,
                            "Параметр «" + name + "» - текстовый. Введите непустой текст."));
                    yield null;
                }
                yield new ValidatedValue(null, trimmed, null);
            }
        };
    }

    /**
 * Однострочный UPSERT без внешней транзакции (см. javadoc класса).
 * Гонка двух INSERT ловится UNIQUE и повторяется как UPDATE.
 */
    private void upsert(Long projectId, Long parameterId, ValidatedValue value,
                        ParameterValueSource source) {
        Optional<ProjectParameterValue> existing = valueRepository
                .findByProjectIdAndObjectTypeParameterId(projectId, parameterId);
        if (existing.isPresent()) {
            ProjectParameterValue row = existing.get();
            applyValue(row, value);
            row.setSource(source);
            row.setUpdatedAt(Instant.now());
            valueRepository.save(row);
            return;
        }
        ProjectParameterValue row = ProjectParameterValue.builder()
                .projectId(projectId)
                .objectTypeParameterId(parameterId)
                .build();
        applyValue(row, value);
        row.setSource(source);
        row.setUpdatedAt(Instant.now());
        try {
            valueRepository.save(row);
        } catch (DataIntegrityViolationException ex) {
            // конкурент успел вставить то же значение - обновляем его строку
            ProjectParameterValue winner = valueRepository
                    .findByProjectIdAndObjectTypeParameterId(projectId, parameterId)
                    .orElseThrow(() -> ex);
            applyValue(winner, value);
            winner.setSource(source);
            winner.setUpdatedAt(Instant.now());
            valueRepository.save(winner);
        }
    }

    // ------------------------------------------------------------------
    // Сборка DTO
    // ------------------------------------------------------------------

    /**
 * Метаданные параметров типа объекта: id определения -> (определение + тип).
 */
    private Map<Long, ParameterMeta> loadMetadata(Long objectTypeId) {
        List<ObjectTypeParameter> definitions = objectTypeParameterRepository
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(objectTypeId);
        if (definitions.isEmpty()) {
            return Map.of();
        }
        Map<Long, ParameterType> types = parameterTypeRepository
                .findAllById(definitions.stream()
                        .map(ObjectTypeParameter::getParameterTypeId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(ParameterType::getId, Function.identity()));
        Map<Long, ParameterMeta> metas = new LinkedHashMap<>();
        for (ObjectTypeParameter definition : definitions) {
            ParameterType type = types.get(definition.getParameterTypeId());
            if (type == null) {
                // FK в V1 гарантирует наличие; защита от рассинхрона справочника
                throw new IllegalStateException(
                        "Нет справочника parameter_type для параметра id="
                                + definition.getId());
            }
            metas.put(definition.getId(), new ParameterMeta(definition, type));
        }
        return metas;
    }

    /**
 * Определение параметра, обязательно принадлежащее типу объекта проекта.
 */
    private ParameterMeta loadMetaForProject(Project project, Long parameterId) {
        ObjectTypeParameter definition = objectTypeParameterRepository.findById(parameterId)
                .orElseThrow(() -> new NotFoundException(
                        "Параметр не найден для этого проекта"));
        if (!Objects.equals(definition.getObjectTypeId(), project.getObjectTypeId())) {
            // параметр другого типа объекта (аэропорт против склада) -
            // единый ответ с «не найден», без раскрытия чужих справочников
            throw new NotFoundException("Параметр не найден для этого проекта");
        }
        ParameterType type = parameterTypeRepository
                .findById(definition.getParameterTypeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Нет справочника parameter_type для параметра id=" + parameterId));
        return new ParameterMeta(definition, type);
    }

    /**
 * Проект с проверкой владельца; чужой/несуществующий - 404 (как ProjectService).
 */
    private Project loadOwnedProject(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new NotFoundException("Проект не найден");
        }
        return project;
    }

    // ------------------------------------------------------------------
    // Загрузка метаданных и проекта
    // ------------------------------------------------------------------

    /**
 * Формула производного параметра: код источника и множитель.
 */
    private record DerivedSpec(String sourceCode, BigDecimal factor) {
    }

    /**
 * Метаданные одного параметра: определение + тип (имя, единица, value_type).
 */
    private record ParameterMeta(ObjectTypeParameter definition, ParameterType type) {
    }

    /**
 * Проверенное значение импорта, готовое к записи.
 */
    private record PendingValue(ObjectTypeParameter definition,
                                BigDecimal numeric, String text, Boolean bool) {
    }

    /**
 * Нормализованное значение: ровно одна колонка заполнена (EAV).
 */
    private record ValidatedValue(BigDecimal numeric, String text, Boolean bool) {
    }
}
