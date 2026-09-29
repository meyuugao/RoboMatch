# Экономика: эталон ручного расчёта (golden)

Назначение: эталон для E2E-верификации формул `docs/economic_model.md`
§2.1–2.13 на живых данных. Проекты - из seed
(`scripts/seed/repositories/test_projects_repo.py`, владелец - демо-аккаунт
`user`): конфигурации воспроизводимы, цены и ТТХ - из каталога
на живой БД. Автосверка - `scripts/verify_economics.py` (эталон читается
из раздела «Машинный эталон» ниже); тест-дубль -
`EconomicsGoldenTest` (backend, inline-копия значений).

Источники входов: параметры - дефолты листа «Склад»
(`Датасеты_хакатон.xlsx`, базовые значения); допущения - каталог
`docs/assumptions.md` §22–23 (дефолты); цены/ТТХ - каталог решений
(`catalog_export_v4.csv` + дозаполнение ТТХ).

## 1. Общие входы (базовые значения датасета)

Производные входы (economic_model.md §1.1), одинаковые для всех
E2E-проектов с базовыми параметрами:

| Вход                                   | Подстановка                                         | Значение                                              |
|----------------------------------------|-----------------------------------------------------|-------------------------------------------------------|
| Peak_demand                            | (1000 + 1000) паллет/сутки ÷ (2 смены × 11 ч) × 1.5 | 2000/22 × 1.5 = 90.909091 × 1.5 = **136.363637 оп/ч** |
| Hours_year                             | 2 × 11 × 365                                        | **8030 ч/год**                                        |
| Operations_year                        | (1000 + 1000) × 365                                 | **730 000 оп/год**                                    |
| FOT_base                               | (100 000 × 100 + 120 000 × 25) × 12 × 1.302         | 13 000 000 × 12 × 1.302 = **203 112 000 руб./год**    |
| Salary_year (средневзвешенная контура) | 13 000 000 ÷ 125 × 12                               | **1 248 000 руб./год**                                |

K_начислений = 1.302 - множитель С ЕДИНИЦЕЙ (assumptions.md §22):
13 000 000 × 12 = 156 000 000; × 1.302 = 203 112 000 (а не
156 000 000 × 0.302 = 47 112 000).

Допущения (дефолты каталога, assumptions.md §22–23): K_load = 0.75,
K_reserve = 0.15, Reserve_rate = 0.10, Tariff = 7.0 руб./кВт·ч,
Ratio_infra = 0.35, K_availability = 1.0, амортизация 6 лет (линейная;
срок службы в ТТХ Ronavi/AMR не задан - фолбэк допущения),
C_infra 10%, C_software 15%, C_integration 15%, C_commissioning 7.5%,
C_training 3%, C_service 10%, C_licenses 5%, C_communication 36 000
руб./робот/год, C_consumables 2%, C_repair 3.5%, фактор стоимости 100%,
dOther = 0, N_staff_rob = 0, горизонт 5 лет, RaaS: fixed, 2%/мес,
контракт 3 года, без выкупа.

Составы (цены каталога, с НДС): Ronavi H1500 - 2 700 000 руб.,
мощность зарядки 1.35 кВт (EAV); Ronavi SR - 950 000 руб., 1.35 кВт;
AMR 100 - 1 500 000 руб.; AMR 800 - 1 800 000 руб.; AMR 1500 -
2 200 000 руб. (AMR без ТТХ мощности - в средневзвешенной не участвуют).
P_nominal (допущение проекта) = 90 оп/ч.

## 2. Проект «E2E: Максимум - 1 решение» - покупка

Состав: Ronavi H1500 × 1. Base пустой (base не содержит решений по определению).

### §2.1 Количество роботов (требуемое и выбранное - раздельно)

