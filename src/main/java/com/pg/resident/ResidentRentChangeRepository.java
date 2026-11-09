package com.pg.resident;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResidentRentChangeRepository extends JpaRepository<ResidentRentChange, Long> {

	List<ResidentRentChange> findByPgIdAndResidentIdOrderByEffectiveFrom(Long pgId, Long residentId);

	List<ResidentRentChange> findByPgIdOrderByEffectiveFrom(Long pgId);

	void deleteByPgIdAndResidentId(Long pgId, Long residentId);
}
