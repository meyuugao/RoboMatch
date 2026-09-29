package me.yuugao.robomatch.export;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.ExportDto;
import me.yuugao.robomatch.economics.EconomicCalculationService;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.EconomicValidationException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;
import me.yuugao.robomatch.selection.ScenarioService;
import me.yuugao.robomatch.service.ExportStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;

/**
 * Сервис экспорта: оркестратор
 * «вход → проверки → сборка модели → генерация → файл + строка
 * истории». Модель оркестрации - SimulationService.
 *
 * <p>ПОТОК: {projectId, format} → проверка владельца (404) → гейт склада
 * is_calc_enabled (400, как у экономики/имитации) → автозапуск
 * недостающих/устаревших расчётов (неудача НЕ блокирует отчёт -
 * причина печатается в разделе экономики) → сборка ReportModel
 * (ReportDataBuilder - отчёт читает ГОТОВЫЕ метрики, не пересчитывает;
 * 2D-схема обязательна: автозапуск имитации + серверный рендер при
 * отсутствии сохранённой) → генерация файлов выбранным генератором →
 * запись в ExportStorage (data/exports/{projectId}/{exportId}.{ext})
 * + строка export → DTO.
 *
 * <p>ПАРАЛЛЕЛЬНЫЙ ЭКСПОРТ: повторная генерация того же формата того же
 * проекта, пока предыдущая выполняется, - 409 (in-flight-замок на
 * (projectId, format); генерация синхронна и занимает секунды).
 * Разные форматы параллельно - можно.
 *
 * <p>СОГЛАСОВАННОСТЬ ФАЙЛА И СТРОКИ: строка export пишется после
 * успешной записи файла (id нужен для пути - двухшаговая вставка:
 * сначала строка-заготовка с транзакцией, затем файл, затем обновление
 * file_path). Отказ файловой системы - компенсация: строка удаляется,
 * пользователь получает понятную 500 без осиротевших записей.
 *
 * <p>ИЗОЛЯЦИЯ: userId из JWT, чужой проект/выгрузка - 404,
 * не 403 (как у проектов/экономики/имитации).
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final Logger log =
            LoggerFactory.getLogger(ExportService.class);

    private final ProjectRepository projectRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final UserRepository userRepository;
    private final ScenarioRepository scenarioRepository;
    private final CalculationRepository calculationRepository;
    private final ScenarioService scenarioService;
    private final EconomicCalculationService economicCalculationService;
    private final ReportDataBuilder reportDataBuilder;
    private final PdfReportGenerator pdfReportGenerator;
    private final ExcelReportGenerator excelReportGenerator;
    private final CsvReportGenerator csvReportGenerator;
    private final ExportStorage exportStorage;
    private final ExportRepository exportRepository;

    /**
 * In-flight-замок: (projectId:format) → занят.
 */
    private final ConcurrentHashMap<String, AtomicBoolean> inFlight =
            new ConcurrentHashMap<>();

    // ==================================================================
    // Генерация
    // ==================================================================

    /**
 * Нормализация темы схемы: null/мусор -> «ui» (как в интерфейсе).
 */
    private static String normalizeSchemaTheme(String schemaTheme) {
        String value = schemaTheme == null ? ""
                : schemaTheme.trim().toLowerCase(Locale.ROOT);
        return "light".equals(value) || "dark".equals(value) ? value : "ui";
    }

    /**
 * Имя файла: без разделителей путей и управляющих символов;
 * пробелы схлопываются («:» заменялся на
 * пробел и оставлял двойные пробелы в имени).
 */
    private static String sanitizeFileName(String name) {
        String clean = name == null ? "project"
                : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            return "project";
        }
        return clean.length() <= 60 ? clean : clean.substring(0, 60).trim();
    }

    /**
 * Сгенерировать отчёт в светлой теме (совместимость вызовов).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param format pdf/xlsx/csv
 * @return DTO созданной выгрузки
 */
    public ExportDto create(Long userId, Long projectId, String format) {
        return create(userId, projectId, format, null, null);
    }

    /**
 * Сгенерировать отчёт; 404 чужой, 400 не-склад/нет расчётов,
 * 409 параллельный экспорт того же формата. Тема:
 * «dark» - тёмные страницы PDF, null/«light» - светлые; Excel/CSV
 * без темы (машиночитаемые форматы).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param format pdf/xlsx/csv
 * @param theme тема PDF («dark»/«light»/null)
 * @param schemaTheme тема 2D-схемы в отчёте («ui»/«light»/«dark»/
 * null - как сохранена в интерфейсе)
 * @return DTO созданной выгрузки
 */
    public ExportDto create(Long userId, Long projectId, String format,
                            String theme, String schemaTheme) {
        Project project = requireOwnedProject(userId, projectId);
        ObjectType objectType = requireWarehouse(project);
        // расчёты не требуются заранее: перед отчётом досчитываем
        // недостающие/устаревшие сценарии, неудачи печатаются в отчёте
        Map<Long, String> calcFailures = ensureFreshCalculations(userId,
                project);
        String normalized = format == null ? ""
                : format.trim().toLowerCase(Locale.ROOT);
        boolean dark = "dark".equals(theme == null ? ""
                : theme.trim().toLowerCase(Locale.ROOT));

        // параллельная генерация того же формата - 409 (гонка кнопок)
        String lockKey = project.getId() + ":" + normalized;
        AtomicBoolean lock = inFlight.computeIfAbsent(lockKey,
                key -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            throw new ConflictException("Отчёт «" + normalized
                    + "» уже генерируется - дождитесь завершения");
        }
        try {
            ReportModel model = reportDataBuilder.build(project, objectType,
                    userId, dark, calcFailures, normalizeSchemaTheme(
                            schemaTheme));
            byte[] content = generate(normalized, model, dark);
            return persist(project, userId, normalized, content,
                    model.projectName());
        } finally {
            lock.set(false);
            inFlight.remove(lockKey, lock);
        }
    }

    /**
 * История выгрузок проекта (свежие сверху; изоляция 404).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return выгрузки проекта свежими сверху
 */
    @Transactional(readOnly = true)
    public List<ExportDto> history(Long userId, Long projectId) {
        requireOwnedProject(userId, projectId);
        return exportRepository
                .findAllByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .stream()
                .map(row -> toView(row, projectNameOf(projectId)))
                .toList();
    }

    /**
 * Имя проекта (для имени файла скачивания; с проверкой владельца).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return имя проекта
 */
    @Transactional(readOnly = true)
    public String projectName(Long userId, Long projectId) {
        return requireOwnedProject(userId, projectId).getName();
    }

    /**
 * Выгрузка по id (скачивание/удаление; изоляция 404).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param exportId идентификатор выгрузки
 * @return строка истории экспорта (сущность Export)
 */
    @Transactional(readOnly = true)
    public Export requireOwnedExport(Long userId, Long projectId,
                                     Long exportId) {
        return exportRepository
                .findByIdAndProjectIdAndUserId(exportId, projectId, userId)
                .orElseThrow(() -> new NotFoundException(
                        "Выгрузка не найдена"));
    }

    /**
 * Удалить выгрузку: файл + строка (204 вызывающего).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param exportId идентификатор выгрузки
 */
    public void delete(Long userId, Long projectId, Long exportId) {
        Export row = requireOwnedExport(userId, projectId, exportId);
        exportRepository.delete(row);
        exportStorage.delete(row.getFilePath());
    }

    /**
 * Байты файла для скачивания; 404 - файл потерян ИЛИ битый путь
 * в БД (защита в глубину - не 500).
 *
 * @param row строка истории экспорта (сущность Export)
 * @return содержимое файла выгрузки
 */
    public byte[] contentOf(Export row) {
        byte[] content;
        try {
            content = exportStorage.read(row.getFilePath());
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("Файл выгрузки не найден - "
                    + "повторите генерацию");
        }
        if (content == null) {
            throw new NotFoundException(
                    "Файл выгрузки не найден в хранилище - повторите "
                            + "генерацию");
        }
        return content;
    }

    // ==================================================================
    // Внутренние
    // ==================================================================

    /**
 * Имя файла для Content-Disposition (кириллица - filename*).
 *
 * @param row строка истории экспорта (формат и дата создания)
 * @param projectName имя проекта для имени файла
 * @return имя файла вида RoboMatch_проект_штамп.ext
 */
    public String fileNameOf(Export row, String projectName) {
        String stamp = ReportFormats.fileStamp(row.getCreatedAt());
        return "RoboMatch_" + sanitizeFileName(projectName) + "_"
                + stamp + "." + row.getFormat();
    }

    /**
 * Генерация выбранного формата; тема - только для PDF.
 */
    private byte[] generate(String format, ReportModel model, boolean dark) {
        return switch (format) {
            case "pdf" -> pdfReportGenerator.generate(model, dark);
            case "xlsx" -> excelReportGenerator.generate(model);
            case "csv" -> csvReportGenerator.generate(model);
            default -> throw new BadRequestException(
                    "Формат отчёта - pdf, xlsx или csv");
        };
    }

    /**
 * Двухшаговая запись: id нужен для пути файла, file_path NOT NULL -
 * сначала заготовка (каждый save репозитория - своя транзакция),
 * затем файл, затем обновление пути. Ошибка записи - компенсирующее
 * удаление строки (осиротевших записей не остаётся).
 */
    private ExportDto persist(Project project, Long userId, String format,
                              byte[] content, String projectName) {
        Export row = exportRepository.save(Export.builder()
                .projectId(project.getId())
                .format(format)
                .filePath("pending")
                .createdByUserId(userId)
                .build());
        try {
            String relative = exportStorage.save(project.getId(), row.getId(),
                    format, content);
            row.setFilePath(relative);
            Export saved = exportRepository.save(row);
            log.info("Экспорт проекта {}: формат {}, {} байт, пользователь {}",
                    project.getId(), format, content.length, userId);
            return toView(saved, projectName);
        } catch (RuntimeException ex) {
            // компенсация: строки без файла не оставляем; исходная ошибка
            // (UncheckedIOException/лимит) уходит пользователю как есть
            exportRepository.delete(row);
            throw ex;
        }
    }

    private ExportDto toView(Export row, String projectName) {
        User author = userRepository.findById(row.getCreatedByUserId())
                .orElse(null);
        long size = exportStorage.sizeOf(row.getFilePath());
        return new ExportDto(
                row.getId(),
                row.getFormat(),
                fileNameOf(row, projectName),
                size < 0 ? 0 : size,
                row.getCreatedAt(),
                author == null ? "-" : author.getLogin(),
                "/api/projects/" + row.getProjectId() + "/exports/"
                        + row.getId());
    }

    private String projectNameOf(Long projectId) {
        return projectRepository.findById(projectId)
                .map(Project::getName).orElse("-");
    }

    private Project requireOwnedProject(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new NotFoundException("Проект не найден");
        }
        return project;
    }

    /**
 * Гейт склада - как у экономики и имитации (is_calc_enabled).
 */
    private ObjectType requireWarehouse(Project project) {
        ObjectType objectType = objectTypeRepository
                .findById(project.getObjectTypeId())
                .orElseThrow(() -> new NotFoundException(
                        "Тип объекта не найден"));
        if (!Boolean.TRUE.equals(objectType.getIsCalcEnabled())) {
            throw new BadRequestException("Экспорт доступен только для "
                    + "склада - тип объекта «" + objectType.getName()
                    + "» не поддерживается.");
        }
        return objectType;
    }

    /**
 * Перед отчётом досчитать сценарии (последовательно base →
 * purchase → raas): нет расчёта или состав изменился с момента
 * последнего - запуск. Неудавшийся расчёт НЕ блокирует отчёт:
 * причина (недостающие параметры и т.п.) печатается в разделе
 * экономики «Расчёт не выполнен: …».
 *
 * @param userId владелец (изоляция расчёта)
 * @param project проект (владелец проверен)
 * @return сценарийId → причина неудачи (пусто - всё рассчитано)
 */
    private Map<Long, String> ensureFreshCalculations(Long userId,
                                                      Project project) {
        Map<Long, String> failures = new LinkedHashMap<>();
        for (Scenario scenario : scenarioRepository
                .findAllByProjectIdOrderByIdAsc(project.getId())) {
            Calculation latest = calculationRepository
                    .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                            scenario.getId()).orElse(null);
            boolean stale = latest == null || scenarioService
                    .compositionChanged(scenario.getId(), latest);
            if (!stale) {
                continue;
            }
            try {
                economicCalculationService.calculate(userId,
                        project.getId(), scenario.getId());
            } catch (BadRequestException | EconomicValidationException ex) {
                failures.put(scenario.getId(), ex.getMessage());
            }
        }
        return failures;
    }
}
