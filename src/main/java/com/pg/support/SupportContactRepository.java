package com.pg.support;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportContactRepository extends JpaRepository<SupportContact, Long> {

	List<SupportContact> findByPgIdOrderByIdAsc(Long pgId);

	Optional<SupportContact> findByPgIdAndId(Long pgId, Long id);
}
