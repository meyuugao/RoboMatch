package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SolutionCase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий кейсов внедрения (solution_case).
 */
@Repository
public interface SolutionCaseRepository extends JpaRepository<SolutionCase, Long> {
}
