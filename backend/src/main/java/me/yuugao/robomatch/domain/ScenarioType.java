package me.yuugao.robomatch.domain;

/**
 * Тип сценария расчёта (data_model.md §10.5, CHECK type IN
 * ('base','purchase','raas')): base — текущий процесс без роботизации,
 * purchase — покупка оборудования, raas — роботы как услуга
 *.
 */
public enum ScenarioType {
    /**
 * Базовый: текущий процесс без роботизации.
 */
    BASE,
    /**
 * Покупка оборудования (CAPEX).
 */
    PURCHASE,
    /**
 * Роботы как услуга — RaaS, подписка.
 */
    RAAS
}
