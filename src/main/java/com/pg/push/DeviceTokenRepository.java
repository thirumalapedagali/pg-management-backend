package com.pg.push;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

	Optional<DeviceToken> findByToken(String token);

	@Transactional
	@Modifying
	@Query("delete from DeviceToken d where d.token = :token and d.userId = :userId")
	int deleteByTokenAndUser(@Param("token") String token, @Param("userId") Long userId);

	@Transactional
	@Modifying
	@Query("delete from DeviceToken d where d.token in :tokens")
	int deleteByTokens(@Param("tokens") Collection<String> tokens);

	/** Tokens of every resident of a property who has a login. */
	@Query("select d.token from DeviceToken d where d.userId in "
			+ "(select u.id from UserEntity u where u.pgId = :pgId and u.role = com.pg.user.Role.RESIDENT "
			+ "and u.status = com.pg.user.Status.ACTIVE)")
	List<String> tokensForPg(@Param("pgId") Long pgId);

	/** Tokens of one resident's login. */
	@Query("select d.token from DeviceToken d where d.userId in "
			+ "(select u.id from UserEntity u where u.pgId = :pgId and u.residentId = :residentId "
			+ "and u.status = com.pg.user.Status.ACTIVE)")
	List<String> tokensForResident(@Param("pgId") Long pgId, @Param("residentId") Long residentId);

	/** Tokens of every owner/manager (staff) associated with a property. */
	@Query("select d.token from DeviceToken d where d.userId in "
			+ "(select u.id from UserEntity u where (u.pgId = :pgId or u.id in "
			+ "(select o.userId from OwnerPg o where o.pgId = :pgId)) "
			+ "and u.role in (com.pg.user.Role.OWNER, com.pg.user.Role.MANAGER) "
			+ "and u.status = com.pg.user.Status.ACTIVE)")
	List<String> tokensForStaff(@Param("pgId") Long pgId);

	/** Tokens of any user with the given mobile number. */
	@Query("select d.token from DeviceToken d where d.userId in "
			+ "(select u.id from UserEntity u where u.mobile = :mobile and u.status = com.pg.user.Status.ACTIVE)")
	List<String> tokensForUserByMobile(@Param("mobile") String mobile);

	/** Tokens of all active super admin users. */
	@Query("select d.token from DeviceToken d where d.userId in "
			+ "(select u.id from UserEntity u where u.role = com.pg.user.Role.SUPER_ADMIN "
			+ "and u.status = com.pg.user.Status.ACTIVE)")
	List<String> tokensForSuperAdmins();
}
