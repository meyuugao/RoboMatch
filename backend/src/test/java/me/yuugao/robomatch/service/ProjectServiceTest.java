package me.yuugao.robomatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import me.yuugao.robomatch.domain.ObjectType;
import me.yuugao.robomatch.domain.Project;
import me.yuugao.robomatch.domain.ProjectStatus;
import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.mapper.ProjectMapper;
import me.yuugao.robomatch.repository.ObjectTypeRepository;
import me.yuugao.robomatch.repository.ProjectRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Unit-тест ProjectService: изоляция, валидация,
 * копирование, пагинация. Репозитории - моки, маппер - реальный
 * (чистая функция).
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final Long ALICE = 1L;
    private static final Long BOB = 2L;
    private static final Long WAREHOUSE_TYPE = 10L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ObjectTypeRepository objectTypeRepository;
    @Mock
    private AttachmentStorage attachmentStorage;
    @Mock
    private SimulationStorage simulationStorage;
    @Mock
    private me.yuugao.robomatch.service.ExportStorage exportStorage;
    @Mock
    private TransactionTemplate transactionTemplate;

    @Spy
    private ProjectMapper projectMapper = new ProjectMapper();

    @InjectMocks
    private ProjectService projectService;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;
    @Captor
    private ArgumentCaptor<Project> projectCaptor;

    private ObjectType warehouse() {
        return ObjectType.builder()
                .id(WAREHOUSE_TYPE).code("warehouse").name("Склад")
                .isCalcEnabled(true).build();
    }

    private Project project(Long id, Long userId, String name) {
        return Project.builder()
                .id(id).userId(userId).objectTypeId(WAREHOUSE_TYPE)
                .name(name).status(ProjectStatus.DRAFT)
                .build();
    }

    /**
 * Прогон лямбды TransactionTemplate без транзакции (сервис в тестах
 * выполняет тело сразу - как реальный commit).
 */
    private void runTransactions() {
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(org.mockito.Mockito.mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(org.mockito.ArgumentMatchers.any());
    }

    // --- список ---------------------------------------------------------

    @Test
    void list_passesUserId_isolationByContract() {
        when(projectRepository.findAllByUserId(eq(ALICE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(project(5L, ALICE, "Мой"))));
        when(objectTypeRepository.findAllById(List.of(WAREHOUSE_TYPE)))
                .thenReturn(List.of(warehouse()));

        PageResponse<ProjectSummaryDto> result = projectService.listProjects(ALICE, null, null);

        // репозиторий получил именно userId Алисы - изоляция в контракте
        verify(projectRepository).findAllByUserId(eq(ALICE), pageableCaptor.capture());
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getObjectTypeName()).isEqualTo("Склад");
        // сортировка по умолчанию: свежие изменённые сверху + id как тайбрейк
        Sort sort = pageableCaptor.getValue().getSort();
        assertThat(sort.getOrderFor("updatedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void list_invalidPageAndSize_throws400() {
        assertThatThrownBy(() -> projectService.listProjects(ALICE, -1, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> projectService.listProjects(ALICE, 0, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> projectService.listProjects(ALICE, 0, 101))
                .isInstanceOf(BadRequestException.class);
    }

    // --- создание -------------------------------------------------------

    @Test
    void create_trimsFields_andBuildsDraft() {
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(warehouse()));
        when(projectRepository.existsByUserIdAndName(ALICE, "Склад")).thenReturn(false);
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectFullDto created = projectService.createProject(ALICE, ProjectCreateRequest.builder()
                .name("  Склад  ").description("  Описание  ").objectTypeId(WAREHOUSE_TYPE)
                .build());

        verify(projectRepository).save(projectCaptor.capture());
        assertThat(projectCaptor.getValue().getUserId()).isEqualTo(ALICE);
        assertThat(projectCaptor.getValue().getName()).isEqualTo("Склад");
        assertThat(projectCaptor.getValue().getDescription()).isEqualTo("Описание");
        assertThat(projectCaptor.getValue().getStatus()).isEqualTo(ProjectStatus.DRAFT);
        assertThat(created.getObjectTypeName()).isEqualTo("Склад");
    }

    @Test
    void create_duplicateName_throws409() {
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(warehouse()));
        when(projectRepository.existsByUserIdAndName(ALICE, "Склад")).thenReturn(true);

        assertThatThrownBy(() -> projectService.createProject(ALICE, ProjectCreateRequest.builder()
                .name("Склад").objectTypeId(WAREHOUSE_TYPE).build()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже существует");
    }

    @Test
    void create_unknownObjectType_throws400() {
        when(objectTypeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.createProject(ALICE, ProjectCreateRequest.builder()
                .name("Склад").objectTypeId(999L).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("999");
    }

    @Test
    void create_sameNameForDifferentUsers_ok() {
        // имя уникально в рамках ПОЛЬЗОВАТЕЛЯ (UNIQUE(user_id, name)), не глобально
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(warehouse()));
        when(projectRepository.existsByUserIdAndName(BOB, "Склад")).thenReturn(false);
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        org.assertj.core.api.Assertions.assertThatCode(() -> projectService.createProject(BOB,
                        ProjectCreateRequest.builder()
                                .name("Склад").objectTypeId(WAREHOUSE_TYPE).build()))
                .doesNotThrowAnyException();
    }

    // --- чтение и изоляция ----------------------------------------------

    @Test
    void get_foreignProject_throws404() {
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, "Чужой")));

        assertThatThrownBy(() -> projectService.getProject(BOB, 5L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Проект не найден");
    }

    @Test
    void get_missingProject_throws404() {
        when(projectRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getProject(ALICE, 42L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void get_ownProject_mapsType() {
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, "Мой")));
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(warehouse()));

        ProjectFullDto dto = projectService.getProject(ALICE, 5L);

        assertThat(dto.getName()).isEqualTo("Мой");
        assertThat(dto.getObjectTypeCode()).isEqualTo("warehouse");
        assertThat(dto.getObjectTypeIsCalcEnabled()).isTrue();
    }

    // --- редактирование --------------------------------------------------

    @Test
    void update_ownProject_renames() {
        Project existing = project(5L, ALICE, "Старое");
        when(projectRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.existsByUserIdAndName(ALICE, "Новое")).thenReturn(false);
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectFullDto updated = projectService.updateProject(ALICE, 5L,
                ProjectUpdateRequest.builder().name("Новое").description("Описание").build());

        assertThat(updated.getName()).isEqualTo("Новое");
        assertThat(existing.getDescription()).isEqualTo("Описание");
    }

    @Test
    void update_renameToExistingName_throws409() {
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, "Старое")));
        when(projectRepository.existsByUserIdAndName(ALICE, "Занято")).thenReturn(true);

        assertThatThrownBy(() -> projectService.updateProject(ALICE, 5L,
                ProjectUpdateRequest.builder().name("Занято").build()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_foreignProject_throws404() {
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, "Чужой")));

        assertThatThrownBy(() -> projectService.updateProject(BOB, 5L,
                ProjectUpdateRequest.builder().name("Взлом").build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_sameName_noDuplicateCheck() {
        // имя не менялось - проверка дубля не нужна (и не вызывается)
        Project existing = project(5L, ALICE, "То же имя");
        when(projectRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        projectService.updateProject(ALICE, 5L,
                ProjectUpdateRequest.builder().name("То же имя").description("d").build());

        verify(projectRepository, org.mockito.Mockito.never())
                .existsByUserIdAndName(anyLong(), any());
    }

    // --- копирование -----------------------------------------------------

    @Test
    void copy_firstCopy_getsCopySuffix() {
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, "Оригинал")));
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(warehouse()));
        when(projectRepository.existsByUserIdAndName(ALICE, "Оригинал (копия)"))
                .thenReturn(false);
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectFullDto copy = projectService.copyProject(ALICE, 5L);

        verify(projectRepository).save(projectCaptor.capture());
        assertThat(copy.getName()).isEqualTo("Оригинал (копия)");
        assertThat(projectCaptor.getValue().getStatus()).isEqualTo(ProjectStatus.DRAFT);
        assertThat(projectCaptor.getValue().getDescription())
                .isEqualTo(project(5L, ALICE, "Оригинал").getDescription());
    }

    @Test
    void copy_secondCopy_getsNumberedSuffix() {
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, "Оригинал")));
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(warehouse()));
        when(projectRepository.existsByUserIdAndName(ALICE, "Оригинал (копия)"))
                .thenReturn(true);
        when(projectRepository.existsByUserIdAndName(ALICE, "Оригинал (копия 2)"))
                .thenReturn(false);
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectFullDto copy = projectService.copyProject(ALICE, 5L);

        assertThat(copy.getName()).isEqualTo("Оригинал (копия 2)");
    }

    @Test
    void copy_longSourceName_firstCandidate_fits128() {
        // имя 124 символа + " (копия)" (8) = 132 - без усечения
        // копия получала имя длиннее лимита, которое потом не сохранялось PUT-ом
        String longName = "С".repeat(124);
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, longName)));
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(warehouse()));
        when(projectRepository.existsByUserIdAndName(eq(ALICE), any(String.class)))
                .thenReturn(false);
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectFullDto copy = projectService.copyProject(ALICE, 5L);

        assertThat(copy.getName()).hasSize(128);
        assertThat(copy.getName()).endsWith("(копия)");
    }

    @Test
    void copy_longSourceName_numberedCandidate_fits128() {
        String longName = "С".repeat(126);
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, longName)));
        when(objectTypeRepository.findById(WAREHOUSE_TYPE))
                .thenReturn(Optional.of(warehouse()));
        when(projectRepository.existsByUserIdAndName(eq(ALICE), org.mockito.ArgumentMatchers.startsWith("С")))
                .thenReturn(true)   // «(копия)» занято
                .thenReturn(false); // «(копия 2)» свободно
        when(projectRepository.save(any(Project.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProjectFullDto copy = projectService.copyProject(ALICE, 5L);

        assertThat(copy.getName()).hasSize(128);
        assertThat(copy.getName()).endsWith("(копия 2)");
    }

    @Test
    void copy_foreignProject_throws404() {
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, "Чужой")));

        assertThatThrownBy(() -> projectService.copyProject(BOB, 5L))
                .isInstanceOf(NotFoundException.class);
    }

    // --- удаление --------------------------------------------------------

    @Test
    void delete_ownProject_removes() {
        Project existing = project(5L, ALICE, "На удаление");
        when(projectRepository.findById(5L)).thenReturn(Optional.of(existing));
        runTransactions();

        projectService.deleteProject(ALICE, 5L);

        verify(projectRepository).delete(existing);
        //
        verify(attachmentStorage).deleteProjectFiles(5L);
        // и сохранёнными схемами имитаций
        verify(simulationStorage).deleteProjectFiles(5L);
    }

    @Test
    void delete_foreignProject_throws404() {
        when(projectRepository.findById(5L))
                .thenReturn(Optional.of(project(5L, ALICE, "Чужой")));

        assertThatThrownBy(() -> projectService.deleteProject(BOB, 5L))
                .isInstanceOf(NotFoundException.class);
    }
}
