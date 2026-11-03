package com.pg.notification.usage;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsappMessageLogRepository extends JpaRepository<WhatsappMessageLog, Long> {

	List<WhatsappMessageLog> findTop50ByPgIdOrderByCreatedAtDesc(Long pgId);

	Page<WhatsappMessageLog> findByPgIdOrderByCreatedAtDesc(Long pgId, Pageable pageable);

	long countByPgIdAndStatus(Long pgId, String status);
}