```
P_effective = P_nominal × K_load × K_availability = 90 × 0.75 × 1.0 = 67.5
requiredRobots = ceil(Peak_demand × (1 + K_reserve) / P_effective)
              = ceil(136.363637 × 1.15 / 67.5)
              = ceil(156.818182 / 67.5) = ceil(2.3232) = 3
selectedRobots (состав) = Σ quantity = 1   → 1 < 3 - флаг
underpowered = true + предупреждение «расчёт не отражает достижение
заявленной производительности» (мягкая плашка, не ошибка;
вся экономика считается по selectedRobots)
```

### §2.2 Вспомогательное оборудование

```
N_infra = ceil(selectedRobots × Ratio_infra) = ceil(1 × 0.35) = 1
```

### §2.3 CAPEX

```
C_equipment = 1 × 2 700 000 = 2 700 000
C_infra       = 10%  × 2 700 000 =   270 000
C_software    = 15%  × 2 700 000 =   405 000
C_integration = 15%  × 2 700 000 =   405 000
C_commissioning = 7.5% × 2 700 000 = 202 500
C_training    = 3%   × 2 700 000 =    81 000
Сумма 6 статей = 4 063 500
C_reserve = 10% × 4 063 500 = 406 350      (Reserve_rate из Легенды XLSX)
CAPEX = 4 063 500 + 406 350 = 4 469 850
```

### §2.4 OPEX годовой

```
C_electricity = N × P_consumption × Hours_year × Tariff
             = 1 × 1.35 × 8030 × 7.0 = 75 883.5 → 75 884 (до рубля, HALF_UP)
C_staff = N_staff_rob × Salary_year × K_начислений = 0 × … × 1.302 = 0
C_service = 10% × 2 700 000 = 270 000;  C_licenses = 5% = 135 000
C_communication = 1 × 36 000 = 36 000;  C_consumables = 2% = 54 000
C_repair = 3.5% × 2 700 000 = 94 500
OPEX_year = 270 000+135 000+75 884+36 000+54 000+94 500+0 = 665 384
```

### §2.5 Изменение OPEX

```
ΔOPEX = OPEX_rob − OPEX_base = 665 384 − 203 112 000 = −202 446 616
(отрицательное - экономия; OPEX_base = ФОТ контура базового процесса)
```

### §2.6 Годовой эффект

```
ΔFOT = FOT_base − FOT_rob = 203 112 000 − 0 = 203 112 000
ΔOther = 0
Effect_gross = ΔFOT + ΔOther − ΔOPEX_без_ФОТ
             = 203 112 000 + 0 − 665 384 = 202 446 616
Amort_year = CAPEX / Lifetime = 4 469 850 / 6 = 744 975
Effect_year = Effect_gross − Amort_year = 202 446 616 − 744 975
            = 201 701 641      (амортизация ВЫЧИТАЕТСЯ)
```

### §2.7 Срок окупаемости

```
Payback = CAPEX / Effect_year = 4 469 850 / 201 701 641 = 0.02216 → 0.0
(до десятых года; интерпретация - «быстрая окупаемость, до 3 лет»)
```

### §2.8 ROI

```
CumEffect = 5 × 201 701 641 = 1 008 508 205
ROI = 1 008 508 205 / 4 469 850 × 100 = 22 562.46 → 22 562.5%
```

### §2.9 TCO (горизонт 5, срок службы 6)

```
Replacements = floor((5 − 1) / 6) × C_equipment = 0 × 2 700 000 = 0
TCO = CAPEX + OPEX × 5 + Replacements
    = 4 469 850 + 3 326 920 + 0 = 7 796 770
```

### §2.10 Заёмное финансирование

Не задано (Loan = 0; базовое финансирование -
собственные средства). Проверка вариации: при 2 000 000 под 20% на
3 года аннуитет = 2 000 000 × (0.2 × 1.2³)/(1.2³ − 1) =
2 000 000 × 0.3456/0.728 = 949 450.55 → 949 451 руб./год;
Effect_after_debt = 201 701 641 − 949 451 = 200 752 190.

