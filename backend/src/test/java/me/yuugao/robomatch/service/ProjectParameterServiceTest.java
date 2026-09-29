package me.yuugao.robomatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.ImportResultDto;
import me.yuugao.robomatch.dto.ParameterDto;
import me.yuugao.robomatch.dto.ParameterSetRequest;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ImportException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.exception.PayloadTooLargeException;
import me.yuugao.robomatch.repository.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unit-тест ProjectParameterService (
 * 3.2.2–3.2.5, 4.3.4): валидация по метаданным, UPSERT, атомарность
 * импорта (ошибка в файле - никаких записей в БД), вложения.
 * <p>
 * ImportParser и файлы - НАСТОЯЩИЕ (парсер - чистая функция, файлы - во
 * временном каталоге): покрывают реальный разбор CSV. Репозитории,
 * хранилище и TransactionTemplate - моки; транзакция прогоняется сразу.
 * Strictness.LENIENT: общая фикстура stubImport поднимает стабы для
 * разных сценариев, и не все используются каждым тестом.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectParameterServiceTest {

    private static final Long ALICE = 1L;
    private static final Long BOB = 2L;
    private static final Long PROJECT_ID = 5L;
    private static final Long WAREHOUSE_TYPE = 10L;
    @Spy
    private final ImportParser importParser = new ImportParser();
    private final ParameterType area = pt(1001L, "total_warehouse_area",
            "Общая площадь склада", "кв. м", ParameterValueType.NUMBER);
    private final ParameterType shifts = pt(1002L, "shifts_per_day",
            "Смен в сутки", "шт", ParameterValueType.NUMBER);
    private final ParameterType wms = pt(1003L, "has_wms",
            "Наличие WMS", null, ParameterValueType.BOOLEAN);
    private final ParameterType racking = pt(1004L, "racking_type",
            "Тип стеллажной системы", null, ParameterValueType.TEXT);
    private final ObjectTypeParameter otpArea =
            otp(101L, 1001L, true, new BigDecimal("1"), new BigDecimal("1000000"));
    private final ObjectTypeParameter otpShifts =
            otp(102L, 1002L, true, new BigDecimal("1"), new BigDecimal("4"));
    private final ObjectTypeParameter otpWms = otp(103L, 1003L, false, null, null);
    private final ObjectTypeParameter otpRacking = otp(104L, 1004L, false, null, null);
    private final ParameterType payroll = pt(3001L, "payroll_tax_rate",
            "Коэффициент начислений на ФОТ", null, ParameterValueType.NUMBER);
    private final ParameterType pickingLines = pt(3002L, "picking_lines_per_day",
            "Объём отбора (строк/сутки, всего)", "строк/сут", ParameterValueType.NUMBER);
    private final ParameterType pickingUnits = pt(3003L, "picking_units_per_day",
            "Объём отбора (штук/сутки, всего)", "шт./сут", ParameterValueType.NUMBER);

    // --- фикстуры метаданных ----------------------------------------------
    private final ObjectTypeParameter otpPayroll = ObjectTypeParameter.builder()
            .id(301L).objectTypeId(WAREHOUSE_TYPE).parameterTypeId(3001L)
            .groupName("Персонал").isRequired(true)
            .isFixed(true).isDerived(false)
            .defaultValueNumeric(new BigDecimal("1.302"))
            .minValue(new BigDecimal("1.302")).maxValue(new BigDecimal("1.302"))
            .sourceNote("Легенда XLSX").build();
    private final ObjectTypeParameter otpPickingLines = ObjectTypeParameter.builder()
            .id(302L).objectTypeId(WAREHOUSE_TYPE).parameterTypeId(3002L)
            .groupName("Операции").isRequired(true)
            .defaultValueNumeric(new BigDecimal("100000"))
            .minValue(new BigDecimal("50000")).maxValue(new BigDecimal("500000"))
            .sourceNote("Датасеты").build();
    private final ObjectTypeParameter otpPickingUnits = ObjectTypeParameter.builder()
            .id(303L).objectTypeId(WAREHOUSE_TYPE).parameterTypeId(3003L)
            .groupName("Операции").isRequired(true)
            .isDerived(true).isFixed(false)
            .defaultValueNumeric(new BigDecimal("150000"))
            .minValue(new BigDecimal("75000")).maxValue(new BigDecimal("750000"))
            .sourceNote("Датасеты").build();
    @TempDir
    Path tempDir;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ObjectTypeRepository objectTypeRepository;
    @Mock
    private ObjectTypeParameterRepository objectTypeParameterRepository;
    @Mock
    private ParameterTypeRepository parameterTypeRepository;
    @Mock
    private ProjectParameterValueRepository valueRepository;
    @Mock
    private ProjectAttachmentRepository attachmentRepository;
    @Mock
    private AttachmentStorage storage;
    @Mock
    private TransactionTemplate transactionTemplate;
    @InjectMocks
    private ProjectParameterService service;

    // --- список -----------------------------------------------------------
    private Path csvFile;

    private ParameterType pt(long id, String code, String name, String unit,
                             ParameterValueType type) {
        return ParameterType.builder().id(id).code(code).name(name).unit(unit)
                .valueType(type).build();
    }

    // --- ручной ввод ------------------------------------------------------

    private ObjectTypeParameter otp(long id, long ptId, boolean required,
                                    BigDecimal min, BigDecimal max) {
        return ObjectTypeParameter.builder()
                .id(id).objectTypeId(WAREHOUSE_TYPE).parameterTypeId(ptId)
                .groupName("Общие").isRequired(required)
                .minValue(min).maxValue(max).sourceNote("Датасеты_хакатон.xlsx")
                .build();
    }

    @BeforeEach
    void setUp() throws IOException {
        Project project = Project.builder().id(PROJECT_ID).userId(ALICE)
                .objectTypeId(WAREHOUSE_TYPE).name("Мой склад").build();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        csvFile = Files.createTempFile(tempDir, "import", ".csv");
    }

    private void stubMetadata() {
        when(objectTypeParameterRepository
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(WAREHOUSE_TYPE))
                .thenReturn(List.of(otpArea, otpShifts, otpWms, otpRacking));
        when(parameterTypeRepository.findAllById(any()))
                .thenReturn(List.of(area, shifts, wms, racking));
    }

    private void runTransaction() {
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback =
                    invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    @Test
    void getParameters_mapsMetadataAndValues() {
        stubMetadata();
        ProjectParameterValue existing = ProjectParameterValue.builder()
                .id(1L).projectId(PROJECT_ID).objectTypeParameterId(101L)
                .valueNumeric(new BigDecimal("4200"))
                .source(ParameterValueSource.MANUAL).build();
        when(valueRepository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(existing));

        List<ParameterDto> result = service.getParameters(ALICE, PROJECT_ID);

        assertThat(result).hasSize(4);
        ParameterDto areaView = result.get(0);
        assertThat(areaView.getId()).isEqualTo(101L);
        assertThat(areaView.getCode()).isEqualTo("total_warehouse_area");
        assertThat(areaView.getUnit()).isEqualTo("кв. м");
        assertThat(areaView.getIsRequired()).isTrue();
        assertThat(areaView.getCurrentValue().value())
                .isEqualTo(new BigDecimal("4200"));
        assertThat(areaView.getSourceNote()).isNotBlank();
        ParameterDto wmsView = result.get(2);
        assertThat(wmsView.getValueType()).isEqualTo("boolean");
        assertThat(wmsView.getCurrentValue()).isNull();
        // без N+1: по одному вызову на справочник и значения
        verify(objectTypeParameterRepository)
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(WAREHOUSE_TYPE);
        verify(parameterTypeRepository).findAllById(any());
        verify(valueRepository).findAllByProjectId(PROJECT_ID);
    }

    @Test
    void getParameters_foreignProject_404() {
        assertThatThrownBy(() -> service.getParameters(BOB, PROJECT_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void set_number_createsRow() {
        when(objectTypeParameterRepository.findById(101L))
                .thenReturn(Optional.of(otpArea));
        when(parameterTypeRepository.findById(1001L)).thenReturn(Optional.of(area));
        when(valueRepository.findByProjectIdAndObjectTypeParameterId(PROJECT_ID, 101L))
                .thenReturn(Optional.empty(), Optional.of(ProjectParameterValue.builder()
                        .id(9L).projectId(PROJECT_ID).objectTypeParameterId(101L)
                        .valueNumeric(new BigDecimal("5000"))
                        .source(ParameterValueSource.MANUAL).build()));

        ParameterDto result = service.setParameterValue(ALICE, PROJECT_ID, 101L,
                new ParameterSetRequest(5000));

        assertThat(result.getCurrentValue().value()).isEqualTo(new BigDecimal("5000"));
        ArgumentCaptor<ProjectParameterValue> captor =
                ArgumentCaptor.forClass(ProjectParameterValue.class);
        verify(valueRepository).save(captor.capture());
        assertThat(captor.getValue().getValueNumeric()).isEqualByComparingTo("5000");
        assertThat(captor.getValue().getValueText()).isNull();
        assertThat(captor.getValue().getValueBool()).isNull();
        assertThat(captor.getValue().getSource()).isEqualTo(ParameterValueSource.MANUAL);
    }

    @Test
    void set_number_updatesExistingRow() {
        ProjectParameterValue existing = ProjectParameterValue.builder()
                .id(9L).projectId(PROJECT_ID).objectTypeParameterId(101L)
                .valueNumeric(new BigDecimal("100"))
                .source(ParameterValueSource.IMPORT).build();
        when(objectTypeParameterRepository.findById(101L))
                .thenReturn(Optional.of(otpArea));
        when(parameterTypeRepository.findById(1001L)).thenReturn(Optional.of(area));
        when(valueRepository.findByProjectIdAndObjectTypeParameterId(PROJECT_ID, 101L))
                .thenReturn(Optional.of(existing), Optional.of(existing));

        service.setParameterValue(ALICE, PROJECT_ID, 101L, new ParameterSetRequest(4321.5));

        assertThat(existing.getValueNumeric()).isEqualByComparingTo("4321.5");
        assertThat(existing.getSource()).isEqualTo(ParameterValueSource.MANUAL);
        verify(valueRepository).save(existing);
    }

    @Test
    void set_outOfRange_400withRangeText() {
        when(objectTypeParameterRepository.findById(101L))
                .thenReturn(Optional.of(otpArea));
        when(parameterTypeRepository.findById(1001L)).thenReturn(Optional.of(area));
        when(valueRepository.findByProjectIdAndObjectTypeParameterId(PROJECT_ID, 101L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setParameterValue(ALICE, PROJECT_ID, 101L,
                new ParameterSetRequest(2000000)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("от 1 до 1000000")
                .hasMessageContaining("кв. м");
        verify(valueRepository, never()).save(any());
    }

    @Test
    void set_nullValueRequired_400() {
        when(objectTypeParameterRepository.findById(101L))
                .thenReturn(Optional.of(otpArea));
        when(parameterTypeRepository.findById(1001L)).thenReturn(Optional.of(area));

        assertThatThrownBy(() -> service.setParameterValue(ALICE, PROJECT_ID, 101L,
                new ParameterSetRequest(null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("обязательный");
    }

    @Test
    void set_nullValueOptional_400() {
        when(objectTypeParameterRepository.findById(103L))
                .thenReturn(Optional.of(otpWms));
        when(parameterTypeRepository.findById(1003L)).thenReturn(Optional.of(wms));

        assertThatThrownBy(() -> service.setParameterValue(ALICE, PROJECT_ID, 103L,
                new ParameterSetRequest(null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("не передано");
    }

    // --- импорт --------------------------

    @Test
    void set_wrongType_stringForNumber_400() {
        when(objectTypeParameterRepository.findById(101L))
                .thenReturn(Optional.of(otpArea));
        when(parameterTypeRepository.findById(1001L)).thenReturn(Optional.of(area));

        assertThatThrownBy(() -> service.setParameterValue(ALICE, PROJECT_ID, 101L,
                new ParameterSetRequest("пять тысяч")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("числовое");
    }

    @Test
    void set_wrongType_numberForBoolean_400() {
        when(objectTypeParameterRepository.findById(103L))
                .thenReturn(Optional.of(otpWms));
        when(parameterTypeRepository.findById(1003L)).thenReturn(Optional.of(wms));

        assertThatThrownBy(() -> service.setParameterValue(ALICE, PROJECT_ID, 103L,
                new ParameterSetRequest(1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("логическое");
    }

    @Test
    void set_boolean_ok() {
        when(objectTypeParameterRepository.findById(103L))
                .thenReturn(Optional.of(otpWms));
        when(parameterTypeRepository.findById(1003L)).thenReturn(Optional.of(wms));
        when(valueRepository.findByProjectIdAndObjectTypeParameterId(PROJECT_ID, 103L))
                .thenReturn(Optional.empty(), Optional.of(ProjectParameterValue.builder()
                        .id(10L).projectId(PROJECT_ID).objectTypeParameterId(103L)
                        .valueBool(true).source(ParameterValueSource.MANUAL).build()));

        ParameterDto result = service.setParameterValue(ALICE, PROJECT_ID, 103L,
                new ParameterSetRequest(Boolean.TRUE));

        assertThat(result.getCurrentValue().kind()).isEqualTo("bool");
        assertThat(result.getCurrentValue().value()).isEqualTo(Boolean.TRUE);
    }

    @Test
    void set_parameterOfForeignObjectType_404() {
        ObjectTypeParameter airportParam = ObjectTypeParameter.builder()
                .id(201L).objectTypeId(20L).parameterTypeId(1001L).build();
        when(objectTypeParameterRepository.findById(201L))
                .thenReturn(Optional.of(airportParam));

        assertThatThrownBy(() -> service.setParameterValue(ALICE, PROJECT_ID, 201L,
                new ParameterSetRequest(100)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void set_raceOnInsert_retriesAsUpdate() {
        ProjectParameterValue winner = ProjectParameterValue.builder()
                .id(11L).projectId(PROJECT_ID).objectTypeParameterId(101L)
                .valueNumeric(new BigDecimal("1"))
                .source(ParameterValueSource.IMPORT).build();
        when(objectTypeParameterRepository.findById(101L))
                .thenReturn(Optional.of(otpArea));
        when(parameterTypeRepository.findById(1001L)).thenReturn(Optional.of(area));
        // первый findBy - пусто; INSERT падает (гонка UNIQUE); повторный
        // findBy - строка конкурента, UPDATE её побеждает
        when(valueRepository.findByProjectIdAndObjectTypeParameterId(PROJECT_ID, 101L))
                .thenReturn(Optional.empty(), Optional.of(winner), Optional.of(winner));
        when(valueRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("unique"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.setParameterValue(ALICE, PROJECT_ID, 101L, new ParameterSetRequest(777));

        assertThat(winner.getValueNumeric()).isEqualByComparingTo("777");
        assertThat(winner.getSource()).isEqualTo(ParameterValueSource.MANUAL);
    }

    @Test
    void reset_removesRow() {
        when(objectTypeParameterRepository.findById(101L))
                .thenReturn(Optional.of(otpArea));
        when(parameterTypeRepository.findById(1001L)).thenReturn(Optional.of(area));

        service.deleteParameterValue(ALICE, PROJECT_ID, 101L);

        verify(valueRepository).deleteByProjectIdAndObjectTypeParameterId(PROJECT_ID, 101L);
    }

    private void stubImport() throws IOException {
        stubMetadata();
        when(storage.maxBytes()).thenReturn(10_000_000L);
        when(storage.saveTemp(any())).thenReturn(csvFile);
        when(storage.promote(any(), eq(PROJECT_ID), any(), anyString()))
                .thenReturn(PROJECT_ID + "/7_import.csv");
        when(storage.resolve(anyString()))
                .thenReturn(tempDir.resolve("7_import.csv"));
        when(attachmentRepository.save(any())).thenAnswer(inv -> {
            ProjectAttachment attachment = inv.getArgument(0);
            attachment.setId(7L);
            return attachment;
        });
        when(valueRepository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of());
        runTransaction();
    }

    private MultipartFile csv(String content) {
        return new MockMultipartFile("file", "import.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void import_validCsv_writesAllValuesAndAttachment() throws IOException {
        stubImport();
        Files.writeString(csvFile,
                "total_warehouse_area;shifts_per_day;has_wms;racking_type\n"
                        + "4200,5;2;да;Глубинные стеллажи\n");

        ImportResultDto result = service.importParameters(ALICE, PROJECT_ID, csv(
                "total_warehouse_area;shifts_per_day;has_wms;racking_type\n"
                        + "4200,5;2;да;Глубинные стеллажи\n"));

        assertThat(result.getImportedCount()).isEqualTo(4);
        assertThat(result.getAttachmentId()).isEqualTo(7L);
        assertThat(result.getWarnings()).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProjectParameterValue>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(valueRepository).saveAll(captor.capture());
        Map<Long, ProjectParameterValue> byOtp = captor.getValue().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProjectParameterValue::getObjectTypeParameterId, v -> v));
        assertThat(byOtp.get(101L).getValueNumeric()).isEqualByComparingTo("4200.5");
        assertThat(byOtp.get(102L).getValueNumeric()).isEqualByComparingTo("2");
        assertThat(byOtp.get(103L).getValueBool()).isTrue();
        assertThat(byOtp.get(104L).getValueText()).isEqualTo("Глубинные стеллажи");
        assertThat(byOtp.values()).allMatch(
                v -> v.getSource() == ParameterValueSource.IMPORT);
        // вложение пишется двумя save (вставка + путь после promote) -
        // проверяем сам факт записи, а не количество
        verify(attachmentRepository, org.mockito.Mockito.atLeastOnce())
                .save(any(ProjectAttachment.class));
    }

    @Test
    void import_concurrentUniqueViolation_409andFilesCleaned() throws IOException {
        stubImport();
        Files.writeString(csvFile, "total_warehouse_area;shifts_per_day\n4200;2\n");
        Path promotedFile = tempDir.resolve("7_import.csv");
        when(storage.resolve(anyString())).thenReturn(promotedFile);
        // параллельная запись значений конкурентом -> UNIQUE violation
        // на flush внутри транзакции импорта
        when(valueRepository.saveAll(any()))
                .thenThrow(new DataIntegrityViolationException("ppv_unique"));
        runTransaction();

        assertThatThrownBy(() -> service.importParameters(ALICE, PROJECT_ID,
                csv("total_warehouse_area;shifts_per_day\n4200;2\n")))
                .isInstanceOf(me.yuugao.robomatch.exception.ConflictException.class)
                .hasMessageContaining("Повторите загрузку");

        // компенсация: оба файла (tmp и перенесённый) убраны
        verify(storage).deleteQuietly(promotedFile);
        verify(storage).deleteQuietly(csvFile);
    }

    @Test
    void import_oneBadCell_nothingWritten() throws IOException {
        stubImport();
        String content = "total_warehouse_area;shifts_per_day;has_wms\n"
                + "4200;99;да\n";
        Files.writeString(csvFile, content);

        // в 102 (shifts_per_day) диапазон 1..4, файл кладёт 99 - 400,
        // и НИ ОДНОЙ записи в БД (включая корректные 4200 и «да»)
        assertThatThrownBy(() -> service.importParameters(ALICE, PROJECT_ID, csv(content)))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Ничего не сохранено")
                .satisfies(ex -> assertThat(
                        ((ImportException) ex).getErrors())
                        .singleElement()
                        .satisfies(error -> {
                            assertThat(error.row()).isEqualTo(2);
                            assertThat(error.column()).isEqualTo(2);
                            assertThat(error.parameterCode()).isEqualTo("shifts_per_day");
                        }));
        verify(valueRepository, never()).saveAll(any());
        verify(valueRepository, never()).save(any());
        verify(attachmentRepository, never()).save(any());
    }

    // --- вложения ----------------------------------------------------------

    @Test
    void import_unknownColumn_error() throws IOException {
        stubImport();
        String content = "total_warehouse_area;passenger_flow\n4200;100\n";
        Files.writeString(csvFile, content);

        assertThatThrownBy(() -> service.importParameters(ALICE, PROJECT_ID, csv(content)))
                .isInstanceOf(ImportException.class)
                .satisfies(ex -> assertThat(((ImportException) ex).getErrors())
                        .singleElement()
                        .satisfies(error -> {
                            assertThat(error.parameterCode()).isEqualTo("passenger_flow");
                            assertThat(error.message()).contains("не относится");
                        }));
        verify(valueRepository, never()).saveAll(any());
    }

    @Test
    void import_extraRows_warningOnly() throws IOException {
        stubImport();
        String content = "total_warehouse_area\n4200\n5000\n6000\n";
        Files.writeString(csvFile, content);

        ImportResultDto result = service.importParameters(ALICE, PROJECT_ID, csv(content));

        assertThat(result.getImportedCount()).isEqualTo(1);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("Строка 3"));
    }

    @Test
    void import_requiredStillEmpty_warning() throws IOException {
        stubImport();
        String content = "has_wms\nда\n";
        Files.writeString(csvFile, content);

        ImportResultDto result = service.importParameters(ALICE, PROJECT_ID, csv(content));

        assertThat(result.getImportedCount()).isEqualTo(1);
        // area и shifts обязательные, но в файле их нет - предупреждение
        assertThat(result.getWarnings())
                .anyMatch(w -> w.contains("Общая площадь склада"))
                .anyMatch(w -> w.contains("Смен в сутки"));
    }
    // --- worst-case параметры ----

    @Test
    void import_wrongMime_400() throws IOException {
        stubImport();
        MultipartFile file = new MockMultipartFile("file", "photo.png", "image/png",
                new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.importParameters(ALICE, PROJECT_ID, file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Excel");
        verify(storage, never()).saveTemp(any());
    }

    @Test
    void import_tooLarge_413() throws IOException {
        when(storage.maxBytes()).thenReturn(10L);
        MultipartFile file = new MockMultipartFile("file", "import.csv", "text/csv",
                new byte[50]);

        assertThatThrownBy(() -> service.importParameters(ALICE, PROJECT_ID, file))
                .isInstanceOf(PayloadTooLargeException.class)
                .hasMessageContaining("лимита");
        verify(storage, never()).saveTemp(any());
    }

    @Test
    void import_unreadableFile_400() throws IOException {
        stubImport();
        Files.writeString(csvFile, "этот файл совсем не Excel и не CSV с заголовком?");
        MultipartFile file = new MockMultipartFile("file", "import.csv", "text/csv",
                "этот файл совсем не Excel и не CSV с заголовком?"
                        .getBytes(StandardCharsets.UTF_8));
        // «заголовок» не распознаётся как Excel - ок, это CSV; но без
        // известных кодов колонка станет ошибкой валидации - проверим 400
        assertThatThrownBy(() -> service.importParameters(ALICE, PROJECT_ID, file))
                .isInstanceOf(ImportException.class);
        verify(valueRepository, never()).saveAll(any());
    }

    @Test
    void attachments_listMapsDto() {
        when(attachmentRepository.findAllByProjectIdOrderByUploadedAtDesc(PROJECT_ID))
                .thenReturn(List.of(ProjectAttachment.builder()
                        .id(7L).projectId(PROJECT_ID).fileName("импорт.csv")
                        .filePath("5/7_импорт.csv").mimeType("text/csv")
                        .sizeBytes(123L).uploadedAt(java.time.Instant.now()).build()));

        var result = service.listAttachments(ALICE, PROJECT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFileName()).isEqualTo("импорт.csv");
        assertThat(result.get(0).getSizeBytes()).isEqualTo(123L);
        // путь хранилища наружу не отдаётся
        assertThat(result.get(0)).hasNoNullFieldsOrPropertiesExcept("mimeType");
        assertThat(result.get(0).toString()).doesNotContain("5/7_");
    }

    @Test
    void attachments_delete_removesRowAndFile() {
        ProjectAttachment attachment = ProjectAttachment.builder()
                .id(7L).projectId(PROJECT_ID).fileName("импорт.csv")
                .filePath(PROJECT_ID + "/7_импорт.csv").mimeType("text/csv")
                .sizeBytes(123L).uploadedAt(java.time.Instant.now()).build();
        when(attachmentRepository.findByProjectIdAndId(PROJECT_ID, 7L))
                .thenReturn(Optional.of(attachment));
        Path stored = tempDir.resolve("stored.csv");
        when(storage.resolve(PROJECT_ID + "/7_импорт.csv")).thenReturn(stored);
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service.deleteAttachment(ALICE, PROJECT_ID, 7L);

        verify(attachmentRepository).delete(attachment);
        verify(storage).deleteQuietly(stored);
    }

    @Test
    void attachments_deleteForeignProject_404() {
        assertThatThrownBy(() -> service.deleteAttachment(BOB, PROJECT_ID, 7L))
                .isInstanceOf(NotFoundException.class);
    }

    private void stubWorstCaseMetadata() {
        when(objectTypeParameterRepository
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(WAREHOUSE_TYPE))
                .thenReturn(List.of(otpPayroll, otpPickingLines, otpPickingUnits));
        when(parameterTypeRepository.findAllById(any()))
                .thenReturn(List.of(payroll, pickingLines, pickingUnits));
    }

    @Test
    void set_fixedParam_400_nothingWritten() {
        when(objectTypeParameterRepository.findById(301L))
                .thenReturn(Optional.of(otpPayroll));
        when(parameterTypeRepository.findById(3001L)).thenReturn(Optional.of(payroll));

        assertThatThrownBy(() -> service.setParameterValue(ALICE, PROJECT_ID, 301L,
                new ParameterSetRequest(1.5)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("фиксированный");
        verify(valueRepository, never()).save(any());
    }

    @Test
    void set_derivedParam_400_nothingWritten() {
        when(objectTypeParameterRepository.findById(303L))
                .thenReturn(Optional.of(otpPickingUnits));
        when(parameterTypeRepository.findById(3003L)).thenReturn(Optional.of(pickingUnits));

        assertThatThrownBy(() -> service.setParameterValue(ALICE, PROJECT_ID, 303L,
                new ParameterSetRequest(999999)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("рассчитывается автоматически");
        verify(valueRepository, never()).save(any());
    }

    @Test
    void delete_fixedParam_400() {
        when(objectTypeParameterRepository.findById(301L))
                .thenReturn(Optional.of(otpPayroll));
        when(parameterTypeRepository.findById(3001L)).thenReturn(Optional.of(payroll));

        assertThatThrownBy(() -> service.deleteParameterValue(ALICE, PROJECT_ID, 301L))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("фиксированный");
        verify(valueRepository, never())
                .deleteByProjectIdAndObjectTypeParameterId(anyLong(), anyLong());
    }

    @Test
    void delete_derivedParam_400() {
        when(objectTypeParameterRepository.findById(303L))
                .thenReturn(Optional.of(otpPickingUnits));
        when(parameterTypeRepository.findById(3003L)).thenReturn(Optional.of(pickingUnits));

        assertThatThrownBy(() -> service.deleteParameterValue(ALICE, PROJECT_ID, 303L))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("рассчитывается автоматически");
        verify(valueRepository, never())
                .deleteByProjectIdAndObjectTypeParameterId(anyLong(), anyLong());
    }

    @Test
    void getParameters_derivedComputed_fromSourceDefault() {
        stubWorstCaseMetadata();
        when(valueRepository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of());

        List<ParameterDto> result = service.getParameters(ALICE, PROJECT_ID);

        ParameterDto units = result.stream()
                .filter(v -> v.getCode().equals("picking_units_per_day")).findFirst().orElseThrow();
        assertThat(units.getIsDerived()).isTrue();
        assertThat(units.getIsFixed()).isFalse();
        assertThat(units.getDerivedFromName())
                .isEqualTo("Объём отбора (строк/сутки, всего)");
        // 100000 (дефолт источника) x 1.5
        assertThat((BigDecimal) units.getCurrentValue().value())
                .isEqualByComparingTo("150000");
        assertThat(units.getUpdatedAt()).isNull();
    }

    @Test
    void getParameters_derivedComputed_fromUserSourceValue() {
        stubWorstCaseMetadata();
        when(valueRepository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(
                ProjectParameterValue.builder()
                        .id(1L).projectId(PROJECT_ID).objectTypeParameterId(302L)
                        .valueNumeric(new BigDecimal("200000"))
                        .source(ParameterValueSource.MANUAL).build(),
                // мёртвая строка производного (осталась до V4) - игнорируется
                ProjectParameterValue.builder()
                        .id(2L).projectId(PROJECT_ID).objectTypeParameterId(303L)
                        .valueNumeric(new BigDecimal("999999"))
                        .source(ParameterValueSource.MANUAL).build()));

        List<ParameterDto> result = service.getParameters(ALICE, PROJECT_ID);

        ParameterDto units = result.stream()
                .filter(v -> v.getCode().equals("picking_units_per_day")).findFirst().orElseThrow();
        // 200000 (строка пользователя) x 1.5 - не 999999 из мёртвой строки
        assertThat((BigDecimal) units.getCurrentValue().value()).isEqualByComparingTo("300000");
    }

    @Test
    void getParameters_fixedShowsConstant_ignoresUserRow() {
        stubWorstCaseMetadata();
        when(valueRepository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(
                ProjectParameterValue.builder()
                        .id(1L).projectId(PROJECT_ID).objectTypeParameterId(301L)
                        .valueNumeric(new BigDecimal("2"))
                        .source(ParameterValueSource.IMPORT).build()));

        List<ParameterDto> result = service.getParameters(ALICE, PROJECT_ID);

        ParameterDto payrollView = result.get(0);
        assertThat(payrollView.getIsFixed()).isTrue();
        assertThat((BigDecimal) payrollView.getCurrentValue().value()).isEqualByComparingTo("1.302");
        assertThat(payrollView.getUpdatedAt()).isNull();
        assertThat(payrollView.getDerivedFromName()).isNull();
    }

    @Test
    void import_fixedOrDerivedCell_400_nothingWritten() throws IOException {
        stubImport();
        stubWorstCaseMetadata();
        // перебиваем метаданные импорта на worst-case набор
        String content = "payroll_tax_rate;picking_units_per_day;picking_lines_per_day\n"
                + "1.5;200000;100000\n";
        Files.writeString(csvFile, content);

        assertThatThrownBy(() -> service.importParameters(ALICE, PROJECT_ID, csv(content)))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("ошибки");
        verify(valueRepository, never()).saveAll(any());
        verify(attachmentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void import_fixedColumnWithEmptyCell_ok() throws IOException {
        stubImport();
        stubWorstCaseMetadata();
        // колонка фиксированного параметра из старого шаблона с пустой
        // ячейкой - не ошибка: значение не передано, система не трогается
        String content = "payroll_tax_rate;picking_lines_per_day\n"
                + ";120000\n";
        Files.writeString(csvFile, content);

        ImportResultDto result = service.importParameters(ALICE, PROJECT_ID, csv(content));

        assertThat(result.getImportedCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProjectParameterValue>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(valueRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getObjectTypeParameterId()).isEqualTo(302L);
    }

    @Test
    void template_excludesFixedAndDerivedColumns() {
        stubWorstCaseMetadata();
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(ObjectType.builder()
                        .id(WAREHOUSE_TYPE).code("warehouse").name("Склад").build()));

        ParameterTemplateService.TemplateFile template =
                service.buildTemplate(ALICE, PROJECT_ID, "csv");

        String body = new String(template.content(), StandardCharsets.UTF_8);
        assertThat(body).contains("picking_lines_per_day");
        assertThat(body).doesNotContain("payroll_tax_rate");
        assertThat(body).doesNotContain("picking_units_per_day");
    }
}
