package org.congcong.controlmanager.repository;

import org.congcong.controlmanager.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    
    Optional<User> findByUsername(String username);
    
    List<User> findByStatus(Integer status);
    
    boolean existsByUsername(String username);

    boolean existsByIpAddress(String ipAddress);
    
    Page<User> findByUsernameContaining(String username, Pageable pageable);
    
    @Query("SELECT u FROM User u WHERE (:status IS NULL OR u.status = :status) AND (:username IS NULL OR u.username LIKE %:username%)")
    Page<User> findByStatusAndUsernameContaining(@Param("status") Integer status, @Param("username") String username, Pageable pageable);
}