### §2.11 RaaS (fixed, без выкупа) - тот же проект

```
Rate_month = Price × 2% = 2 700 000 × 0.02 = 54 000
Payment_year = 12 × 54 000 × 1 = 648 000
OPEX_raas = Payment_year + C_electricity + C_communication + C_staff
          = 648 000 + 75 884 + 36 000 + 0 = 759 884
CAPEX_raas = 0 (без выкупа) → Payback_raas НЕ определён - сообщение
Effect_year_raas = ΔFOT − (648 000 + 75 884 + 36 000) = 202 352 116
TCO_raas = 0 + 759 884 × 5 = 3 799 420
```

Вариация «с выкупом»:
Buyout = 2 700 000 × max(0, 1 − 3/6) = 1 350 000; CAPEX_raas = 1 ×
1 350 000 = 1 350 000; Amort_raas = 1 350 000/(6 − 3) = 450 000;
платежи годы min(3, 5) = 3; Effect = 202 352 116 − 450 000 =
201 902 116; Payback = 1 350 000/201 902 116 = 0.0; ROI = 74 778.6%;
TCO = CAPEX_raas + Платежи × 3 + (Электроэнергия + Связь + Персонал)
× горизонт 5 = 1 350 000 + 648 000×3 + (75 884 + 36 000 + 0)×5 =
1 350 000 + 1 944 000 + 559 420 = 3 853 420 (прочий OPEX - весь
горизонт, платежи - только годы контракта).

Вариации usage/mixed: usage 6 руб./операция →
Payment_year = 6 × 730 000 = 4 380 000, OPEX = 4 491 884, TCO =
22 459 420; mixed 2% + 3 руб./оп → Payment_year = 648 000 + 2 190 000
= 2 838 000, OPEX = 2 949 884, TCO = 14 749 420.

### §2.12 Чувствительность (шаги −20/−10/0/+10/+20%)

Таблица для покупки (полный пересчёт движком; фрагмент - Δ оборудования
меняет только цены, Δ труда - ФОТ и зарплату, Δ операций - ВЫБРАННЫЙ
ПАРК ПРОПОРЦИОНАЛЬНО Δ% (scaledTotal =
ceil(selected × (1+Δ)), распределение по строкам - наибольшие остатки;
отвечает на вопрос «что будет с экономикой, если парк увеличить
пропорционально»; P_nominal не участвует - строка Δ=0 воспроизводит
основной результат при любом составе) и годовой объём):

| Параметр     | Δ%  | Effect_year | Payback | ROI      |
|--------------|-----|-------------|---------|----------|
| Оборудование | −20 | 201 961 336 | 0.0     | 28 239.4 |
| Оборудование | 0   | 201 701 641 | 0.0     | 22 562.5 |
| Оборудование | +20 | 201 441 946 | 0.0     | 18 777.8 |
| Труд         | −20 | 161 079 241 | 0.0     | 18 018.4 |
| Труд         | 0   | 201 701 641 | 0.0     | 22 562.5 |
| Труд         | +20 | 242 324 041 | 0.0     | 27 106.5 |

### §2.13 Таблица сравнения

base: N=0, CAPEX=0, OPEX=203 112 000, TCO=1 015 560 000, эффект 0;
purchase и raas - выше; Δ к базовому и интерпретация Payback -
эндпоинт `GET /compare` (проверяется тестами и verify-скриптом).

## 3. Проект «E2E: Максимум - 5 решений» - покупка

Состав: Ronavi H1500 × 2, Ronavi SR × 3, AMR 100 × 1, AMR 800 × 2,
AMR 1500 × 1. selectedRobots = 9 ≥ requiredRobots = 3 - флага недобора
нет.

