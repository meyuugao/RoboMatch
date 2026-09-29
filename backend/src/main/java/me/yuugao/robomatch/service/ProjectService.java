package me.yuugao.robomatch.service;

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

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

/**
 * Бизнес-логика проектов пользователя (;
 * user-flow.md шаги 1–2).
 * <p>
 * ИЗОЛЯЦИЯ: каждый метод принимает userId и проверяет
 * владельца — чужой проект неотличим от несуществующего (404, не 403:
 * 403 раскрывал бы перебором, какие имена проектов вообще существуют).
 * userId приходит из CurrentUser.requireUserId в контроллере, из
 * запроса его взять нельзя.
 * <p>
 * КОПИРОВАНИЕ: копируются метаданные — имя, описание, тип
 * объекта; статус копии — draft (жизненный цикл копии начинается
 * заново). Параметры и вложения СОЗНАТЕЛЬНО не копируются (осознанное
 * решение): пользователь получает чистый черновик того же типа объекта —
 * параметры заведёт под свои данные (формы и импорт параметров
 * объекта это позволяют). Глубокое копирование
 * значений (если понадобится) — сервисная операция поверх тех же
 * таблиц, контракт метода не меняется.
 * <p>
 * ИМЯ КОПИИ: «имя (копия)», при занятом — «имя (копия 2)»,
 * «(копия 3)», ... — до свободного (практический потолок защищён
 * лимитом длины имени 128; зацикливание исключено уникальностью N).
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    /**
 * Дефолт и максимум размера страницы (едино с каталогом).
 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final String COPY_SUFFIX = " (копия)";

    private final ProjectRepository projectRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final AttachmentStorage attachmentStorage;
    private final SimulationStorage simulationStorage;
    private final ExportStorage exportStorage;
    private final TransactionTemplate transactionTemplate;
    private final ProjectMapper projectMapper;

    // ------------------------------------------------------------------
    // Список
    // ------------------------------------------------------------------

    /**
 * Имя копии с усечением базы под лимит 128 (суффикс помещается всегда).
 */
    private static String copyName(String sourceName, Integer n) {
        String suffix = n == null ? COPY_SUFFIX : " (копия " + n + ")";
        if (sourceName.length() + suffix.length() <= 128) {
            return sourceName + suffix;
        }
        return sourceName.substring(0, 128 - suffix.length()) + suffix;
    }

    // ------------------------------------------------------------------
    // Создание
    // ------------------------------------------------------------------

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------
    // Чтение
    // ------------------------------------------------------------------

    /**
 * Проекты пользователя по страницам. Сортировка — updatedAt DESC
 * (последние изменённые сверху): пользователь возвращается к тому,
 * с чем работал; вторичный ключ id — детерминированный порядок
 * при одновременном updated_at (записи «прыгали»
 * между страницами).
 *
 * @param userId владелец (изоляция)
 * @param page номер страницы (0-based; null — первая)
 * @param size размер страницы (null — дефолт 20; максимум 100)
 * @return страница списка проектов пользователя
 */
    @Transactional(readOnly = true)
    public PageResponse<ProjectSummaryDto> listProjects(Long userId, Integer page,
                                                        Integer size) {
        int safePage = page == null ? 0 : page;
        int safeSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (safePage < 0) {
            throw new BadRequestException("page не может быть отрицательным");
        }
        if (safeSize < 1 || safeSize > MAX_PAGE_SIZE) {
            throw new BadRequestException("size должен быть от 1 до " + MAX_PAGE_SIZE);
        }
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        Page<Project> pageResult = projectRepository.findAllByUserId(userId, pageable);
        Map<Long, ObjectType> types = resolveObjectTypes(pageResult.getContent());
        List<ProjectSummaryDto> content = pageResult.getContent().stream()
                .map(project -> projectMapper.toSummary(project,
                        types.get(project.getObjectTypeId())))
                .toList();
        return new PageResponse<>(content, pageResult.getNumber(), pageResult.getSize(),
                pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    // ------------------------------------------------------------------
    // Редактирование
    // ------------------------------------------------------------------

    /**
 * Создание проекта. 409 при дубликате имени, 400 при неизвестном типе.
 *
 * @param userId владелец нового проекта
 * @param request имя/описание/тип объекта
 * @return карточка созданного проекта
 */
    @Transactional
    public ProjectFullDto createProject(Long userId, ProjectCreateRequest request) {
        ObjectType objectType = objectTypeRepository.findById(request.getObjectTypeId())
                .orElseThrow(() -> new BadRequestException(
                        "Неизвестный тип объекта: id=" + request.getObjectTypeId()));
        String name = request.getName().trim();
        if (projectRepository.existsByUserIdAndName(userId, name)) {
            throw new ConflictException(
                    "Проект с именем «" + name + "» уже существует");
        }
        Project project = Project.builder()
                .userId(userId)
                .objectTypeId(objectType.getId())
                .name(name)
                .description(trimToNull(request.getDescription()))
                .status(ProjectStatus.DRAFT)
                .build();
        try {
            project = projectRepository.save(project);
        } catch (DataIntegrityViolationException ex) {
            // гонка: тот же пользователь успел создать проект с тем же именем
            // между exists-проверкой и INSERT — UNIQUE(user_id, name) сработал
            throw new ConflictException(
                    "Проект с именем «" + name + "» уже существует");
        }
        return projectMapper.toFull(project, objectType);
    }

    // ------------------------------------------------------------------
    // Копирование
    // ------------------------------------------------------------------

    /**
 * Карточка проекта; чужой или несуществующий — 404 (изоляция).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return карточка проекта
 */
    @Transactional(readOnly = true)
    public ProjectFullDto getProject(Long userId, Long projectId) {
        Project project = loadOwned(userId, projectId);
        ObjectType objectType = objectTypeRepository.findById(project.getObjectTypeId())
                .orElse(null);
        return projectMapper.toFull(project, objectType);
    }

    // ------------------------------------------------------------------
    // Удаление
    // ------------------------------------------------------------------

    /**
 * Редактирование (имя/описание; 409 при занятом имени).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param request новое имя/описание
 * @return карточка обновлённого проекта
 */
    @Transactional
    public ProjectFullDto updateProject(Long userId, Long projectId,
                                        ProjectUpdateRequest request) {
        Project project = loadOwned(userId, projectId);
        String newName = request.getName().trim();
        if (!project.getName().equals(newName)
                && projectRepository.existsByUserIdAndName(userId, newName)) {
            throw new ConflictException(
                    "Проект с именем «" + newName + "» уже существует");
        }
        project.setName(newName);
        project.setDescription(trimToNull(request.getDescription()));
        // updatedAt поднимет @LastModifiedDate — проект всплывёт в списке
        try {
            project = projectRepository.save(project);
        } catch (DataIntegrityViolationException ex) {
            // гонка: имя успел занять другой проект этого пользователя
            // между exists-проверкой и UPDATE (симметрия с create/copy)
            throw new ConflictException(
                    "Проект с именем «" + newName + "» уже существует");
        }
        ObjectType objectType = objectTypeRepository.findById(project.getObjectTypeId())
                .orElse(null);
        return projectMapper.toFull(project, objectType);
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    /**
 * Копия проекта: метаданные + имя «имя (копия N)».
 * Параметры/вложения не копируются (осознанное решение,
 * javadoc класса) — копия начинается как чистый draft.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор исходного проекта
 * @return карточка копии (статус draft)
 */
    @Transactional
    public ProjectFullDto copyProject(Long userId, Long projectId) {
        Project source = loadOwned(userId, projectId);
        ObjectType objectType = objectTypeRepository.findById(source.getObjectTypeId())
                .orElseThrow(() -> new BadRequestException(
                        "Неизвестный тип объекта: id=" + source.getObjectTypeId()));
        Project copy = Project.builder()
                .userId(userId)
                .objectTypeId(source.getObjectTypeId())
                .name(nextCopyName(userId, source.getName()))
                .description(source.getDescription())
                .status(ProjectStatus.DRAFT)
                .build();
        try {
            copy = projectRepository.save(copy);
        } catch (DataIntegrityViolationException ex) {
            // гонка подбора имени (две одновременные копии) — честный 409;
            // повторная вставка в этой же транзакции невозможна: сессия
            // после нарушения констрейнта идёт в rollback
            throw new ConflictException(
                    "Не удалось скопировать: имя «" + copy.getName()
                            + "» уже занято, попробуйте ещё раз");
        }
        return projectMapper.toFull(copy, objectType);
    }

    /**
 * Удаление проекта. Каскад строк — на стороне БД (V1:
 * project -> project_parameter_value, project_attachment, scenario,
 * selection_result, export; далее по цепочке — data_model.md §12).
 * Файлы вложений — физическое удаление хранилищем. Порядок: строка
 * БД — в транзакции TransactionTemplate, файлы — ПОСЛЕ коммита,
 * best-effort (ошибка файловой системы логируется, но не откатывает
 * удаление и не пугает пользователя — строки уже нет, осиротевший
 * файл не влияет на работу; компенсация при разборе инцидентов).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 */
    public void deleteProject(Long userId, Long projectId) {
        Project project = loadOwned(userId, projectId);
        // существует ли каталог вложений — проверять не нужно: удаление
        // пустого каталога в AttachmentStorage безвредно
        transactionTemplate.executeWithoutResult(tx ->
                projectRepository.delete(project));
        attachmentStorage.deleteProjectFiles(projectId);
        // сохранённые 2D-схемы имитаций ( —
        // удаление вместе с загруженными файлами; строки simulation_result
        // каскадит схема V1)
        simulationStorage.deleteProjectFiles(projectId);
        // сгенерированные отчёты ( — файлы
        // data/exports/{projectId} вместе с проектом; строки export
        // каскадит схема V1)
        exportStorage.deleteProjectFiles(projectId);
    }

    /**
 * Загрузка проекта С проверкой владельца; иначе — 404.
 */
    private Project loadOwned(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            // единый ответ для «нет такого» и «чужой»: не раскрываем
            // существование чужих проектов перебором id
            throw new NotFoundException("Проект не найден");
        }
        return project;
    }

    /**
 * «<имя> (копия)», при занятом — «<имя> (копия 2)» и далее.
 * Лимит длины имени 128 соблюдается для ЛЮБОГО кандидата:
 * первый кандидат без усечения давал имя до 136 символов, которое
 * потом нельзя было сохранить через PUT.
 */
    private String nextCopyName(Long userId, String sourceName) {
        String candidate = copyName(sourceName, null);
        if (!projectRepository.existsByUserIdAndName(userId, candidate)) {
            return candidate;
        }
        for (int n = 2; n <= 1000; n++) {
            candidate = copyName(sourceName, n);
            if (!projectRepository.existsByUserIdAndName(userId, candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("Не удалось подобрать имя копии проекта");
    }

    /**
 * Имена типов объектов батчем: один IN-запрос на страницу, без N+1.
 */
    private Map<Long, ObjectType> resolveObjectTypes(List<Project> projects) {
        List<Long> ids = projects.stream()
                .map(Project::getObjectTypeId)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return objectTypeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ObjectType::getId, Function.identity()));
    }
}
