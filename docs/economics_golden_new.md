# Экономика: эталон демо-проектов из тестовых фикстур (golden new)

Назначение: эталон E2E-верификации демо-проектов «Демо: *» - проекты создаются seed
(`scripts/seed/repositories/test_projects_repo.py`, владелец - демо-
аккаунт `user`), состав - ТОЛЬКО решения тестовых фикстур
`docs/test-fixtures/` (импорт через админку, в seed фикстуры не
попадают - см. docs/test-fixtures/README.md). Автосверка -
`scripts/verify_economics_new.py` (эталон читается из «Машинного
эталона» ниже); скриншоты - docs/examples/demo_projects/.

Ключевые инварианты демо-проектов:

- подбор находит решения фикстур, статус **fit** (нет исключений по
  грузоподъёмности/габаритам/мощности для составов);
- `selectedRobots == requiredRobots` - `underpowered = false`,
  `overpowered = false`;
- экономика в разумных пределах: ROI ≤ 500 % (без предупреждения
  о завышенном baseline ФОТ), Payback ≤ 5 лет, `Effect_year > 0`.

## 1. Общие входы

Цены фикстур (docs/test-fixtures, с НДС): РТ-Паллета 1500 -
2 990 000 руб. (payload 1500 кг, 2.0 м/с, 9.5 кВт зарядка,
lifecycle 7 лет); РТ-Комплект 800 - 1 750 000 руб. (800 кг, 1.8 м/с);
АТ-Паллета 2500 - 4 100 000 руб. (2500 кг, 1.7 м/с, lifecycle 8);
АТ-Штабелер 1000 - 2 400 000 руб. (1000 кг, 1.5 м/с, lifecycle 7).

Допущения (дефолты каталога + проектные): K_load = 0.75,
K_availability = 1.0, K_reserve = 0.15, Ratio_infra = 0.35,
Tariff = 7.0 руб./кВт·ч, C_infra 10 %, C_software 15 %,
C_integration 15 %, C_commissioning 7.5 %, C_training 3 %,
C_service 10 %, C_licenses 5 %, C_consumables 2 %, C_repair 3.5 %,
C_communication 36 000 руб./робот/год, амортизация 6 лет (линейная -
срок службы у фикстур задан в ТТХ lifecycle_years 7/8/6/7, но
амортизация идёт по допущению каталога), горизонт 5 лет, RaaS fixed
2 %/мес от цены робота, контракт 3 года, без выкупа; P_nominal -
допущение проекта (90/90/25 оп/ч - см. README demo_projects).
K_начислений = 1.302.

## 2. «Демо: Оптимальный склад» - покупка (РТ-Паллета 1500 × 5)

```
Peak_demand = (1900 + 1900) ÷ (2 × 11) × 1.5 = 3800/22 × 1.5 = 259.090909 оп/ч
P_effective = 90 × 0.75 × 1.0 = 67.5
requiredRobots = ceil(259.090909 × 1.15 / 67.5) = ceil(4.412) = 5 = selectedRobots
  → underpowered = false, overpowered = false
N_infra = ceil(5 × 0.35) = 2
C_equipment = 5 × 2 990 000 = 14 950 000
CAPEX = 14 950 000 × (1 + 0.10 + 0.15 + 0.15 + 0.075 + 0.03)
      = 22 499 750 + резерв 10% = 2 249 975 → 24 749 725 руб.
FOT_base = (16 × 70 000 + 5 × 80 000) × 12 × 1.302 = 1 520 000 × 15.624
         = 23 748 480 руб./год
OPEX_rob = 5 914 725 руб./год (сервис/лицензии/расходники/ремонт/
           электричество 9.5 кВт × 8030 ч × 7.0/связь)
Effect_gross = 23 748 480 − 5 914 725 = 17 833 755
Amort_year = 24 749 725 / 7 = 3 535 675 (lifetime фикстуры
           РТ-Паллета 7 лет - средневзвешенный срок парка)
Effect_year = 17 833 755 − 3 535 675 = 14 298 080 руб./год
Payback = 24 749 725 / 14 298 080 = 1.73 → 1.7 года
ROI = 14 298 080 × 5 / 24 749 725 × 100 % = 288.9 %
TCO = 24 749 725 + 5 914 725 × 5 = 54 323 350 руб.
```