```
§2.1  requiredRobots = 3 (те же входы); selectedRobots = 2+3+1+2+1 = 9
§2.2  N_infra = ceil(9 × 0.35) = ceil(3.15) = 4
§2.3  C_equipment = 2×2 700 000 + 3×950 000 + 1 500 000
                      + 2×1 800 000 + 2 200 000
                    = 5 400 000+2 850 000+1 500 000+3 600 000+2 200 000
                    = 15 550 000
      C_infra 10% = 1 555 000; C_software 15% = 2 332 500
      C_integration 15% = 2 332 500; C_commissioning 7.5% = 1 166 250
      C_training 3% = 466 500; сумма 6 статей = 23 402 750
      C_reserve = 10% × 23 402 750 = 2 340 275
      CAPEX = 23 402 750 + 2 340 275 = 25 743 025
§2.4  P_consumption = (1.35×2 + 1.35×3 + 2.2×2)/(2+3+2) = 11.15/7 =
        1.592857 кВт (средневзвешенно по решениям с заданной ТТХ -
        AMR 800 «Ручная зарядка: 1-ф 220В, 10A» →
        P = U×I = 2.2 кВт, морос.рф/amr-800; AMR 100/1500 не публикуют -
        не участвуют; среднее округляется до 6 знаков - конвенция
        сборки входов, субрублевый эффект)
      C_electricity = 9 × 1.592857 × 8030 × 7.0 = 805 810.43 → 805 810
      C_communication = 9 × 36 000 = 324 000
      C_service = 1 555 000; C_licenses = 777 500
      C_consumables = 311 000; C_repair = 544 250
      OPEX_year = 1 555 000+777 500+805 810+324 000+311 000+544 250+0
                = 4 317 560
§2.5  ΔOPEX = 4 317 560 − 203 112 000 = −198 794 440
§2.6  ΔFOT = 203 112 000; Effect_gross = 203 112 000 − 4 317 560
        = 198 794 440; Amort = 25 743 025/6 = 4 290 504.17 → 4 290 504
      Effect_year = 198 794 440 − 4 290 504 = 194 503 936
§2.7  Payback = 25 743 025 / 194 503 936 = 0.1323 → 0.1 (в UI - «1,2 мес.»:
        срок < 0,5 года отображается в месяцах;
        paybackMonths = round(Payback × 12, 1) - от округлённого
        до десятых значения, §4)
§2.8  ROI = 194 503 936 × 5 / 25 743 025 × 100 = 3 777.84 → 3 777.8%
        (ROI > 500% - подпись «Значение зависит от большого
        baseline ФОТ - проверьте допущения»)
§2.9  Replacements = floor(4/6) = 0; TCO = 25 743 025 + 4 317 560 × 5
        = 47 330 825
§2.11 RaaS: avg_price = 15 550 000/9 = 1 727 777.78
      Rate_month (точно) = 1 727 777.78 × 2% = 34 555.56 (без промежуточного
        округления - §4; до рубля округляется только отображение: 34 556)
      Payment_year = 12 × 34 555.56 × 9 = 3 732 000 (точно, без
        промежуточного округления - §4; отображение ставки - 34 556)
      OPEX_raas = 3 732 000 + 805 810 + 324 000 = 4 861 810
      Effect_year = 203 112 000 − 4 861 810 = 198 250 190
      TCO_raas = 4 861 810 × 5 = 24 309 050; CAPEX 0, Payback - сообщение
```

## 4. Особые случаи (проекты 1, 4, 5)

