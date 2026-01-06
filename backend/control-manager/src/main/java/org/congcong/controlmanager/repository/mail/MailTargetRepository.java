package org.congcong.controlmanager.repository.mail;

import org.congcong.controlmanager.entity.mail.MailTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailTargetRepository extends JpaRepository<MailTarget, Long> {
    Optional<MailTarget> findByBizKey(String bizKey);

    Optional<MailTarget> findByBizKeyAndEnabledTrue(String bizKey);

    boolean existsByBizKey(String bizKey);

    boolean existsByBizKeyAndIdNot(String bizKey, Long id);

    List<MailTarget> findByBizKeyContainingIgnoreCase(String bizKey);
}
