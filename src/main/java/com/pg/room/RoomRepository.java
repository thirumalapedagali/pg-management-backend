package com.pg.room;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RoomRepository extends JpaRepository<Room, Long> {

	List<Room> findByPgIdOrderByNumber(Long pgId);

	Optional<Room> findByPgIdAndId(Long pgId, Long id);

	/** One row: [room count, total beds]. */
	@Query("select count(r), coalesce(sum(r.capacity), 0) from Room r where r.pgId = :pgId")
	List<Object[]> stats(@Param("pgId") Long pgId);

	boolean existsByPgIdAndNumber(Long pgId, String number);

	boolean existsByPgIdAndNumberAndIdNot(Long pgId, String number, Long id);

	/** Row lock so two concurrent check-ins cannot both take the last bed. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Room r where r.pgId = :pgId and r.id = :id")
	Optional<Room> lockByPgIdAndId(@Param("pgId") Long pgId, @Param("id") Long id);
}