| Проект                 | Что проверяет       | Ожидание (подтверждено живым расчётом)                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|------------------------|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Минимальный склад      | 0 решений           | N=0, CAPEX=0, предупреждение «состав пуст» + «ΔFOT обнулён» (ΔFOT = 0, Effect_gross = 0 + ΔOther − 0 = 0, Effect_year = 0); Payback/ROI не определены (CAPEX=0 - сообщение; интерпретация - «Состав пуст»), OPEX роботизированных = 0                                                                                                                                                                                                                                            |
| Пиковая нагрузка       | ceil при worst-case | peak_factor 2.5, K_load 0.70, K_reserve 0.20: Peak = 2000/22×2.5 = 227.27; N_rec = ceil(227.27×1.20/(90×0.70)) = ceil(272.73/63) = ceil(4.329) = **5**; N_infra = ceil(5×0.35) = 2                                                                                                                                                                                                                                                                                               |
| Edge case - Effect ≤ 0 | Неокупаемый проект  | ФОТ контура 1+1 чел. по 50 000: FOT_base = (50 000+50 000)×12×1.302 = 1 562 400; состав 5×AMR 1500 (мощность зарядки не публикуется - проектное допущение robot_power_consumption_kw = 2.2, консервативная оценка по цепи AMR 800): C_electricity = 5×2.2×8030×7.0 = 618 310, OPEX = 3 053 310; CAPEX = 18 210 500; Amort = 3 035 083; Effect_year = 1 562 400 − 3 053 310 − 3 035 083 = **−4 525 993** → Payback не выводится, сообщение «не окупается»; ROI = −124.3%          |

## 5. Правила округления (§4 economic_model.md) - живые примеры

- N_robots (выбранный и требуемый) / N_infra - всегда вверх (ceil):
  0.35 → 1; 3.15 → 4; 4.329 → 5.
- Стоимости - до рублей (HALF_UP): 75 883.5 → 75 884; 682 951.5 →
  682 952; 4 290 504.17 → 4 290 504; 34 555.56 → 34 556.
- Payback - до десятых года: 0.02216 → 0.0; 0.1323 → 0.1.
- ROI - до десятых процента: 22 562.46 → 22 562.5; 45 124.925 →
  45 124.9.
- Округление - на уровне строк-показателей (самих статей CAPEX/OPEX,
  амортизации, платежей RaaS, выкупной стоимости за робота):
  каждая статья - целые рубли (HALF_UP); итоги и зависимые величины
  считаются ИЗ ОКРУГЛЁННЫХ строк (§4 «стоимости - до рублей»).
  Смысл: аддитивная согласованность отображения - сумма показанных
  статей равна показанному итогу, пользователь может воспроизвести
  расчёт по видимым значениям (§4 «все формулы видны пользователю»).
  Пример: выкуп project 3 - 863 889 руб./робот
  (money(863 888.89)) × 9 = CAPEX_raas 7 775 001, а «чистая цепочка»
  без статейного округления дала бы 7 775 000; расхождение ±1 руб. -
  осознанная цена согласованности отображения.
  Внутри одной строки-показателя промежуточных округлений нет
  (MathContext.DECIMAL128): 682 951.5 → 682 952 одним шагом.
  Ставка RaaS НЕ округляется до рублей до умножения на 12×N -
  округляются только отображаемые значения (rateMonthRub) и итоги;
  платеж project 3 - точный 3 732 000.

## 6. Уточнения методики, вошедшие в эталон

- Чувствительность «объём операций»: парк масштабируется пропорционально
  выбранному составу; распределение по строкам - метод наибольших
  остатков (Σ ровно scaledTotal). Персонал эксплуатации масштабируется
  вместе с парком (доля парка, а не доля Δ%).
- Замены в TCO: floor((Horizon−1)/Lifetime) по ВЕЩЕСТВЕННОМУ
  средневзвешенному сроку службы.
- ROI: колонка numeric(12,2), при |ROI| свыше 1 млрд % - null +
  предупреждение (по образцу Payback).
- version_data включает эффективную потребляемую мощность - смена
  любой части фолбэк-цепочки меняет версию данных.
- Некорректный payback_horizon (не число / <= 0) - блокировка расчёта
  400 вместо молчаливого дефолта 5.
- Кредит с нулевой ставкой/сроком - блокировка 400 (нулевый знаменатель
  аннуитета).
- Разбивка CAPEX сценария RaaS: статьи согласованы с итогом (equipment
  не показывает весь парк при CAPEX_raas = 0; полная стоимость парка -
  в raas.fleetCostRub).

