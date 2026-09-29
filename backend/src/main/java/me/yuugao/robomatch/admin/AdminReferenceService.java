package me.yuugao.robomatch.admin;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.domain.Process;
import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * CRUD справочников для администратора ( «управлять
 * справочниками»): industry, process, vendor, region,
 * solution_type, solution_subtype, characteristic_type.
 * <p>
 * Особенности справочников:
 * - vendor — только имя (без кода): технический код не предусмотрен
 * схемой (V1);
 * - process — переключатель is_active (деактивация вместо удаления);
 * - characteristic_type — при создании обязательны group_code и
 * data_type (CHECK в схеме);
 * - удаление записи, на которую ссылаются карточки решений /
 * применения / типы объектов / пары «тип-подтип» / значения ТТХ —
 * 409 с перечнем ссылающихся сущностей (data_model.md §11: не рушим
 * чужой контент; FK в схеме RESTRICT).
 */
@Service
public class AdminReferenceService {

    /**
 * Белый список справочников.
 */
    public static final Set<String> DICT_CODES = Set.of(
            "industry", "process", "vendor", "region",
            "solution_type", "solution_subtype", "characteristic_type");

    /**
 * Подписи для сообщений.
 */
    private static final Map<String, String> DICT_TITLES = buildTitles();
    private static final Set<String> GROUP_CODES = Set.of(
            "identification", "technical", "infrastructure",
            "economic", "applicability", "data_quality");
    private static final Set<String> DATA_TYPES = Set.of("number", "text", "boolean",
            "date");
    private static final int MAX_PAGE_SIZE = 200;
    private final IndustryRepository industryRepository;
    private final ProcessRepository processRepository;
    private final VendorRepository vendorRepository;
    private final RegionRepository regionRepository;
    private final SolutionTypeRepository solutionTypeRepository;
    private final SolutionSubtypeRepository solutionSubtypeRepository;
    private final CharacteristicTypeRepository characteristicTypeRepository;
    private final SolutionRepository solutionRepository;
    private final SolutionApplicationRepository applicationRepository;
    private final ObjectTypeIndustryRepository objectTypeIndustryRepository;
    private final SolutionTypeSubtypeMappingRepository mappingRepository;
    private final SolutionCharacteristicRepository characteristicRepository;
    /**
 * Конструктор со всеми репозиториями справочников (Spring DI).
 *
 * @param industryRepository отраслей
 * @param processRepository процессов
 * @param vendorRepository производителей
 * @param regionRepository регионов
 * @param solutionTypeRepository типов решений
 * @param solutionSubtypeRepository подтипов решений
 * @param characteristicTypeRepository типов ТТХ
 * @param solutionRepository решений каталога
 * @param applicationRepository применений решений
 * @param objectTypeIndustryRepository связей типов объектов и отраслей
 * @param mappingRepository связей типов и подтипов решений
 * @param characteristicRepository ТТХ решений
 */
    public AdminReferenceService(IndustryRepository industryRepository,
                                 ProcessRepository processRepository,
                                 VendorRepository vendorRepository,
                                 RegionRepository regionRepository,
                                 SolutionTypeRepository solutionTypeRepository,
                                 SolutionSubtypeRepository solutionSubtypeRepository,
                                 CharacteristicTypeRepository characteristicTypeRepository,
                                 SolutionRepository solutionRepository,
                                 SolutionApplicationRepository applicationRepository,
                                 ObjectTypeIndustryRepository objectTypeIndustryRepository,
                                 SolutionTypeSubtypeMappingRepository mappingRepository,
                                 SolutionCharacteristicRepository characteristicRepository) {
        this.industryRepository = industryRepository;
        this.processRepository = processRepository;
        this.vendorRepository = vendorRepository;
        this.regionRepository = regionRepository;
        this.solutionTypeRepository = solutionTypeRepository;
        this.solutionSubtypeRepository = solutionSubtypeRepository;
        this.characteristicTypeRepository = characteristicTypeRepository;
        this.solutionRepository = solutionRepository;
        this.applicationRepository = applicationRepository;
        this.objectTypeIndustryRepository = objectTypeIndustryRepository;
        this.mappingRepository = mappingRepository;
        this.characteristicRepository = characteristicRepository;
    }

