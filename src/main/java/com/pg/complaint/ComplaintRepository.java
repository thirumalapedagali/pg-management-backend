package com.pg.complaint;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

	List<Complaint> findTop200ByPgIdOrderByIdDesc(Long pgId);

	List<Complaint> findByPgIdAndResidentIdOrderByIdDesc(Long pgId, Long residentId);

	List<Complaint> findByPgIdAndResidentIdAndResidentSeenFalse(Long pgId, Long residentId);

	Optional<Complaint> findByPgIdAndId(Long pgId, Long id);

	long countByPgIdAndStatus(Long pgId, ComplaintStatus status);
}
