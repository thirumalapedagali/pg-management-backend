package com.pg.resident;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResidentRepository extends JpaRepository<Resident, Long> {

	List<Resident> findByPgIdOrderByName(Long pgId);

	List<Resident> findByPgIdAndStatusOrderByName(Long pgId, ResidentStatus status);

	long countByPgIdAndStatus(Long pgId, ResidentStatus status);

	long countByPgIdAndStatusAndNoticeStatus(Long pgId, ResidentStatus status, NoticeStatus noticeStatus);

	List<Resident> findByPgIdAndStatusAndNoticeStatus(Long pgId, ResidentStatus status, NoticeStatus noticeStatus);

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

	long countByPgId(Long pgId);

	long countByPgIdAndRoomId(Long pgId, Long roomId);

	long countByPgIdAndRoomIdAndStatus(Long pgId, Long roomId, ResidentStatus status);

	long countByPgIdAndRoomIdAndStatusAndIdNot(Long pgId, Long roomId, ResidentStatus status, Long id);

	long countByPgIdAndRoomIdAndIdNot(Long pgId, Long roomId, Long id);

	long countByPgIdAndSectionId(Long pgId, Long sectionId);

	long countByPgIdAndSectionIdAndStatus(Long pgId, Long sectionId, ResidentStatus status);

	long countByPgIdAndSectionIdAndStatusAndIdNot(Long pgId, Long sectionId, ResidentStatus status, Long id);

	long countByPgIdAndSectionIdAndIdNot(Long pgId, Long sectionId, Long id);

	@Query("select coalesce(sum(r.securityDeposit), 0) - coalesce(sum(r.returnedDeposit), 0) from Resident r where r.pgId = :pgId")
	java.math.BigDecimal totalRefundableDeposit(@Param("pgId") Long pgId);

	@Query("select r.roomId, count(r) from Resident r where r.pgId = :pgId and r.status = 'ACTIVE' group by r.roomId")
	List<Object[]> countByRoom(@Param("pgId") Long pgId);

	@Query("select r from Resident r " +
	       "left join Room rm on r.roomId = rm.id " +
	       "left join RoomSection rs on r.sectionId = rs.id " +
	       "left join Bed b on r.bedId = b.id " +
	       "where r.pgId = :pgId " +
	       "and (:status is null or r.status = :status) " +
	       "and (:floor is null or :floor = '' or :floor = 'All Floors' or " +
	       "     rm.floorName = :floor or (rm.floorName is null and :floor = 'Ground Floor')) " +
	       "and (:search is null or :search = '' or " +
	       "     lower(r.name) like concat('%', lower(:search), '%') or " +
	       "     lower(r.phone) like concat('%', lower(:search), '%') or " +
	       "     lower(rm.number) like concat('%', lower(:search), '%') or " +
	       "     lower(rs.sectionName) like concat('%', lower(:search), '%') or " +
	       "     lower(b.bedNumber) like concat('%', lower(:search), '%')) " +
	       "order by rm.number asc, rs.sectionName asc, b.bedNumber asc, r.name asc")
	Page<Resident> searchResidentsPaged(@Param("pgId") Long pgId,
	                                    @Param("status") ResidentStatus status,
	                                    @Param("floor") String floor,
	                                    @Param("search") String search,
	                                    Pageable pageable);

	@Query("select r from Resident r " +
	       "left join Room rm on r.roomId = rm.id " +
	       "left join RoomSection rs on r.sectionId = rs.id " +
	       "left join Bed b on r.bedId = b.id " +
	       "where r.pgId = :pgId " +
	       "and (:status is null or r.status = :status) " +
	       "and (:floor is null or :floor = '' or :floor = 'All Floors' or " +
	       "     rm.floorName = :floor or (rm.floorName is null and :floor = 'Ground Floor')) " +
	       "and (:search is null or :search = '' or " +
	       "     lower(r.name) like concat('%', lower(:search), '%') or " +
	       "     lower(r.phone) like concat('%', lower(:search), '%') or " +
	       "     lower(rm.number) like concat('%', lower(:search), '%') or " +
	       "     lower(rs.sectionName) like concat('%', lower(:search), '%') or " +
	       "     lower(b.bedNumber) like concat('%', lower(:search), '%')) " +
	       "order by rm.number asc, rs.sectionName asc, b.bedNumber asc, r.name asc")
	List<Resident> searchResidentsList(@Param("pgId") Long pgId,
	                                   @Param("status") ResidentStatus status,
	                                   @Param("floor") String floor,
	                                   @Param("search") String search);

	Page<Resident> findByIdIn(List<Long> ids, Pageable pageable);
}
