package com.pg.feedback;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

	/** One page of feedback in the given statuses, newest first; {@code search} is lower-case, '' for none. */
	@Query("select f from Feedback f where f.status in :statuses and (:search = '' or "
			+ "lower(f.message) like concat('%', :search, '%') or lower(f.username) like concat('%', :search, '%') or "
			+ "lower(f.pgName) like concat('%', :search, '%')) order by f.id desc")
	Page<Feedback> search(@Param("statuses") Collection<FeedbackStatus> statuses, @Param("search") String search,
			Pageable pageable);

	long countByStatus(FeedbackStatus status);
}
