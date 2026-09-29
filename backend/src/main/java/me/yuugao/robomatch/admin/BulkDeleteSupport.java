package me.yuugao.robomatch.admin;

import me.yuugao.robomatch.dto.BulkDeleteResultDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Общая механика массовых операций «Управления»: валидация списка
 * идентификаторов и независимая обработка позиций с накоплением
 * результатов.
 * <p>
 * Позиции обрабатываются по одной и НЕ в общей транзакции: удаление
 * каждой записи атомарно на уровне репозитория, отказ любой из них
 * (RESTRICT-ссылки, отсутствие записи) фиксируется в failed и не
 * откатывает уже удалённые.
 */
final class BulkDeleteSupport {

    private BulkDeleteSupport() {
    }

    /**
 * Валидация и дедупликация списка идентификаторов.
 *
 * @param ids исходный список (дубликаты и null - ошибка/игнор)
 * @return уникальные идентификаторы в исходном порядке
 * @throws BadRequestException 400 - список пуст или содержит null
 */
    static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BadRequestException("Список идентификаторов пуст");
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new BadRequestException(
                    "Список идентификаторов содержит пустое значение (null)");
        }
        return ids.stream().distinct().toList();
    }

    /**
 * Выполнить удаление по каждой позиции независимо.
 *
 * @param ids уникальные идентификаторы
 * @param singleDelete удаление одной позиции (existing-метод сервиса)
 * @return удалённые идентификаторы и причины отказов
 */
    static BulkDeleteResultDto collect(List<Long> ids, Consumer<Long> singleDelete) {
        List<Long> deleted = new ArrayList<>();
        List<BulkDeleteResultDto.FailureDto> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                singleDelete.accept(id);
                deleted.add(id);
            } catch (NotFoundException | ConflictException e) {
                failed.add(new BulkDeleteResultDto.FailureDto(id, e.getMessage()));
            } catch (DataIntegrityViolationException e) {
                failed.add(new BulkDeleteResultDto.FailureDto(id,
                        "На запись есть ссылки - удаление нарушает целостность"));
            }
        }
        return new BulkDeleteResultDto(deleted, failed);
    }
}
