package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.RewriteTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewriteTableRepository extends JpaRepository<RewriteTable, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<RewriteTable> findAllByOrderByIdAsc();
}
