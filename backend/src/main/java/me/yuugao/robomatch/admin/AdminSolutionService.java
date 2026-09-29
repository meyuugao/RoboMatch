package me.yuugao.robomatch.admin;

import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.mapper.SolutionMapper;
import me.yuugao.robomatch.repository.*;
import me.yuugao.robomatch.service.SolutionService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CRUD каталога решений для администратора (; доступ -
 * /api/admin/** под hasRole('ADMIN'), SecurityConfig).
 * <p>
 * Провенанс: ручное создание/правка -> source_kind='manual'
 * (не подтверждено источником; такие карточки видны в админке фильтром
 * «требуют проверки»), source_url=NULL, source_date=дата операции.
 * <p>
 * Удаление (data_model.md §11 - «не рушим чужой контекст»): применения,
 * ТТХ и кейсы ссылаются CASCADE, но сценарии (scenario_solution) и
 * результаты подбора (selection_result) - RESTRICT: пока решение
 * участвует в чужом проекте, удалять нельзя -> 409 с подсказкой.
 */
@Service
public class AdminSolutionService {

    /**
 * Ручной провенанс.
 */
    private static final String MANUAL_SOURCE = "manual";

    private static final int MAX_PAGE_SIZE = 100;

    private final SolutionRepository solutionRepository;
    private final VendorRepository vendorRepository;
    private final SolutionTypeRepository solutionTypeRepository;
    private final SolutionSubtypeRepository solutionSubtypeRepository;
    private final RegionRepository regionRepository;
    private final ScenarioSolutionRepository scenarioSolutionRepository;
    private final SelectionResultRepository selectionResultRepository;
    private final SolutionService solutionService;
    private final SolutionMapper solutionMapper;

    /**
 * Конструктор с зависимостями (Spring DI).
 *
 * @param solutionRepository решений каталога
 * @param vendorRepository производителей
 * @param solutionTypeRepository типов решений
 * @param solutionSubtypeRepository подтипов решений
 * @param regionRepository регионов
 * @param scenarioSolutionRepository составов сценариев
 * @param selectionResultRepository результатов подбора
 * @param solutionService публичная сборка карточки
 * @param solutionMapper маппер в DTO
 */
    public AdminSolutionService(SolutionRepository solutionRepository,
                                VendorRepository vendorRepository,
                                SolutionTypeRepository solutionTypeRepository,
                                SolutionSubtypeRepository solutionSubtypeRepository,
                                RegionRepository regionRepository,
                                ScenarioSolutionRepository scenarioSolutionRepository,
                                SelectionResultRepository selectionResultRepository,
                                SolutionService solutionService,
                                SolutionMapper solutionMapper) {
        this.solutionRepository = solutionRepository;
        this.vendorRepository = vendorRepository;
        this.solutionTypeRepository = solutionTypeRepository;
        this.solutionSubtypeRepository = solutionSubtypeRepository;
        this.regionRepository = regionRepository;
        this.scenarioSolutionRepository = scenarioSolutionRepository;
        this.selectionResultRepository = selectionResultRepository;
        this.solutionService = solutionService;
        this.solutionMapper = solutionMapper;
    }

    private static Short toShort(Integer value, String label) {
        if (value == null) {
            return null;
        }
        return (short) value.intValue();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
 * Экранирование LIKE-вилдкардов (как SolutionService.escapeLike).
 */
    private static String escapeLike(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
 * Список для админки: поиск по названию + фильтр «требуют проверки».
 *
 * @param q подстрока поиска по названию (null - без фильтра)
 * @param needsCheck только решения с провенансом manual
 * @param page номер страницы (0-based)
 * @param size размер страницы (1..100)
 * @return страница решений для таблицы админки
 */
    public PageResponse<SolutionSummaryDto> list(String q, boolean needsCheck,
                                                 int page, int size) {
        if (page < 0) {
            throw new BadRequestException("Номер страницы не может быть отрицательным");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("Размер страницы: от 1 до " + MAX_PAGE_SIZE);
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("id")));
        String search = escapeLike(blankToNull(q));
        Page<Solution> result = solutionRepository.adminSearch(search,
                needsCheck ? MANUAL_SOURCE : null, pageable);
        Map<Long, CatalogNames> names = resolveNames(result.getContent());
        List<SolutionSummaryDto> content = result.getContent().stream()
                .map(s -> solutionMapper.toSummary(s,
                        names.getOrDefault(s.getId(), CatalogNames.empty())))
                .toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /**
 * Карточка для формы редактирования (публичная логика сборки).
 *
 * @param id идентификатор решения
 * @return полная карточка решения
 */
    public SolutionFullDto get(Long id) {
        return solutionService.getSolutionById(id);
    }

    /**
 * Создание карточки вручную.
 *
 * @param request поля новой карточки (провенанс станет manual)
 * @return созданная карточка решения
 */
    @Transactional
    public SolutionFullDto create(AdminSolutionCreateRequest request) {
        Long vendorId = requireVendor(request.getVendorId());
        Long typeId = optionalType(request.getSolutionTypeId());
        Long subtypeId = optionalSubtype(request.getSolutionSubtypeId());
        Long regionId = optionalRegion(request.getRegionId());

        solutionRepository.findByVendorIdAndName(vendorId, request.getName())
                .ifPresent(existing -> {
                    throw new ConflictException("Решение «" + request.getName()
                            + "» у этого производителя уже есть (id="
                            + existing.getId() + ")");
                });

        Solution solution = Solution.builder()
                .name(request.getName())
                .vendorId(vendorId)
                .productClass(request.getProductClass())
                .solutionTypeId(typeId)
                .solutionSubtypeId(subtypeId)
                .regionId(regionId)
                .status(request.getStatus())
                .description(blankToNull(request.getDescription()))
                .priceRub(request.getPriceRub())
                .trl(toShort(request.getTrl(), "УГТ"))
                .marketPotential(request.getMarketPotential())
                .payloadKg(request.getPayloadKg())
                .massKg(request.getMassKg())
                .lengthMm(request.getLengthMm())
                .widthMm(request.getWidthMm())
                .heightMm(request.getHeightMm())
                .positioningAccuracyMm(request.getPositioningAccuracyMm())
                .speedMs(request.getSpeedMs())
                .chargingPowerKw(request.getChargingPowerKw())
                .noiseLevelDba(request.getNoiseLevelDba())
                .sourceKind(MANUAL_SOURCE)
                .sourceUrl(null)
                .sourceDate(LocalDate.now())
                .build();
        Long id = solutionRepository.save(solution).getId();
        return solutionService.getSolutionById(id);
    }

    // ------------------------------------------------------------------
    // Валидации ссылок и значений
    // ------------------------------------------------------------------

    /**
 * Правка карточки: провенанс становится manual.
 *
 * @param id идентификатор решения
 * @param request новые поля карточки
 * @return обновлённая карточка решения
 */
    @Transactional
    public SolutionFullDto update(Long id, AdminSolutionUpdateRequest request) {
        Solution solution = solutionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        "Решение с id=" + id + " не найдено"));
        Long vendorId = requireVendor(request.getVendorId());
        Long typeId = optionalType(request.getSolutionTypeId());
        Long subtypeId = optionalSubtype(request.getSolutionSubtypeId());
        Long regionId = optionalRegion(request.getRegionId());

        solutionRepository.findByVendorIdAndName(vendorId, request.getName())
                .ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        throw new ConflictException("Решение «" + request.getName()
                                + "» у этого производителя уже есть (id="
                                + existing.getId() + ")");
                    }
                });

        solution.setName(request.getName());
        solution.setVendorId(vendorId);
        solution.setProductClass(request.getProductClass());
        solution.setSolutionTypeId(typeId);
        solution.setSolutionSubtypeId(subtypeId);
        solution.setRegionId(regionId);
        solution.setStatus(request.getStatus());
        solution.setDescription(blankToNull(request.getDescription()));
        solution.setPriceRub(request.getPriceRub());
        solution.setTrl(toShort(request.getTrl(), "УГТ"));
        solution.setMarketPotential(request.getMarketPotential());
        solution.setPayloadKg(request.getPayloadKg());
        solution.setMassKg(request.getMassKg());
        solution.setLengthMm(request.getLengthMm());
        solution.setWidthMm(request.getWidthMm());
        solution.setHeightMm(request.getHeightMm());
        solution.setPositioningAccuracyMm(request.getPositioningAccuracyMm());
        solution.setSpeedMs(request.getSpeedMs());
        solution.setChargingPowerKw(request.getChargingPowerKw());
        solution.setNoiseLevelDba(request.getNoiseLevelDba());
        solution.setSourceKind(MANUAL_SOURCE);
        solution.setSourceUrl(null);
        solution.setSourceDate(LocalDate.now());
        solutionRepository.save(solution);
        return solutionService.getSolutionById(id);
    }

    /**
 * Удаление: 409, пока на решение ссылаются сценарии или результаты
 * подбора (RESTRICT в схеме, data_model.md §11).
 *
 * @param id идентификатор решения
 */
    @Transactional
    public void delete(Long id) {
        Solution solution = solutionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        "Решение с id=" + id + " не найдено"));
        if (scenarioSolutionRepository.existsBySolutionId(id)) {
            throw new ConflictException("Решение «" + solution.getName()
                    + "» добавлено в сценарии проектов - сначала уберите его "
                    + "из сценариев");
        }
        if (selectionResultRepository.existsBySolutionId(id)) {
            throw new ConflictException("Решение «" + solution.getName()
                    + "» фигурирует в результатах подбора проектов - удаление "
                    + "нарушит историю расчётов");
        }
        solutionRepository.delete(solution);
    }

    /**
 * Массовое удаление решений: каждая позиция обрабатывается
 * независимо (409-причины одиночного delete попадают в failed,
 * уже удалённые не откатываются). Дубликаты идентификаторов
 * игнорируются.
 *
 * @param ids идентификаторы решений (1..100)
 * @return удалённые идентификаторы и причины отказов
 * @throws BadRequestException 400 - список пуст или содержит null
 */
    public BulkDeleteResultDto bulkDelete(List<Long> ids) {
        return BulkDeleteSupport.collect(
                BulkDeleteSupport.normalizeIds(ids), this::delete);
    }

    private Long requireVendor(Long vendorId) {
        if (vendorId == null) {
            throw new BadRequestException("Производитель (vendor) обязателен");
        }
        if (!vendorRepository.existsById(vendorId)) {
            throw new BadRequestException("Производитель с id=" + vendorId
                    + " не найден в справочнике");
        }
        return vendorId;
    }

    private Long optionalType(Long typeId) {
        if (typeId == null) {
            return null;
        }
        if (!solutionTypeRepository.existsById(typeId)) {
            throw new BadRequestException("Тип решения с id=" + typeId
                    + " не найден в справочнике");
        }
        return typeId;
    }

    private Long optionalSubtype(Long subtypeId) {
        if (subtypeId == null) {
            return null;
        }
        if (!solutionSubtypeRepository.existsById(subtypeId)) {
            throw new BadRequestException("Подтип решения с id=" + subtypeId
                    + " не найден в справочнике");
        }
        return subtypeId;
    }

    private Long optionalRegion(Long regionId) {
        if (regionId == null) {
            return null;
        }
        if (!regionRepository.existsById(regionId)) {
            throw new BadRequestException("Регион с id=" + regionId
                    + " не найден в справочнике");
        }
        return regionId;
    }

    /**
 * Имена справочников для списка (упрощённый resolveNames).
 */
    private Map<Long, CatalogNames> resolveNames(List<Solution> solutions) {
        Set<Long> vendorIds = new java.util.HashSet<>();
        Set<Long> typeIds = new java.util.HashSet<>();
        Set<Long> subtypeIds = new java.util.HashSet<>();
        Set<Long> regionIds = new java.util.HashSet<>();
        for (Solution s : solutions) {
            vendorIds.add(s.getVendorId());
            if (s.getSolutionTypeId() != null) {
                typeIds.add(s.getSolutionTypeId());
            }
            if (s.getSolutionSubtypeId() != null) {
                subtypeIds.add(s.getSolutionSubtypeId());
            }
            if (s.getRegionId() != null) {
                regionIds.add(s.getRegionId());
            }
        }
        Map<Long, String> vendorNames = new HashMap<>();
        vendorRepository.findAllById(vendorIds).forEach(v ->
                vendorNames.put(v.getId(), v.getName()));
        Map<Long, String> typeNames = new HashMap<>();
        solutionTypeRepository.findAllById(typeIds).forEach(t ->
                typeNames.put(t.getId(), t.getName()));
        Map<Long, String> subtypeNames = new HashMap<>();
        solutionSubtypeRepository.findAllById(subtypeIds).forEach(st ->
                subtypeNames.put(st.getId(), st.getName()));
        Map<Long, String> regionNames = new HashMap<>();
        regionRepository.findAllById(regionIds).forEach(r ->
                regionNames.put(r.getId(), r.getName()));
        Map<Long, CatalogNames> result = new HashMap<>();
        for (Solution s : solutions) {
            result.put(s.getId(), new CatalogNames(
                    vendorNames.get(s.getVendorId()),
                    s.getSolutionTypeId() == null ? null
                            : typeNames.get(s.getSolutionTypeId()),
                    s.getSolutionSubtypeId() == null ? null
                            : subtypeNames.get(s.getSolutionSubtypeId()),
                    s.getRegionId() == null ? null : regionNames.get(s.getRegionId())));
        }
        return result;
    }
}