RaaS: Payment_year = 12 × 2 % × 2 990 000 × 5 = 3 588 000;
OPEX_raas = 6 437 975; Effect_year = 17 310 505; CAPEX = 0 →
Payback/ROI не определены; TCO = 32 189 875.

Base: OPEX = FOT_base = 23 748 480; TCO за 5 лет = 118 742 400.

## 3. «Демо: Пиковая нагрузка - успех» - покупка
(АТ-Паллета 2500 × 4 + АТ-Штабелер 1000 × 2)

```
Peak_demand = (1500 + 1500) ÷ 22 × 2.5 = 341.090909 оп/ч
P_effective = 67.5 → requiredRobots = ceil(341.090909 × 1.15 / 67.5)
            = ceil(5.811) = 6 = selectedRobots
N_infra = ceil(6 × 0.35) = 3
C_equipment = 4 × 4 100 000 + 2 × 2 400 000 = 21 200 000
CAPEX = 35 096 600 руб.
FOT_base = (17 × 70 000 + 5 × 80 000) × 15.624 = 24 842 160 руб./год
OPEX_rob = 7 934 600 руб./год
Effect_gross = 16 907 560; Amort_year = 4 577 817 (lifetime 8 у
АТ-Паллеты - средневзвешенный срок парка)
Effect_year = 12 329 743 руб./год
Payback = 2.84 → 2.8 года; ROI = 175.7 %; TCO = 74 769 600 руб.
```

RaaS: Payment_year = 5 088 000 (2 % × (4×4.1M + 2×2.4M) × 12);
Effect_year = 16 165 560; TCO = 43 383 000. Base TCO = 124 210 800.

## 4. «Демо: Умеренный масштаб» - покупка
(РТ-Комплект 800 × 5 + АТ-Штабелер 1000 × 2)

```
Peak_demand = (750 + 750) ÷ 22 × 1.5 = 102.272727 оп/ч
P_nominal (допущение проекта) = 25 → P_effective = 25 × 0.75 = 18.75
requiredRobots = ceil(102.272727 × 1.15 / 18.75) = ceil(6.273) = 7
             = selectedRobots
N_infra = ceil(7 × 0.35) = 3
C_equipment = 5 × 1 750 000 + 2 × 2 400 000 = 13 550 000
CAPEX = 22 432 025 руб.
FOT_base = (15 × 70 000 + 5 × 80 000) × 15.624 = 22 654 800 руб./год
OPEX_rob = 4 772 260 руб./год
Effect_gross = 17 882 540; Amort_year = 3 568 731
Effect_year = 14 313 809 руб./год
Payback = 1.57 → 1.6 года; ROI = 319.0 %; TCO = 46 293 325 руб.
```

RaaS: Payment_year = 3 252 000; Effect_year = 17 408 290;
TCO = 26 232 550. Base TCO = 113 274 000.

## 5. Машинный эталон

Прямые ожидания для `scripts/verify_economics_new.py`: поля ответа
`POST /api/projects/{id}/scenarios/{sid}/calculate` (верхний уровень
+ `details`). Изменение формул §2.x economic_model.md без смены
`version_model` недопустимо: сначала обновить этот эталон.

