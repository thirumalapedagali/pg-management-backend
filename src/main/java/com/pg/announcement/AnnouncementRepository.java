package com.pg.announcement;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

	List<Announcement> findTop100ByPgIdOrderByIdDesc(Long pgId);

	Optional<Announcement> findByPgIdAndId(Long pgId, Long id);
}