## 6.1. Правила чувствительности, состава и отображения

- Чувствительность по объёму: база Δ=0 обязана совпадать
  с основным результатом при ЛЮБОМ составе. scaledTotal =
  ceil(selectedRobots × (1+Δ)), распределение - наибольшие остатки;
  P_nominal/formulaAvailable/parkScalingNote удалены; note в строках
  всегда null (поле сохранено для чтения исторических расчётов).
- P_consumption не-Ronavi: AMR 800 - charging_power_kw =
  2.2 кВт (морос.рф/amr-800: «Ручная зарядка: 1-ф 220В, 10A»,
  P = U×I; провенанс open_source). AMR 100/
  1500, DMR 1200, Сёмабот, RoboCV - мощность НЕ публикуется
  производителями; для проекта «Edge case» (5×AMR 1500) P_consumption
  задан проектным допущением robot_power_consumption_kw = 2.2
  (консервативная оценка по цепи той же линейки, §1.2 - допущение
  первично, ТТХ зарядки - фолбэк).
- Пустой состав: ΔFOT = 0 при selectedRobots = 0 (замещения
  персонала без роботов нет); Effect_gross = 0 + ΔOther −
  nonPayrollOpex (статьи пустого состава нулевые → Effect_gross =
  ΔOther, амортизация 0 → Effect_year = Effect_gross).
- Флаг overpowered (required != null && selected > required):
  нейтральная плашка «парк превышает рекомендуемый на N ед. -
  возможна переплата», в warnings НЕ попадает; в машинном эталоне -
  поле overpowered.
- Окупаемость < 0,5 года в месяцах (paybackMonths, только
  отображение): project 3 Payback 0.1 г. → «1,2 мес.».
- Независимая перепроверка изменённых значений собственным
  python-движком по документации (без опоры на Java-код): 48/48
  сверок сошлись (электроэнергия, OPEX, ΔOPEX, Effect, TCO, ROI
  для всех изменённых сценариев; конвенция сборки входов - среднее
  ТТХ до 6 знаков).

## 7. Горизонт расчёта (§22)

Дефолт 5 лет. Значение параметра `payback_horizon` хранится
numeric(16,4) («10.0000»), парсинг BigDecimal: горизонт 10 →
ROI = 201 701 641 × 10/4 469 850 × 100 = 45 124.9%, Replacements =
floor(9/6) = 1 × 2 700 000 = 2 700 000, TCO = 4 469 850 + 6 653 840 +
2 700 000 = 13 823 690. Горизонт < 5 - 400 со списком (TCO считается
на горизонте не менее 5 лет).

## 8. Машинный эталон

Значения ниже - прямые ожидания для `scripts/verify_economics.py`:
сверяются поля ответа `POST /api/projects/{id}/scenarios/{sid}/calculate`
(колонки расчёта и metrics_json). Изменение формул §2.x без смены
`version_model` здесь недопустимо: сначала обновить этот эталон и
`version_model`.

