package com.pg.config;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomAmenityRepository extends JpaRepository<RoomAmenity, Long> {

	List<RoomAmenity> findByPgIdAndRoomId(Long pgId, Long roomId);

	void deleteByPgIdAndRoomId(Long pgId, Long roomId);
}
