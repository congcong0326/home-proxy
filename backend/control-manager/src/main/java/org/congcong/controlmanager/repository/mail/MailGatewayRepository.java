package org.congcong.controlmanager.repository.mail;

import org.congcong.controlmanager.entity.mail.MailGateway;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailGatewayRepository extends JpaRepository<MailGateway, Long> {
    List<MailGateway> findByEnabledTrue();

    Optional<MailGateway> findFirstByEnabledTrueOrderByIdAsc();

    Optional<MailGateway> findByIdAndEnabledTrue(Long id);
}