```golden
{
  "E2E: Минимальный склад": {
    "purchase": {
      "selectedRobots": 0,
      "nInfra": 0,
      "horizonYears": 5,
      "capex.total": 0,
      "opex.total": 0,
      "opexBase": 203112000,
      "fotBase": 203112000,
      "deltaFot": 0,
      "opexDelta": -203112000,
      "effectGross": 0,
      "amortYear": 0,
      "effectYear": 0,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 0,
      "replacements": 0
    },
    "raas": {
      "selectedRobots": 0,
      "nInfra": 0,
      "horizonYears": 5,
      "capex.total": 0,
      "opex.total": 0,
      "raas.paymentYear": 0,
      "opexBase": 203112000,
      "fotBase": 203112000,
      "deltaFot": 0,
      "opexDelta": -203112000,
      "effectGross": 0,
      "amortYear": 0,
      "effectYear": 0,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 0
    },
    "base": {
      "selectedRobots": 0,
      "nInfra": 0,
      "horizonYears": 5,
      "capex.total": 0,
      "opex.total": 203112000,
      "opexDelta": 0,
      "effectYear": 0,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 1015560000
    }
  },
  "E2E: Максимум - 1 решение": {
    "purchase": {
      "selectedRobots": 1,
      "requiredRobots": 3,
      "underpowered": true,
      "nInfra": 1,
      "horizonYears": 5,
      "capex.equipment": 2700000,
      "capex.infra": 270000,
      "capex.software": 405000,
      "capex.integration": 405000,
      "capex.commissioning": 202500,
      "capex.training": 81000,
      "capex.reserve": 406350,
      "capex.total": 4469850,
      "opex.service": 270000,
      "opex.licenses": 135000,
      "opex.electricity": 75884,
      "opex.communication": 36000,
      "opex.consumables": 54000,
      "opex.repair": 94500,
      "opex.staff": 0,
      "opex.total": 665384,
      "opexBase": 203112000,
      "fotBase": 203112000,
      "deltaFot": 203112000,
      "opexDelta": -202446616,
      "effectGross": 202446616,
      "amortYear": 744975,
      "effectYear": 201701641,
      "paybackYears": 0.0,
      "roiPct": 22562.5,
      "tcoRub": 7796770,
      "replacements": 0,
      "overpowered": false
    },
    "raas": {
      "selectedRobots": 1,
      "requiredRobots": 3,
      "underpowered": true,
      "nInfra": 1,
      "horizonYears": 5,
      "capex.total": 0,
      "raas.paymentModel": "fixed",
      "raas.rateMonthRub": 54000,
      "raas.paymentYear": 648000,
      "raas.paymentYears": 5,
      "raas.buyoutValue": 0,
      "raas.capexRaas": 0,
      "opex.electricity": 75884,
      "opex.communication": 36000,
      "opex.total": 759884,
      "opexBase": 203112000,
      "fotBase": 203112000,
      "deltaFot": 203112000,
      "opexDelta": -202352116,
      "effectGross": 202352116,
      "amortYear": 0,
      "effectYear": 202352116,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 3799420,
      "overpowered": false
    },
    "base": {
      "selectedRobots": 0,
      "nInfra": 0,
      "horizonYears": 5,
      "capex.total": 0,
      "opex.total": 203112000,
      "opexDelta": 0,
      "effectYear": 0,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 1015560000
    }
  },
  "E2E: Максимум - 5 решений": {
    "purchase": {
      "selectedRobots": 9,
      "requiredRobots": 3,
      "underpowered": false,
      "nInfra": 4,
      "horizonYears": 5,
      "capex.equipment": 15550000,
      "capex.infra": 1555000,
      "capex.software": 2332500,
      "capex.integration": 2332500,
      "capex.commissioning": 1166250,
      "capex.training": 466500,
      "capex.reserve": 2340275,
      "capex.total": 25743025,
      "opex.service": 1555000,
      "opex.licenses": 777500,
      "opex.electricity": 805810,
      "opex.communication": 324000,
      "opex.consumables": 311000,
      "opex.repair": 544250,
      "opex.staff": 0,
      "opex.total": 4317560,
      "opexBase": 203112000,
      "fotBase": 203112000,
      "deltaFot": 203112000,
      "opexDelta": -198794440,
      "effectGross": 198794440,
      "amortYear": 4290504,
      "effectYear": 194503936,
      "paybackYears": 0.1,
      "roiPct": 3777.8,
      "tcoRub": 47330825,
      "replacements": 0,
      "overpowered": true
    },
    "raas": {
      "selectedRobots": 9,
      "requiredRobots": 3,
      "underpowered": false,
      "nInfra": 4,
      "horizonYears": 5,
      "capex.total": 0,
      "raas.paymentModel": "fixed",
      "raas.rateMonthRub": 34556,
      "raas.paymentYear": 3732000,
      "raas.paymentYears": 5,
      "raas.buyoutValue": 0,
      "raas.capexRaas": 0,
      "opex.electricity": 805810,
      "opex.communication": 324000,
      "opex.total": 4861810,
      "opexBase": 203112000,
      "fotBase": 203112000,
      "deltaFot": 203112000,
      "opexDelta": -198250190,
      "effectGross": 198250190,
      "amortYear": 0,
      "effectYear": 198250190,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 24309050,
      "overpowered": true
    },
    "base": {
      "selectedRobots": 0,
      "nInfra": 0,
      "horizonYears": 5,
      "capex.total": 0,
      "opex.total": 203112000,
      "opexDelta": 0,
      "effectYear": 0,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 1015560000
    }
  },
  "E2E: Пиковая нагрузка": {
    "purchase": {
      "selectedRobots": 5,
      "requiredRobots": 5,
      "underpowered": false,
      "nInfra": 2,
      "horizonYears": 5,
      "capex.equipment": 13500000,
      "capex.infra": 1350000,
      "capex.software": 2025000,
      "capex.integration": 2025000,
      "capex.commissioning": 1012500,
      "capex.training": 405000,
      "capex.reserve": 2031750,
      "capex.total": 22349250,
      "opex.electricity": 379418,
      "opex.communication": 180000,
      "opex.total": 3326918,
      "opexBase": 203112000,
      "deltaFot": 203112000,
      "opexDelta": -199785082,
      "effectGross": 199785082,
      "amortYear": 3724875,
      "effectYear": 196060207,
      "paybackYears": 0.1,
      "roiPct": 4386.3,
      "tcoRub": 38983840,
      "replacements": 0,
      "overpowered": false
    },
    "raas": {
      "selectedRobots": 5,
      "requiredRobots": 5,
      "underpowered": false,
      "nInfra": 2,
      "capex.total": 0,
      "raas.rateMonthRub": 54000,
      "raas.paymentYear": 3240000,
      "raas.paymentYears": 5,
      "raas.capexRaas": 0,
      "opex.total": 3799418,
      "opexDelta": -199312582,
      "effectYear": 199312582,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 18997090,
      "overpowered": false
    }
  },
  "E2E: Edge case - Effect_year ≤ 0": {
    "purchase": {
      "selectedRobots": 5,
      "requiredRobots": 3,
      "underpowered": false,
      "nInfra": 2,
      "horizonYears": 5,
      "capex.equipment": 11000000,
      "capex.total": 18210500,
      "opex.electricity": 618310,
      "opex.total": 3053310,
      "opexBase": 1562400,
      "fotBase": 1562400,
      "deltaFot": 1562400,
      "opexDelta": 1490910,
      "effectGross": -1490910,
      "amortYear": 3035083,
      "effectYear": -4525993,
      "paybackYears": null,
      "roiPct": -124.3,
      "tcoRub": 33477050,
      "overpowered": true
    },
    "raas": {
      "selectedRobots": 5,
      "capex.total": 0,
      "raas.rateMonthRub": 44000,
      "raas.paymentYear": 2640000,
      "raas.paymentYears": 5,
      "raas.capexRaas": 0,
      "opex.total": 3438310,
      "opexBase": 1562400,
      "deltaFot": 1562400,
      "opexDelta": 1875910,
      "effectGross": -1875910,
      "effectYear": -1875910,
      "paybackYears": null,
      "roiPct": null,
      "tcoRub": 17191550,
      "overpowered": true
    }
  }
}
```

Примечание: `roiPct` проекта «Edge case» отрицательный (ROI = −124.3% -
электроэнергия 618 310 ₽/год по допущению robot_power_consumption_kw =
2.2) - эффект отрицателен, Payback не выводится, сообщение «не
окупается на текущих допущениях» - в warnings расчёта.
