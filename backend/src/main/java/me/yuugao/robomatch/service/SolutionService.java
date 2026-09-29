package me.yuugao.robomatch.service;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.domain.Process;
import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.mapper.SolutionMapper;
import me.yuugao.robomatch.repository.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

/**
 * Бизнес-логика каталога решений (
 * сортировка, сравнение + карточка с ТТХ, кейсами и провенансом).
 * <p>
 * СТРАТЕГИЯ ИМЁН СПРАВОЧНИКОВ (JOIN-стратегия):
 * страница решений читается одним JPQL-запросом с фильтрами (SolutionRepository.search —
 * портативно между PostgreSQL и H2-тестами), после чего имена справочников
 * резолвятся батчами findAllById — 4 коротких IN-запроса на страницу
 * (vendor, type, subtype, region), а не N+1 и не кэш:
 * - JOIN в основном запросе потребовал бы нативный SQL (JPQL не умеет
 * JOIN по Long-колонкам без @ManyToOne-ассоциаций) и лишил бы
 * переносимости между PostgreSQL и H2 (IT-тесты);
 * - кэш справочников не вводим: справочники крошечные (десятки строк),
 * а свежесть имён важна для админки — цена вопроса
 * 4 лишних запроса на страницу, уложенных в один RTT к БД.
 * <p>
 * Карточка и сравнение собираются фиксированным набором батч-запросов
 * (решения -> имена -> ТТХ+типы -> кейсы -> применения): независимо от
 * числа решений (до 10 в сравнении) это один и тот же набор, без N+1.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SolutionService {

    /**
 * Дефолт и максимум размера страницы каталога.
 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    /**
 * Границы сравнения: минимум по смыслу, максимум — читаемость таблицы.
 */
    static final int COMPARE_MIN = 2;
    static final int COMPARE_MAX = 10;

    private static final int TRL_MIN = 1;
    private static final int TRL_MAX = 9;

    private static final Set<String> STATUSES = Set.of("operation", "piloting", "rnd");
    private static final Set<String> SORT_DIRECTIONS = Set.of("asc", "desc");

    /**
 * Сортировка по nullable-колонкам (trl, payload_kg): NULL должен
 * уходить в конец при любом направлении, одинаково в PostgreSQL
 * (NULLS FIRST при DESC по умолчанию) и H2. Решение — COALESCE
 * с сентинелом: при asc NULL превращается в +бесконечность, при
 * desc — в -1. Выражение подставляется через JpaSort.unsafe.
 */
    private static final String COALESCE_TRL_EXPR = "COALESCE(s.trl, %d)";
    private static final String COALESCE_PAYLOAD_EXPR = "COALESCE(s.payloadKg, %s)";

    /**
 * Белый список полей сортировки: ключ API -> выражение JPQL.
 * created_at и payload_kg приходят в snake_case (совпадает с колонкой
 * БД) — сознательно, чтобы ключ API не расходился с очевидным именем
 * колонки в Swagger-документации.
 */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "name", "s.name",
            "price", "s.priceRub",
            "trl", COALESCE_TRL_EXPR,
            "payload_kg", COALESCE_PAYLOAD_EXPR,
            "created_at", "s.createdAt");

    private final SolutionRepository solutionRepository;
    private final VendorRepository vendorRepository;
    private final SolutionTypeRepository solutionTypeRepository;
    private final SolutionSubtypeRepository solutionSubtypeRepository;
    private final RegionRepository regionRepository;
    private final IndustryRepository industryRepository;
    private final ProcessRepository processRepository;
    private final SolutionApplicationRepository solutionApplicationRepository;
    private final SolutionCharacteristicRepository solutionCharacteristicRepository;
    private final CharacteristicTypeRepository characteristicTypeRepository;
    private final SolutionCaseRepository solutionCaseRepository;
    private final SolutionCaseLinkRepository solutionCaseLinkRepository;
    private final SolutionTypeSubtypeMappingRepository solutionTypeSubtypeMappingRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final SolutionMapper solutionMapper;

    /**
 * Список решений с фильтрами, поиском, сортировкой и пагинацией.
 *
 * @param query параметры каталога (фильтры/поиск/сортировка/страница)
 * @return страница решений каталога
 */
    public PageResponse<SolutionSummaryDto> search(CatalogQuery query) {
        CatalogQuery safe = validateAndNormalize(query);
        Pageable pageable = PageRequest.of(safe.page(), safe.size(), buildSort(safe));
        Page<Solution> page = solutionRepository.search(
                escapeLike(blankToNull(safe.q())),
                safe.typeId(), safe.subtypeId(), safe.industryId(), safe.processId(),
                safe.status(), safe.trlMin(), safe.trlMax(),
                safe.priceMin(), safe.priceMax(), safe.payloadMin(),
                pageable);
        Map<Long, CatalogNames> names = resolveNames(page.getContent());
        List<SolutionSummaryDto> content = page.getContent().stream()
                .map(solution -> solutionMapper.toSummary(solution,
                        names.getOrDefault(solution.getId(), CatalogNames.empty())))
                .toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /**
 * Карточка решения: базовые поля + имена + ТТХ + кейсы + применения.
 *
 * @param id идентификатор решения
 * @return полная карточка решения
 */
    public SolutionFullDto getSolutionById(Long id) {
        Solution solution = solutionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Решение с id=" + id + " не найдено"));
        return assembleFull(List.of(solution)).get(0);
    }

    /**
 * Сравнение решений: 2-10 полных карточек.
 * Проверки идут по множеству уникальных id: дубликаты схлопываются
 * (одно решение дважды в таблице бессмысленно), поэтому 11 значений
 * с 10 уникальными допустимы, а 11 уникальных — уже нет. Порядок
 * выдачи — порядок запроса. Любой неизвестный id — 404: сравнивать
 * «то, что нашлось» молча некорректно.
 *
 * @param ids идентификаторы решений для сравнения (2-10 уникальных)
 * @return полные карточки в порядке запроса
 */
    public List<SolutionFullDto> compare(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BadRequestException(
                    "Укажите от " + COMPARE_MIN + " до " + COMPARE_MAX
                            + " решений для сравнения");
        }
        Set<Long> unique = new LinkedHashSet<>(ids);
        if (unique.size() > COMPARE_MAX) {
            throw new BadRequestException(
                    "Для сравнения доступно не более " + COMPARE_MAX + " решений");
        }
        if (unique.size() < COMPARE_MIN) {
            throw new BadRequestException("Для сравнения нужно минимум " + COMPARE_MIN
                    + " разных решения");
        }
        List<Solution> found = solutionRepository.findAllById(unique);
        if (found.size() != unique.size()) {
            Set<Long> foundIds = found.stream().map(Solution::getId).collect(Collectors.toSet());
            List<Long> missing = unique.stream().filter(id -> !foundIds.contains(id)).toList();
            throw new NotFoundException("Решения не найдены: id=" + missing);
        }
        Map<Long, Solution> byId = found.stream()
                .collect(Collectors.toMap(Solution::getId, Function.identity()));
        List<Solution> ordered = unique.stream().map(byId::get).toList();
        return assembleFull(ordered);
    }

    /**
 * Словари и диапазоны для UI (каталог + типы объектов для проектов).
 *
 * @return словари фильтров и диапазоны цен/TRL
 */
    public FiltersDto getFilters() {
        SolutionRepository.CatalogRanges ranges = solutionRepository.findPriceTrlRanges();
        return new FiltersDto(
                mapDict(solutionTypeRepository.findAll(), SolutionType::getId,
                        SolutionType::getCode, SolutionType::getName),
                mapDict(objectTypeRepository.findAll(), ObjectType::getId,
                        ObjectType::getCode, ObjectType::getName),
                mapDict(solutionSubtypeRepository.findAll(), SolutionSubtype::getId,
                        SolutionSubtype::getCode, SolutionSubtype::getName),
                mapDict(industryRepository.findAll(), Industry::getId,
                        Industry::getCode, Industry::getName),
                mapDict(processRepository.findAll(), Process::getId,
                        Process::getCode, Process::getName),
                List.copyOf(STATUSES.stream().sorted().toList()),
                solutionTypeSubtypeMappingRepository.findAll().stream()
                        .map(m -> new TypeSubtypeLinkDto(m.getSolutionTypeId(),
                                m.getSolutionSubtypeId()))
                        .toList(),
                ranges == null ? null : ranges.getMinPrice(),
                ranges == null ? null : ranges.getMaxPrice(),
                ranges == null || ranges.getMinTrl() == null ? null : ranges.getMinTrl().intValue(),
                ranges == null || ranges.getMaxTrl() == null ? null : ranges.getMaxTrl().intValue());
    }

    // ------------------------------------------------------------------
    // Валидация параметров списка
    // ------------------------------------------------------------------

    private CatalogQuery validateAndNormalize(CatalogQuery query) {
        String sortBy = query.sortBy() == null ? "name" : query.sortBy();
        if (!SORT_FIELDS.containsKey(sortBy)) {
            throw new BadRequestException(
                    "Недопустимое значение sortBy: " + sortBy
                            + " (доступно: name, price, trl, payload_kg, created_at)");
        }
        String sortDir = query.sortDir() == null ? "asc" : query.sortDir();
        if (!SORT_DIRECTIONS.contains(sortDir)) {
            throw new BadRequestException("Недопустимое значение sortDir: " + sortDir
                    + " (доступно: asc, desc)");
        }
        if (query.status() != null && !STATUSES.contains(query.status())) {
            throw new BadRequestException("Недопустимое значение status: " + query.status()
                    + " (доступно: operation, piloting, rnd)");
        }
        if (query.trlMin() != null && (query.trlMin() < TRL_MIN || query.trlMin() > TRL_MAX)) {
            throw new BadRequestException("trlMin должен быть от " + TRL_MIN + " до " + TRL_MAX);
        }
        if (query.trlMax() != null && (query.trlMax() < TRL_MIN || query.trlMax() > TRL_MAX)) {
            throw new BadRequestException("trlMax должен быть от " + TRL_MIN + " до " + TRL_MAX);
        }
        if (query.trlMin() != null && query.trlMax() != null && query.trlMin() > query.trlMax()) {
            throw new BadRequestException("trlMin не может быть больше trlMax");
        }
        if (query.priceMin() != null && query.priceMin().signum() < 0) {
            throw new BadRequestException("priceMin не может быть отрицательным");
        }
        if (query.priceMax() != null && query.priceMax().signum() < 0) {
            throw new BadRequestException("priceMax не может быть отрицательным");
        }
        if (query.priceMin() != null && query.priceMax() != null
                && query.priceMin().compareTo(query.priceMax()) > 0) {
            throw new BadRequestException("priceMin не может быть больше priceMax");
        }
        if (query.payloadMin() != null && query.payloadMin().signum() < 0) {
            throw new BadRequestException("payloadMin не может быть отрицательным");
        }
        int page = query.page() == null ? 0 : query.page();
        if (page < 0) {
            throw new BadRequestException("Параметр page не может быть отрицательным");
        }
        int size = query.size() == null ? DEFAULT_PAGE_SIZE : query.size();
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("Параметр size должен быть от 1 до " + MAX_PAGE_SIZE);
        }
        return new CatalogQuery(blankToNull(query.q()), query.typeId(), query.subtypeId(),
                query.industryId(), query.processId(), query.status(), query.trlMin(),
                query.trlMax(), query.priceMin(), query.priceMax(), query.payloadMin(),
                sortBy, sortDir, page, size);
    }

    /**
 * Сортировка: для nullable-полей — COALESCE-сентинел через JpaSort.unsafe.
 * Вторичный ключ s.id — детерминированный порядок при равных значениях
 * первичного ключа (иначе записи «прыгают» между страницами пагинации;
 * name не UNIQUE, price/trl могут совпадать).
 */
    private Sort buildSort(CatalogQuery query) {
        String key = query.sortBy();
        boolean asc = "asc".equals(query.sortDir());
        Sort.Direction direction = asc ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort secondary = Sort.by("s.id");
        if ("trl".equals(key)) {
            // asc: NULL = 10 (за пределами шкалы 1-9) -> в конец; desc: NULL = 0.
            String expr = String.format(COALESCE_TRL_EXPR, asc ? TRL_MAX + 1 : 0);
            return JpaSort.unsafe(direction, expr).and(secondary);
        }
        if ("payload_kg".equals(key)) {
            // asc: NULL = +1e9 -> в конец; desc: NULL = -1.
            String expr = String.format(COALESCE_PAYLOAD_EXPR, asc ? "1000000000" : "-1");
            return JpaSort.unsafe(direction, expr).and(secondary);
        }
        return Sort.by(direction, SORT_FIELDS.get(key)).and(secondary);
    }

    // ------------------------------------------------------------------
    // Сборка карточек (батч-загрузка без N+1)
    // ------------------------------------------------------------------

    /**
 * Полные DTO для набора решений фиксированным числом запросов.
 */
    private List<SolutionFullDto> assembleFull(List<Solution> solutions) {
        List<Long> ids = solutions.stream().map(Solution::getId).toList();
        Map<Long, CatalogNames> names = resolveNames(solutions);

        // ТТХ + метаданные типов характеристик
        Map<Long, List<SolutionCharacteristic>> characteristicsBySolution =
                solutionCharacteristicRepository.findBySolutionIdIn(ids).stream()
                        .collect(Collectors.groupingBy(SolutionCharacteristic::getSolutionId));
        Set<Long> typeIds = characteristicsBySolution.values().stream()
                .flatMap(List::stream)
                .map(SolutionCharacteristic::getCharacteristicTypeId)
                .collect(Collectors.toSet());
        Map<Long, CharacteristicType> typeById = typeIds.isEmpty() ? Map.of()
                : characteristicTypeRepository.findAllById(typeIds).stream()
                .collect(Collectors.toMap(CharacteristicType::getId, Function.identity()));

        // Кейсы: связи -> сами кейсы
        Map<Long, List<SolutionCaseLink>> linksBySolution =
                solutionCaseLinkRepository.findBySolutionIdIn(ids).stream()
                        .collect(Collectors.groupingBy(SolutionCaseLink::getSolutionId));
        Set<Long> caseIds = linksBySolution.values().stream()
                .flatMap(List::stream)
                .map(SolutionCaseLink::getCaseId)
                .collect(Collectors.toSet());
        Map<Long, SolutionCase> caseById = caseIds.isEmpty() ? Map.of()
                : solutionCaseRepository.findAllById(caseIds).stream()
                .collect(Collectors.toMap(SolutionCase::getId, Function.identity()));

        // Применения + имена отраслей и процессов
        Map<Long, List<SolutionApplication>> applicationsBySolution =
                solutionApplicationRepository.findBySolutionIdIn(ids).stream()
                        .collect(Collectors.groupingBy(SolutionApplication::getSolutionId));
        Set<Long> industryIds = applicationsBySolution.values().stream()
                .flatMap(List::stream)
                .map(SolutionApplication::getIndustryId)
                .collect(Collectors.toSet());
        Map<Long, Industry> industryById = industryIds.isEmpty() ? Map.of()
                : industryRepository.findAllById(industryIds).stream()
                .collect(Collectors.toMap(Industry::getId, Function.identity()));
        Set<Long> processIds = applicationsBySolution.values().stream()
                .flatMap(List::stream)
                .map(SolutionApplication::getProcessId)
                .collect(Collectors.toSet());
        Map<Long, Process> processById = processIds.isEmpty() ? Map.of()
                : processRepository.findAllById(processIds).stream()
                .collect(Collectors.toMap(Process::getId, Function.identity()));

        List<SolutionFullDto> result = new ArrayList<>(solutions.size());
        for (Solution solution : solutions) {
            Long id = solution.getId();
            List<SolutionCharacteristicDto> characteristics =
                    characteristicsBySolution.getOrDefault(id, List.of()).stream()
                            .map(sc -> toCharacteristicDto(sc,
                                    typeById.get(sc.getCharacteristicTypeId())))
                            .sorted(Comparator
                                    .comparing((SolutionCharacteristicDto dto) ->
                                            Objects.toString(dto.getGroupCode(), "zzz"))
                                    .thenComparing(dto ->
                                            Objects.toString(dto.getTypeName(), "")))
                            .toList();
            List<SolutionCaseDto> cases = linksBySolution.getOrDefault(id, List.of()).stream()
                    .map(link -> caseById.get(link.getCaseId()))
                    .filter(Objects::nonNull)
                    .map(this::toCaseDto)
                    .toList();
            List<SolutionApplicationDto> applications =
                    applicationsBySolution.getOrDefault(id, List.of()).stream()
                            .map(app -> toApplicationDto(app,
                                    industryById.get(app.getIndustryId()),
                                    processById.get(app.getProcessId())))
                            .toList();
            result.add(solutionMapper.toFull(solution,
                    names.getOrDefault(id, CatalogNames.empty()),
                    characteristics, cases, applications));
        }
        return result;
    }

    /**
 * Батч-резолюция имён vendor/type/subtype/region (4 IN-запроса).
 */
    private Map<Long, CatalogNames> resolveNames(List<Solution> solutions) {
        Set<Long> vendorIds = collect(solutions, Solution::getVendorId);
        Map<Long, String> vendorNames = vendorIds.isEmpty() ? Map.of()
                : nameById(vendorIds, vendorRepository.findAllById(vendorIds),
                Vendor::getId, Vendor::getName);
        Set<Long> typeIds = collect(solutions, Solution::getSolutionTypeId);
        Map<Long, String> typeNames = typeIds.isEmpty() ? Map.of()
                : nameById(typeIds, solutionTypeRepository.findAllById(typeIds),
                SolutionType::getId, SolutionType::getName);
        Set<Long> subtypeIds = collect(solutions, Solution::getSolutionSubtypeId);
        Map<Long, String> subtypeNames = subtypeIds.isEmpty() ? Map.of()
                : nameById(subtypeIds, solutionSubtypeRepository.findAllById(subtypeIds),
                SolutionSubtype::getId, SolutionSubtype::getName);
        Set<Long> regionIds = collect(solutions, Solution::getRegionId);
        Map<Long, String> regionNames = regionIds.isEmpty() ? Map.of()
                : nameById(regionIds, regionRepository.findAllById(regionIds),
                Region::getId, Region::getName);
        Map<Long, CatalogNames> result = new HashMap<>();
        for (Solution solution : solutions) {
            result.put(solution.getId(), new CatalogNames(
                    vendorNames.get(solution.getVendorId()),
                    solution.getSolutionTypeId() == null ? null
                            : typeNames.get(solution.getSolutionTypeId()),
                    solution.getSolutionSubtypeId() == null ? null
                            : subtypeNames.get(solution.getSolutionSubtypeId()),
                    solution.getRegionId() == null ? null
                            : regionNames.get(solution.getRegionId())));
        }
        return result;
    }

    private Set<Long> collect(List<Solution> solutions, Function<Solution, Long> getter) {
        return solutions.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private <T> Map<Long, String> nameById(Set<Long> ids, List<T> entities,
                                           Function<T, Long> idGetter,
                                           Function<T, String> nameGetter) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return entities.stream().collect(Collectors.toMap(idGetter, nameGetter));
    }

    private <T> List<DictItemDto> mapDict(List<T> entities, Function<T, Long> id,
                                          Function<T, String> code, Function<T, String> name) {
        return entities.stream().map(e -> new DictItemDto(id.apply(e), code.apply(e),
                name.apply(e))).toList();
    }

    private SolutionCharacteristicDto toCharacteristicDto(SolutionCharacteristic sc,
                                                          CharacteristicType type) {
        return SolutionCharacteristicDto.builder()
                .solutionId(sc.getSolutionId())
                .typeCode(type == null ? null : type.getCode())
                .typeName(type == null ? null : type.getName())
                .groupCode(type == null ? null : type.getGroupCode())
                .unit(type == null ? null : type.getUnit())
                .dataType(type == null ? null : type.getDataType())
                .valueNumeric(sc.getValueNumeric())
                .valueText(sc.getValueText())
                .valueBool(sc.getValueBool())
                .valueDate(sc.getValueDate())
                .sourceKind(sc.getSourceKind())
                .sourceUrl(sc.getSourceUrl())
                .sourceDate(sc.getSourceDate())
                .isConfirmed(sc.getIsConfirmed())
                .build();
    }

    private SolutionCaseDto toCaseDto(SolutionCase sc) {
        return SolutionCaseDto.builder()
                .id(sc.getId())
                .name(sc.getName())
                .description(sc.getDescription())
                .sourceUrl(sc.getSourceUrl())
                .sourceDate(sc.getSourceDate())
                .build();
    }

    private SolutionApplicationDto toApplicationDto(SolutionApplication application,
                                                    Industry industry, Process process) {
        return SolutionApplicationDto.builder()
                .industryId(application.getIndustryId())
                .industryName(industry == null ? null : industry.getName())
                .processId(application.getProcessId())
                .processName(process == null ? null : process.getName())
                .offerPriceRub(application.getOfferPriceRub())
                .build();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
 * Экранирование LIKE-вилдкардов в поисковом запросе (%, _, \) —
 * без экранирования q="%" возвращал весь каталог.
 * Символ экранирования — "\", задан в JPQL предложением ESCAPE.
 */
    private String escapeLike(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
