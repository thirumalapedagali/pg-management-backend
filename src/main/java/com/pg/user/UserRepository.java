package com.pg.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

	Optional<UserEntity> findByUsername(String username);

	Optional<UserEntity> findByMobile(String mobile);

	boolean existsByUsername(String username);

	boolean existsByMobile(String mobile);

	boolean existsByRole(Role role);
	long countByRole(Role role);

	long countByStatus(Status status);



	List<UserEntity> findByPgIdOrderByUsername(Long pgId);

	Optional<UserEntity> findByPgIdAndId(Long pgId, Long id);

	Optional<UserEntity> findByPgIdAndResidentId(Long pgId, Long residentId);

	/** Finds active users of specific roles associated with a property. */
	@Query("select u from UserEntity u where (u.pgId = :pgId or u.id in "
			+ "(select o.userId from OwnerPg o where o.pgId = :pgId)) "
			+ "and u.role in :roles and u.status = :status")
	List<UserEntity> findByPgIdAndRoleInAndStatus(@Param("pgId") Long pgId,
			@Param("roles") Collection<Role> roles,
			@Param("status") Status status);
}
