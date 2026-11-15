package com.pg.booking;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRequestRepository extends JpaRepository<BookingRequest, Long> {

	/** One page of a property's requests in the given statuses; {@code search} is lower-case, '' for no search. */
	@Query("select b from BookingRequest b where b.pgId = :pgId and b.status in :statuses and (:search = '' "
			+ "or lower(b.name) like concat('%', :search, '%') or b.mobile like concat('%', :search, '%') "
			+ "or lower(b.roomNumber) like concat('%', :search, '%') or lower(b.bedNumber) like concat('%', :search, '%')) "
			+ "order by b.createdAt desc")
	Page<BookingRequest> search(@Param("pgId") Long pgId, @Param("statuses") Collection<BookingRequestStatus> statuses,
			@Param("search") String search, Pageable pageable);

	List<BookingRequest> findByPgIdOrderByCreatedAtDesc(Long pgId);

	List<BookingRequest> findByPgIdAndStatusOrderByCreatedAtDesc(Long pgId, BookingRequestStatus status);

	Optional<BookingRequest> findByPgIdAndId(Long pgId, Long id);

	long countByPgIdAndStatus(Long pgId, BookingRequestStatus status);
}
