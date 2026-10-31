package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.RewriteEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RewriteEntryRepository extends JpaRepository<RewriteEntry, Long> {

    List<RewriteEntry> findByTableIdOrderBySortOrderAscIdAsc(Long tableId);

    List<RewriteEntry> findByTableIdInOrderBySortOrderAscIdAsc(Collection<Long> tableIds);

    long countByTableId(Long tableId);

    void deleteByTableId(Long tableId);
}
