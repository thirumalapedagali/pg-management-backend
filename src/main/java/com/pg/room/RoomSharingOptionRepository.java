package com.pg.room;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomSharingOptionRepository extends JpaRepository<RoomSharingOption, Long> {

	List<RoomSharingOption> findByPgIdAndRoomIdOrderByCapacity(Long pgId, Long roomId);

	List<RoomSharingOption> findByPgIdOrderByCapacity(Long pgId);

	Optional<RoomSharingOption> findByPgIdAndId(Long pgId, Long id);

	void deleteByPgIdAndRoomId(Long pgId, Long roomId);
}
