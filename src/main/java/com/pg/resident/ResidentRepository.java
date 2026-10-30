package com.pg.resident;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResidentRepository extends JpaRepository<Resident, Long> {

	List<Resident> findByPgIdOrderByName(Long pgId);

	List<Resident> findByPgIdAndRoomIdOrderByName(Long pgId, Long roomId);

	Optional<Resident> findByPgIdAndId(Long pgId, Long id);

	long countByPgIdAndRoomId(Long pgId, Long roomId);

	long countByPgIdAndRoomIdAndIdNot(Long pgId, Long roomId, Long id);

	@Query("select r.roomId, count(r) from Resident r where r.pgId = :pgId group by r.roomId")
	List<Object[]> countByRoom(@Param("pgId") Long pgId);
}
