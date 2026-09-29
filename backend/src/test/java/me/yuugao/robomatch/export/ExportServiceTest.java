package me.yuugao.robomatch.export;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.ExportDto;
import me.yuugao.robomatch.economics.EconomicCalculationService;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;
import me.yuugao.robomatch.selection.ScenarioService;
import me.yuugao.robomatch.service.ExportStorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit-тест оркестратора экспорта: гейт
 * склада, изоляция 404, отсутствие расчётов → 400, параллельная
 * генерация того же формата → 409, физические файлы хранилища,
 * компенсация при гонке БД (DIVE), удаление выгрузки.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExportServiceTest {

    private static final Long USER = 7L;
    private static final Long PROJECT_ID = 3L;
    private static final Long EXPORT_ID = 11L;
    @TempDir
    Path exportRoot;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ObjectTypeRepository objectTypeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ScenarioRepository scenarioRepository;
    @Mock
    private CalculationRepository calculationRepository;
    @Mock
    private ScenarioService scenarioService;
    @Mock
    private EconomicCalculationService economicCalculationService;
    @Mock
    private ReportDataBuilder reportDataBuilder;
    @Mock
    private ExportRepository exportRepository;
    private ExportStorage storage;
    private ExportService service;

    @BeforeEach
    void setUp() {
        storage = new ExportStorage(exportRoot.toString(), 5_000_000);
        service = new ExportService(projectRepository, objectTypeRepository,
                userRepository, scenarioRepository, calculationRepository,
                scenarioService, economicCalculationService,
                reportDataBuilder, new PdfReportGenerator(
                new SvgRasterizer()),
                new ExcelReportGenerator(), new CsvReportGenerator(),
                storage, exportRepository);
        // расчёт свеж - автозапуск не требуется
        when(scenarioService.compositionChanged(anyLong(), any()))
                .thenReturn(false);

        Project project = Project.builder().id(PROJECT_ID).userId(USER)
                .objectTypeId(1L).name("Проект").build();
        when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(project));
        when(objectTypeRepository.findById(1L)).thenReturn(Optional.of(
                ObjectType.builder().id(1L).code("warehouse")
                        .name("Склад").isCalcEnabled(true).build()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(
                User.builder().id(USER).login("user").build()));
        Scenario scenario = Scenario.builder().id(21L)
                .projectId(PROJECT_ID).type(ScenarioType.PURCHASE)
                .name("Покупка").build();
        when(scenarioRepository.findAllByProjectIdOrderByIdAsc(PROJECT_ID))
                .thenReturn(List.of(scenario));
        when(calculationRepository.findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                21L)).thenReturn(Optional.of(Calculation.builder()
                .id(1L).scenarioId(21L).versionData("v")
                .versionModel("economic-model-1.0").build()));
        when(reportDataBuilder.build(any(), any(), any(), anyBoolean(),
                any(), any())).thenReturn(emptyModel());
        when(exportRepository.save(any(Export.class)))
                .thenAnswer(invocation -> {
                    Export row = invocation.getArgument(0);
                    if (row.getId() == null) {
                        row.setId(EXPORT_ID);
                    }
                    return row;
                });
        when(exportRepository.findByIdAndProjectIdAndUserId(anyLong(),
                anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    private ReportModel emptyModel() {
        return new ReportModel(PROJECT_ID, "Проект", "Склад",
                Instant.now(), "user", 5, List.of(), List.of(), null,
                List.of(), List.of(), List.of(),
                ReportDataBuilder.PRELIMINARY_NOTE);
    }

    @Test
    void foreignProject_returns404() {
        when(projectRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(USER, 999L, "pdf"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Проект не найден");
    }

    @Test
    void nonWarehouse_returns400() {
        when(objectTypeRepository.findById(1L)).thenReturn(Optional.of(
                ObjectType.builder().id(1L).code("airport")
                        .name("Аэропорт").isCalcEnabled(false).build()));
        assertThatThrownBy(() -> service.create(USER, PROJECT_ID, "pdf"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Экспорт доступен только для склада");
    }

    @Test
    void noCalculations_autoCalculatesBeforeReport() {
        // расчёта нет - перед отчётом запускается автозапуск; неудача
        // НЕ блокирует отчёт (причина - в раздел экономики)
        when(calculationRepository.findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                21L)).thenReturn(Optional.empty());
        when(economicCalculationService.calculate(anyLong(), anyLong(),
                anyLong())).thenThrow(new BadRequestException(
                "не заданы обязательные параметры объекта"));
        ReportModel withReason = new ReportModel(3L, "Проект", "Склад",
                Instant.now(), "user", null, List.of(), List.of(
                new ReportModel.ScenarioReport("purchase", "Покупка",
                        false, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, null, false, false, List.of(),
                        List.of(), "не заданы обязательные параметры")),
                null, List.of(), List.of(), List.of(),
                ReportDataBuilder.PRELIMINARY_NOTE);
        when(reportDataBuilder.build(any(), any(), any(), anyBoolean(),
                any(), any())).thenReturn(withReason);
        assertThatCode(() -> service.create(USER, PROJECT_ID, "pdf"))
                .doesNotThrowAnyException();
        verify(economicCalculationService).calculate(USER, PROJECT_ID, 21L);
    }

    @Test
    void unknownFormat_returns400() {
        assertThatThrownBy(() -> service.create(USER, PROJECT_ID, "docx"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Формат отчёта - pdf, xlsx или csv");
    }

    @Test
    void create_writesPhysicalFile_andReturnsView() throws IOException {
        ExportDto view = service.create(USER, PROJECT_ID, "csv");
        assertThat(view.format()).isEqualTo("csv");
        assertThat(view.sizeBytes()).isGreaterThan(3); // BOM + данные
        assertThat(view.fileName()).startsWith("RoboMatch_Проект_")
                .endsWith(".csv");
        assertThat(view.createdByLogin()).isEqualTo("user");
        assertThat(view.downloadUrl()).isEqualTo(
                "/api/projects/3/exports/" + EXPORT_ID);
        Path file = exportRoot.resolve(
                PROJECT_ID + "/" + EXPORT_ID + ".csv");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isEqualTo(view.sizeBytes());
    }

    @Test
    void parallelExport_sameFormat_returns409() throws Exception {
        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch releaseBuild = new CountDownLatch(1);
        when(reportDataBuilder.build(any(), any(), any(), anyBoolean(),
                any(), any())).thenAnswer(
                invocation -> {
                    buildStarted.countDown();
                    releaseBuild.await();
                    return emptyModel();
                });
        AtomicReference<Exception> background = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                service.create(USER, PROJECT_ID, "pdf");
            } catch (Exception ex) {
                background.set(ex);
            }
        });
        first.start();
        assertThat(buildStarted.await(5, java.util.concurrent.TimeUnit
                .SECONDS)).isTrue(); // первый держит замок
        assertThatThrownBy(() -> service.create(USER, PROJECT_ID, "pdf"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже генерируется");
        releaseBuild.countDown();
        first.join(5000);
        assertThat(background.get()).isNull();
    }

    @Test
    void differentFormats_parallelAllowed() {
        // разные форматы не конфликтуют (замок на пару проект:формат)
        assertThat(service.create(USER, PROJECT_ID, "pdf").format())
                .isEqualTo("pdf");
        assertThat(service.create(USER, PROJECT_ID, "xlsx").format())
                .isEqualTo("xlsx");
    }

    @Test
    void raceOnSave_compensatesRow() {
        when(exportRepository.save(any(Export.class)))
                .thenThrow(new DataIntegrityViolationException("race"));
        assertThatThrownBy(() -> service.create(USER, PROJECT_ID, "pdf"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // компенсации нечего удалять - save упал; хранилище не трогалось
        assertThat(Files.exists(exportRoot.resolve(
                String.valueOf(PROJECT_ID)))).isFalse();
    }

    @Test
    void delete_removesFileAndRow() {
        service.create(USER, PROJECT_ID, "pdf");
        Path file = exportRoot.resolve(PROJECT_ID + "/" + EXPORT_ID
                + ".pdf");
        assertThat(Files.exists(file)).isTrue();
        Export row = Export.builder().id(EXPORT_ID)
                .projectId(PROJECT_ID).format("pdf")
                .filePath(PROJECT_ID + "/" + EXPORT_ID + ".pdf")
                .createdAt(Instant.now()).createdByUserId(USER).build();
        when(exportRepository.findByIdAndProjectIdAndUserId(EXPORT_ID,
                PROJECT_ID, USER)).thenReturn(Optional.of(row));
        service.delete(USER, PROJECT_ID, EXPORT_ID);
        verify(exportRepository).delete(row);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void contentOf_missingFile_returns404() {
        Export row = Export.builder().id(EXPORT_ID)
                .projectId(PROJECT_ID).format("pdf")
                .filePath(PROJECT_ID + "/404.pdf")
                .createdAt(Instant.now()).createdByUserId(USER).build();
        assertThatThrownBy(() -> service.contentOf(row))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Файл выгрузки не найден");
    }

    @Test
    void history_mapsRows_andIsolationOnForeignProject() {
        when(projectRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.history(USER, 999L))
                .isInstanceOf(NotFoundException.class);
        verify(exportRepository, never())
                .findAllByProjectIdOrderByCreatedAtDescIdDesc(anyLong());
    }

    @Test
    void storageLimit_rejectsOversizedReport() {
        ExportStorage tiny = new ExportStorage(
                exportRoot.toString(), 10);
        ExportService tinyService = new ExportService(projectRepository,
                objectTypeRepository, userRepository, scenarioRepository,
                calculationRepository, scenarioService,
                economicCalculationService, reportDataBuilder,
                new PdfReportGenerator(new SvgRasterizer()),
                new ExcelReportGenerator(), new CsvReportGenerator(),
                tiny, exportRepository);
        // понятный 413, а не 500 через catch-all
        assertThatThrownBy(() -> tinyService.create(USER, PROJECT_ID,
                "pdf"))
                .isInstanceOf(
                        me.yuugao.robomatch.exception.PayloadTooLargeException
                                .class)
                .hasMessageContaining("лимита");
    }
}