    private static Map<String, String> buildTitles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("industry", "Отрасли");
        m.put("process", "Процессы");
        m.put("vendor", "Производители");
        m.put("region", "Регионы");
        m.put("solution_type", "Типы решений");
        m.put("solution_subtype", "Подтипы решений");
        m.put("characteristic_type", "Типы характеристик");
        return m;
    }

    // ==================================================================
    // Чтение
    // ==================================================================

    private static void requireDict(String dictCode) {
        if (dictCode == null || !DICT_CODES.contains(dictCode)) {
            throw new BadRequestException("Неизвестный справочник «"
                    + (dictCode == null ? "" : dictCode) + "». Доступны: "
                    + String.join(", ", DICT_CODES));
        }
    }

    private static ConflictException conflictName(String dictCode, String name) {
        return new ConflictException("Запись «" + name + "» уже есть в справочнике «"
                + DICT_TITLES.get(dictCode) + "»");
    }

    // ==================================================================
    // Создание
    // ==================================================================

    private static ConflictException conflictCode(String dictCode, String code) {
        return new ConflictException("Код «" + code + "» уже занят в справочнике «"
                + DICT_TITLES.get(dictCode) + "»");
    }

    // ==================================================================
    // Правка
    // ==================================================================

    private static java.util.function.Supplier<NotFoundException> notFound(
            String dictCode, Long id) {
        return () -> new NotFoundException("Запись с id=" + id
                + " не найдена в справочнике «" + DICT_TITLES.get(dictCode) + "»");
    }

    // ==================================================================
    // Удаление
    // ==================================================================

    private static AdminReferenceDto view(Industry entity) {
        return AdminReferenceDto.builder()
                .id(entity.getId()).code(entity.getCode()).name(entity.getName())
                .build();
    }

    private static AdminReferenceDto view(Process entity) {
        return AdminReferenceDto.builder()
                .id(entity.getId()).code(entity.getCode()).name(entity.getName())
                .isActive(entity.getIsActive()).build();
    }

    // ------------------------------------------------------------------
    // Внутренняя унификация доступа к справочникам
    // ------------------------------------------------------------------

    private static AdminReferenceDto view(Vendor entity) {
        return AdminReferenceDto.builder()
                .id(entity.getId()).code(null).name(entity.getName()).build();
    }

    private static AdminReferenceDto view(Region entity) {
        return AdminReferenceDto.builder()
                .id(entity.getId()).code(entity.getCode()).name(entity.getName())
                .build();
    }

    private static AdminReferenceDto view(SolutionType entity) {
        return AdminReferenceDto.builder()
                .id(entity.getId()).code(entity.getCode()).name(entity.getName())
                .build();
    }

    private static AdminReferenceDto view(SolutionSubtype entity) {
        return AdminReferenceDto.builder()
                .id(entity.getId()).code(entity.getCode()).name(entity.getName())
                .build();
    }

    private static AdminReferenceDto view(CharacteristicType entity) {
        return AdminReferenceDto.builder()
                .id(entity.getId()).code(entity.getCode()).name(entity.getName())
                .groupCode(entity.getGroupCode()).dataType(entity.getDataType())
                .unit(entity.getUnit()).build();
    }

    /**
 * Количество записей по каждому справочнику (для плиток страницы).
 *
 * @return карта код справочника → число записей
 */
    public Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String code : List.of("industry", "process", "vendor", "region",
                "solution_type", "solution_subtype", "characteristic_type")) {
            result.put(code, count(code));
        }
        return result;
    }

    /**
 * Список записей справочника: поиск + пагинация (в памяти — справочники малы).
 *
 * @param dictCode код справочника (industry/process/vendor/...)
 * @param q подстрока поиска по коду/имени (null — без фильтра)
 * @param page номер страницы (0-based)
 * @param size размер страницы (1..100)
 * @return страница записей справочника
 */
    public PageResponse<AdminReferenceDto> list(String dictCode, String q,
                                                int page, int size) {
        requireDict(dictCode);
        if (page < 0) {
            throw new BadRequestException("Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("Размер страницы: от 1 до " + MAX_PAGE_SIZE);
        }
        List<AdminReferenceDto> all = loadAll(dictCode);
        String needle = q == null ? null : AdminTextUtil.canonicalKey(q);
        if (needle != null) {
            all = all.stream().filter(v ->
                            (v.getName() != null && AdminTextUtil.canonicalKey(v.getName())
                                    .contains(needle))
                                    || (v.getCode() != null && AdminTextUtil.canonicalKey(
                                    v.getCode()).contains(needle)))
                    .toList();
        }
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = size == 0 ? 0 : (all.size() + size - 1) / size;
        return new PageResponse<>(all.subList(from, to), page, size, all.size(),
                totalPages);
    }

    /**
 * Создание записи справочника (201) — валидации по виду справочника.
 *
 * @param dictCode код справочника
 * @param request код/имя записи (у vendor кода нет)
 * @return созданная запись справочника
 */
    @Transactional
    public AdminReferenceDto create(String dictCode, AdminReferenceCreateRequest request) {
        requireDict(dictCode);
        String name = AdminTextUtil.normText(request.getName());
        if (name == null) {
            throw new BadRequestException("Название обязательно");
        }
        String code = AdminTextUtil.normText(request.getCode());
        if ("vendor".equals(dictCode)) {
            if (code != null) {
                throw new BadRequestException("Справочник «Производители» не "
                        + "использует код — заполните только название");
            }
        } else if (code == null) {
            throw new BadRequestException("Код записи обязателен для справочника «"
                    + DICT_TITLES.get(dictCode) + "»");
        }
        if (findByName(dictCode, name).isPresent()) {
            throw conflictName(dictCode, name);
        }
        if (!"vendor".equals(dictCode) && findByCode(dictCode, code).isPresent()) {
            throw conflictCode(dictCode, code);
        }
        return switch (dictCode) {
            case "industry" -> view(industryRepository.save(Industry.builder()
                    .code(code).name(name).build()));
            case "process" -> view(processRepository.save(Process.builder()
                    .code(code).name(name)
                    .isActive(request.getIsActive() == null || request.getIsActive())
                    .build()));
            case "vendor" -> view(vendorRepository.save(Vendor.builder()
                    .name(name).build()));
            case "region" -> view(regionRepository.save(Region.builder()
                    .code(code).name(name).build()));
            case "solution_type" -> view(solutionTypeRepository.save(SolutionType
                    .builder().code(code).name(name).build()));
            case "solution_subtype" -> view(solutionSubtypeRepository.save(
                    SolutionSubtype.builder().code(code).name(name).build()));
            case "characteristic_type" -> {
                String group = request.getGroupCode() == null ? null
                        : request.getGroupCode().trim();
                String dataType = request.getDataType() == null ? null
                        : request.getDataType().trim();
                if (group == null || !GROUP_CODES.contains(group)) {
                    throw new BadRequestException("Группа характеристики: один из "
                            + GROUP_CODES);
                }
                if (dataType == null || !DATA_TYPES.contains(dataType)) {
                    throw new BadRequestException("Тип значения: number, text, "
                            + "boolean или date");
                }
                yield view(characteristicTypeRepository.save(CharacteristicType
                        .builder()
                        .code(code).name(name)
                        .groupCode(group).dataType(dataType)
                        .unit(AdminTextUtil.normText(request.getUnit()))
                        .isFilterable(false)
                        .isRequired(false)
                        .sortOrder(0)
                        .build()));
            }
            default -> throw new BadRequestException("Неизвестный справочник");
        };
    }

    /**
 * Правка записи (200): код/имя/активность; уникальность проверяется.
 *
 * @param dictCode код справочника
 * @param id идентификатор записи
 * @param request новый код/имя (у vendor кода нет)
 * @return обновлённая запись справочника
 */
    @Transactional
    public AdminReferenceDto update(String dictCode, Long id,
                                    AdminReferenceUpdateRequest request) {
        requireDict(dictCode);
        // 404 прежде дублей: PUT несуществующей записи
        // с занятым кодом/именем должен отвечать «не найдено», а не 409
        if (!existsById(dictCode, id)) {
            throw notFound(dictCode, id).get();
        }
        String name = AdminTextUtil.normText(request.getName());
        if (name == null) {
            throw new BadRequestException("Название обязательно");
        }
        String code = AdminTextUtil.normText(request.getCode());
        if ("vendor".equals(dictCode)) {
            if (code != null) {
                throw new BadRequestException("Справочник «Производители» не "
                        + "использует код — заполните только название");
            }
        } else if (code == null) {
            throw new BadRequestException("Код записи обязателен для справочника «"
                    + DICT_TITLES.get(dictCode) + "»");
        }
        findByName(dictCode, name)
                .ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        throw conflictName(dictCode, name);
                    }
                });
        if (!"vendor".equals(dictCode)) {
            findByCode(dictCode, code)
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(id)) {
                            throw conflictCode(dictCode, code);
                        }
                    });
        }
        switch (dictCode) {
            case "industry" -> {
                Industry entity = industryRepository.findById(id).orElseThrow(notFound(
                        dictCode, id));
                entity.setCode(code);
                entity.setName(name);
                return view(industryRepository.save(entity));
            }
            case "process" -> {
                Process entity = processRepository.findById(id).orElseThrow(notFound(
                        dictCode, id));
                entity.setCode(code);
                entity.setName(name);
                entity.setIsActive(request.getIsActive() == null || request.getIsActive());
                return view(processRepository.save(entity));
            }
            case "vendor" -> {
                Vendor entity = vendorRepository.findById(id).orElseThrow(notFound(
                        dictCode, id));
                entity.setName(name);
                return view(vendorRepository.save(entity));
            }
            case "region" -> {
                Region entity = regionRepository.findById(id).orElseThrow(notFound(
                        dictCode, id));
                entity.setCode(code);
                entity.setName(name);
                return view(regionRepository.save(entity));
            }
            case "solution_type" -> {
                SolutionType entity = solutionTypeRepository.findById(id).orElseThrow(
                        notFound(dictCode, id));
                entity.setCode(code);
                entity.setName(name);
                return view(solutionTypeRepository.save(entity));
            }
            case "solution_subtype" -> {
                SolutionSubtype entity = solutionSubtypeRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                entity.setCode(code);
                entity.setName(name);
                return view(solutionSubtypeRepository.save(entity));
            }
            case "characteristic_type" -> {
                CharacteristicType entity = characteristicTypeRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                entity.setCode(code);
                entity.setName(name);
                // PUT не молча игнорирует метаданные
                // типа характеристики — применяет их с той же валидацией,
                // что и создание (CHECK схемы group_code/data_type)
                if (request.getGroupCode() != null) {
                    String group = request.getGroupCode().trim();
                    if (!GROUP_CODES.contains(group)) {
                        throw new BadRequestException("Группа характеристики: один из "
                                + GROUP_CODES);
                    }
                    entity.setGroupCode(group);
                }
                if (request.getDataType() != null) {
                    String dataType = request.getDataType().trim();
                    if (!DATA_TYPES.contains(dataType)) {
                        throw new BadRequestException("Тип значения: number, text, "
                                + "boolean или date");
                    }
                    entity.setDataType(dataType);
                }
                if (request.getUnit() != null) {
                    entity.setUnit(AdminTextUtil.normText(request.getUnit()));
                }
                return view(characteristicTypeRepository.save(entity));
            }
            default -> throw new BadRequestException("Неизвестный справочник");
        }
    }

    /**
 * Удаление записи (204) или 409 при FK-ссылках (data_model.md §11).
 *
 * @param dictCode код справочника
 * @param id идентификатор записи
 */
    @Transactional
    public void delete(String dictCode, Long id) {
        requireDict(dictCode);
        switch (dictCode) {
            case "industry" -> {
                Industry entity = industryRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                if (applicationRepository.existsByIndustryId(id)) {
                    throw new ConflictException("Отрасль «" + entity.getName()
                            + "» используется в применениях решений каталога");
                }
                if (objectTypeIndustryRepository.existsByIndustryId(id)) {
                    throw new ConflictException("Отрасль «" + entity.getName()
                            + "» связана с типами объектов");
                }
                industryRepository.delete(entity);
            }
            case "process" -> {
                Process entity = processRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                if (applicationRepository.existsByProcessId(id)) {
                    throw new ConflictException("Процесс «" + entity.getName()
                            + "» используется в применениях решений каталога");
                }
                processRepository.delete(entity);
            }
            case "vendor" -> {
                Vendor entity = vendorRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                if (solutionRepository.existsByVendorId(id)) {
                    throw new ConflictException("У производителя «" + entity.getName()
                            + "» есть решения в каталоге");
                }
                vendorRepository.delete(entity);
            }
            case "region" -> {
                Region entity = regionRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                if (solutionRepository.existsByRegionId(id)) {
                    throw new ConflictException("Регион «" + entity.getName()
                            + "» указан у решений каталога");
                }
                regionRepository.delete(entity);
            }
            case "solution_type" -> {
                SolutionType entity = solutionTypeRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                if (solutionRepository.existsBySolutionTypeId(id)) {
                    throw new ConflictException("Тип «" + entity.getName()
                            + "» указан у решений каталога");
                }
                if (mappingRepository.existsBySolutionTypeId(id)) {
                    throw new ConflictException("Тип «" + entity.getName()
                            + "» участвует в парах «тип-подтип»");
                }
                solutionTypeRepository.delete(entity);
            }
            case "solution_subtype" -> {
                SolutionSubtype entity = solutionSubtypeRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                if (solutionRepository.existsBySolutionSubtypeId(id)) {
                    throw new ConflictException("Подтип «" + entity.getName()
                            + "» указан у решений каталога");
                }
                if (mappingRepository.existsBySolutionSubtypeId(id)) {
                    throw new ConflictException("Подтип «" + entity.getName()
                            + "» участвует в парах «тип-подтип»");
                }
                solutionSubtypeRepository.delete(entity);
            }
            case "characteristic_type" -> {
                CharacteristicType entity = characteristicTypeRepository.findById(id)
                        .orElseThrow(notFound(dictCode, id));
                if (characteristicRepository.existsByCharacteristicTypeId(id)) {
                    throw new ConflictException("Тип характеристики «"
                            + entity.getName() + "» имеет значения у решений");
                }
                characteristicTypeRepository.delete(entity);
            }
            default -> throw new BadRequestException("Неизвестный справочник");
        }
    }

    /**
 * Массовое удаление записей справочника: каждая позиция
 * обрабатывается независимо (FK-ссылки дают отказ с причиной,
 * уже удалённые не откатываются). Дубликаты идентификаторов
 * игнорируются.
 *
 * @param dictCode код справочника
 * @param ids идентификаторы записей (1..100)
 * @return удалённые идентификаторы и причины отказов
 * @throws BadRequestException 400 — неизвестный справочник,
 * пустой список или null в списке
 */
    public BulkDeleteResultDto bulkDelete(String dictCode, List<Long> ids) {
        requireDict(dictCode);
        return BulkDeleteSupport.collect(
                BulkDeleteSupport.normalizeIds(ids),
                id -> delete(dictCode, id));
    }

    /**
 * Единая запись по имени (для проверки дублей).
 */
    private Optional<AdminReferenceDto> findByName(String dictCode, String name) {
        String needle = AdminTextUtil.canonicalKey(name);
        return loadAll(dictCode).stream()
                .filter(v -> AdminTextUtil.canonicalKey(v.getName()).equals(needle))
                .findFirst();
    }

    /**
 * Единая запись по коду (для проверки дублей).
 */
    private Optional<AdminReferenceDto> findByCode(String dictCode, String code) {
        String needle = AdminTextUtil.canonicalKey(code);
        return loadAll(dictCode).stream()
                .filter(v -> v.getCode() != null
                        && AdminTextUtil.canonicalKey(v.getCode()).equals(needle))
                .findFirst();
    }

    /**
 * Существует ли запись (404-проверка до поиска дублей).
 */
    private boolean existsById(String dictCode, Long id) {
        return switch (dictCode) {
            case "industry" -> industryRepository.existsById(id);
            case "process" -> processRepository.existsById(id);
            case "vendor" -> vendorRepository.existsById(id);
            case "region" -> regionRepository.existsById(id);
            case "solution_type" -> solutionTypeRepository.existsById(id);
            case "solution_subtype" -> solutionSubtypeRepository.existsById(id);
            case "characteristic_type" -> characteristicTypeRepository.existsById(id);
            default -> false;
        };
    }

    private long count(String dictCode) {
        return switch (dictCode) {
            case "industry" -> industryRepository.count();
            case "process" -> processRepository.count();
            case "vendor" -> vendorRepository.count();
            case "region" -> regionRepository.count();
            case "solution_type" -> solutionTypeRepository.count();
            case "solution_subtype" -> solutionSubtypeRepository.count();
            case "characteristic_type" -> characteristicTypeRepository.count();
            default -> 0;
        };
    }

    /**
 * Все записи справочника в универсальном виде (id по возрастанию).
 */
    private List<AdminReferenceDto> loadAll(String dictCode) {
        List<AdminReferenceDto> result = new ArrayList<>();
        switch (dictCode) {
            case "industry" -> industryRepository.findAllByOrderByIdAsc()
                    .forEach(i -> result.add(view(i)));
            case "process" -> processRepository.findAllByOrderByIdAsc()
                    .forEach(p -> result.add(view(p)));
            case "vendor" -> vendorRepository.findAllByOrderByIdAsc()
                    .forEach(v -> result.add(view(v)));
            case "region" -> regionRepository.findAllByOrderByIdAsc()
                    .forEach(r -> result.add(view(r)));
            case "solution_type" -> solutionTypeRepository.findAllByOrderByIdAsc()
                    .forEach(t -> result.add(view(t)));
            case "solution_subtype" -> solutionSubtypeRepository.findAllByOrderByIdAsc()
                    .forEach(st -> result.add(view(st)));
            case "characteristic_type" -> characteristicTypeRepository.findAllByOrderByIdAsc()
                    .forEach(ct -> result.add(view(ct)));
            default -> throw new BadRequestException("Неизвестный справочник");
        }
        return result;
    }
}
