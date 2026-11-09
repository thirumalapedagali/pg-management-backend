package com.pg.resident;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResidentRepository extends JpaRepository<Resident, Long> {

	List<Resident> findByPgIdOrderByName(Long pgId);

	List<Resident> findByPgIdAndStatusOrderByName(Long pgId, ResidentStatus status);

	long countByPgIdAndStatus(Long pgId, ResidentStatus status);

	long countByPgIdAndStatusAndNoticeStatus(Long pgId, ResidentStatus status, NoticeStatus noticeStatus);

	@Query("select r.joinedOn from Resident r where r.pgId = :pgId and r.joinedOn >= :from")
	List<java.time.LocalDate> joinDatesSince(@Param("pgId") Long pgId, @Param("from") java.time.LocalDate from);

	List<Resident> findByPgIdAndRoomIdOrderByName(Long pgId, Long roomId);

	List<Resident> findByPgIdAndRoomIdAndStatusOrderByName(Long pgId, Long roomId, ResidentStatus status);

	Optional<Resident> findByPgIdAndId(Long pgId, Long id);

	boolean existsByPhoneAndStatus(String phone, ResidentStatus status);

	boolean existsByPhoneAndStatusAndIdNot(String phone, ResidentStatus status, Long id);

	boolean existsByPhone(String phone);

	boolean existsByPhoneAndIdNot(String phone, Long id);

	Optional<Resident> findByPhone(String phone);

	Optional<Resident> findByPhoneAndStatus(String phone, ResidentStatus status);

	long countByPgIdAndRoomId(Long pgId, Long roomId);

	long countByPgIdAndRoomIdAndStatus(Long pgId, Long roomId, ResidentStatus status);

	long countByPgIdAndRoomIdAndStatusAndIdNot(Long pgId, Long roomId, ResidentStatus status, Long id);

	long countByPgIdAndRoomIdAndIdNot(Long pgId, Long roomId, Long id);

	long countByPgIdAndSectionId(Long pgId, Long sectionId);

	long countByPgIdAndSectionIdAndStatus(Long pgId, Long sectionId, ResidentStatus status);

	long countByPgIdAndSectionIdAndStatusAndIdNot(Long pgId, Long sectionId, ResidentStatus status, Long id);

	long countByPgIdAndSectionIdAndIdNot(Long pgId, Long sectionId, Long id);

	@Query("select r.roomId, count(r) from Resident r where r.pgId = :pgId and r.status = 'ACTIVE' group by r.roomId")
	List<Object[]> countByRoom(@Param("pgId") Long pgId);
}
