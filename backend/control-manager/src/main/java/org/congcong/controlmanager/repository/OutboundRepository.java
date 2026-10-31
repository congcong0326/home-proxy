package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.Outbound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutboundRepository extends JpaRepository<Outbound, Long> {

    Optional<Outbound> findByBuiltinKey(String builtinKey);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<Outbound> findAllByOrderByIdAsc();
}
