package com.pg.room;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomSectionRepository extends JpaRepository<RoomSection, Long> {

	List<RoomSection> findByPgIdAndRoomIdOrderBySectionName(Long pgId, Long roomId);

	List<RoomSection> findByPgIdOrderBySectionName(Long pgId);

	Optional<RoomSection> findByPgIdAndId(Long pgId, Long id);

	void deleteByPgIdAndRoomId(Long pgId, Long roomId);

	@Query("select s from RoomSection s where s.pgId = :pgId and s.id = :id")
	Optional<RoomSection> lockByPgIdAndId(@Param("pgId") Long pgId, @Param("id") Long id);
}
