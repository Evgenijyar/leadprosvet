package ru.abs7.leadprosvet.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.abs7.leadprosvet.domain.IncomingBitrixEvent;

import java.util.List;

public interface IncomingBitrixEventRepository extends JpaRepository<IncomingBitrixEvent, Long> {
    List<IncomingBitrixEvent> findAllByOrderByIdDesc(Pageable pageable);
}