```golden
{
  "Демо: Оптимальный склад": {
    "base": {
      "selectedRobots": 0, "nInfra": 0, "horizonYears": 5,
      "capex.total": 0, "opex.total": 23748480,
      "opexBase": 23748480, "fotBase": 23748480, "deltaFot": 0,
      "opexDelta": 0, "effectGross": 0, "amortYear": 0,
      "effectYear": 0, "paybackYears": null, "roiPct": null,
      "tcoRub": 118742400, "replacements": 0
    },
    "purchase": {
      "selectedRobots": 5, "requiredRobots": 5, "nInfra": 2,
      "horizonYears": 5, "capex.total": 24749725,
      "opex.total": 5914725, "opexBase": 23748480, "fotBase": 23748480,
      "deltaFot": 23748480, "opexDelta": -17833755,
      "effectGross": 17833755, "amortYear": 3535675,
      "effectYear": 14298080, "paybackYears": 1.7, "roiPct": 288.9,
      "tcoRub": 54323350, "replacements": 0,
      "underpowered": false, "overpowered": false
    },
    "raas": {
      "selectedRobots": 5, "requiredRobots": 5, "nInfra": 2,
      "horizonYears": 5, "capex.total": 0, "opex.total": 6437975,
      "raas.paymentYear": 3588000, "opexBase": 23748480,
      "fotBase": 23748480, "deltaFot": 23748480,
      "opexDelta": -17310505, "effectGross": 17310505,
      "amortYear": 0, "effectYear": 17310505, "paybackYears": null,
      "roiPct": null, "tcoRub": 32189875, "replacements": 0,
      "underpowered": false, "overpowered": false
    }
  },
  "Демо: Пиковая нагрузка - успех": {
    "base": {
      "selectedRobots": 0, "nInfra": 0, "horizonYears": 5,
      "capex.total": 0, "opex.total": 24842160,
      "opexBase": 24842160, "fotBase": 24842160, "deltaFot": 0,
      "opexDelta": 0, "effectGross": 0, "amortYear": 0,
      "effectYear": 0, "paybackYears": null, "roiPct": null,
      "tcoRub": 124210800, "replacements": 0
    },
    "purchase": {
      "selectedRobots": 6, "requiredRobots": 6, "nInfra": 3,
      "horizonYears": 5, "capex.total": 35096600,
      "opex.total": 7934600, "opexBase": 24842160, "fotBase": 24842160,
      "deltaFot": 24842160, "opexDelta": -16907560,
      "effectGross": 16907560, "amortYear": 4577817,
      "effectYear": 12329743, "paybackYears": 2.8, "roiPct": 175.7,
      "tcoRub": 74769600, "replacements": 0,
      "underpowered": false, "overpowered": false
    },
    "raas": {
      "selectedRobots": 6, "requiredRobots": 6, "nInfra": 3,
      "horizonYears": 5, "capex.total": 0, "opex.total": 8676600,
      "raas.paymentYear": 5088000, "opexBase": 24842160,
      "fotBase": 24842160, "deltaFot": 24842160,
      "opexDelta": -16165560, "effectGross": 16165560,
      "amortYear": 0, "effectYear": 16165560, "paybackYears": null,
      "roiPct": null, "tcoRub": 43383000, "replacements": 0,
      "underpowered": false, "overpowered": false
    }
  },
  "Демо: Умеренный масштаб": {
    "base": {
      "selectedRobots": 0, "nInfra": 0, "horizonYears": 5,
      "capex.total": 0, "opex.total": 22654800,
      "opexBase": 22654800, "fotBase": 22654800, "deltaFot": 0,
      "opexDelta": 0, "effectGross": 0, "amortYear": 0,
      "effectYear": 0, "paybackYears": null, "roiPct": null,
      "tcoRub": 113274000, "replacements": 0
    },
    "purchase": {
      "selectedRobots": 7, "requiredRobots": 7, "nInfra": 3,
      "horizonYears": 5, "capex.total": 22432025,
      "opex.total": 4772260, "opexBase": 22654800, "fotBase": 22654800,
      "deltaFot": 22654800, "opexDelta": -17882540,
      "effectGross": 17882540, "amortYear": 3568731,
      "effectYear": 14313809, "paybackYears": 1.6, "roiPct": 319.0,
      "tcoRub": 46293325, "replacements": 0,
      "underpowered": false, "overpowered": false
    },
    "raas": {
      "selectedRobots": 7, "requiredRobots": 7, "nInfra": 3,
      "horizonYears": 5, "capex.total": 0, "opex.total": 5246510,
      "raas.paymentYear": 3252000, "opexBase": 22654800,
      "fotBase": 22654800, "deltaFot": 22654800,
      "opexDelta": -17408290, "effectGross": 17408290,
      "amortYear": 0, "effectYear": 17408290, "paybackYears": null,
      "roiPct": null, "tcoRub": 26232550, "replacements": 0,
      "underpowered": false, "overpowered": false
    }
  }
}
```
