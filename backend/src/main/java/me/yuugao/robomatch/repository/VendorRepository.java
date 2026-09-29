package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Vendor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий справочника производителей. Имена нужны каталогу:
 * батч-загрузка findAllById по набору vendor_id со страницы решений.
 */
@Repository
public interface VendorRepository extends JpaRepository<Vendor, Long> {

    /**
 * Все записи по возрастанию id (админка: снимок справочника).
 *
 * @return все производители, упорядоченные по возрастанию id
 */
    java.util.List<Vendor> findAllByOrderByIdAsc();
}
