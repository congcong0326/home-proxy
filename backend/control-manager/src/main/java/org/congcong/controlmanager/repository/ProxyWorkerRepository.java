package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.ProxyWorker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProxyWorkerRepository extends JpaRepository<ProxyWorker, String> {
    Optional<ProxyWorker> findByTokenHash(byte[] tokenHash);
}
