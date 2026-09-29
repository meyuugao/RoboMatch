package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Репозиторий проектов. Все выборки - с явным user_id: изоляция проектов
 * закладывается в сам контракт репозитория, «забыть» фильтр
 * по владельцу здесь невозможно.
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
 * Страница проектов пользователя (список /api/projects).
 *
 * @param userId владелец проектов
 * @param pageable параметры страницы и сортировки
 * @return страница проектов только этого пользователя
 */
    Page<Project> findAllByUserId(Long userId, Pageable pageable);

    /**
 * Проверка дубликата имени в рамках пользователя (UNIQUE(user_id, name)).
 *
 * @param userId владелец проектов
 * @param name проверяемое имя проекта
 * @return true, если проект с таким именем уже существует
 */
    boolean existsByUserIdAndName(Long userId, String name);
}
