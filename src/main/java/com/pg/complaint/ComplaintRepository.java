package com.pg.complaint;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

	/** One page of a property's complaints in the given statuses, newest first; {@code search} is lower-case, '' for none. */
	@Query("select c from Complaint c "
			+ "left join Resident r on c.residentId = r.id "
			+ "left join Room rm on r.roomId = rm.id "
			+ "where c.pgId = :pgId and c.status in :statuses and (:search = '' or "
			+ "lower(c.title) like concat('%', :search, '%') or lower(c.category) like concat('%', :search, '%') or "
			+ "lower(r.name) like concat('%', :search, '%') or lower(r.phone) like concat('%', :search, '%') or "
			+ "lower(rm.number) like concat('%', :search, '%')) "
			+ "order by c.id desc")
	Page<Complaint> search(@Param("pgId") Long pgId, @Param("statuses") Collection<ComplaintStatus> statuses,
			@Param("search") String search, Pageable pageable);

	List<Complaint> findTop200ByPgIdOrderByIdDesc(Long pgId);

	List<Complaint> findByPgIdAndResidentIdOrderByIdDesc(Long pgId, Long residentId);

	List<Complaint> findByPgIdAndResidentIdAndResidentSeenFalse(Long pgId, Long residentId);

	Optional<Complaint> findByPgIdAndId(Long pgId, Long id);

	long countByPgIdAndStatus(Long pgId, ComplaintStatus status);

	long countByStatus(ComplaintStatus status);
}
