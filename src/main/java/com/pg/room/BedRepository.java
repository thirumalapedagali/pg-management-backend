package com.pg.room;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BedRepository extends JpaRepository<Bed, Long> {

	List<Bed> findByPgIdAndRoomIdOrderByBedNumber(Long pgId, Long roomId);

	List<Bed> findByPgIdAndSectionIdOrderByBedNumber(Long pgId, Long sectionId);

	List<Bed> findByPgIdOrderByBedNumber(Long pgId);

	Optional<Bed> findByPgIdAndId(Long pgId, Long id);

	Optional<Bed> findByPgIdAndResidentId(Long pgId, Long residentId);

	List<Bed> findByPgIdAndRoomIdAndStatusOrderByBedNumber(Long pgId, Long roomId, BedStatus status);

	List<Bed> findByPgIdAndSectionIdAndStatusOrderByBedNumber(Long pgId, Long sectionId, BedStatus status);

	void deleteByPgIdAndRoomId(Long pgId, Long roomId);

	@Query("select b from Bed b where b.pgId = :pgId and b.id = :id")
	Optional<Bed> lockByPgIdAndId(@Param("pgId") Long pgId, @Param("id") Long id);
}
