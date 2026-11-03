package com.pg.booking;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRequestRepository extends JpaRepository<BookingRequest, Long> {

	List<BookingRequest> findByPgIdOrderByCreatedAtDesc(Long pgId);

	List<BookingRequest> findByPgIdAndStatusOrderByCreatedAtDesc(Long pgId, BookingRequestStatus status);

	Optional<BookingRequest> findByPgIdAndId(Long pgId, Long id);

	long countByPgIdAndStatus(Long pgId, BookingRequestStatus status);
}
