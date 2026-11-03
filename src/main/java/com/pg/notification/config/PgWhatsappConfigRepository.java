package com.pg.notification.config;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PgWhatsappConfigRepository extends JpaRepository<PgWhatsappConfig, Long> {

	Optional<PgWhatsappConfig> findByPgId(Long pgId);

	boolean existsByPgId(Long pgId);
}
