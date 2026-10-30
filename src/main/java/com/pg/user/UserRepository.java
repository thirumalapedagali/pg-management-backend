package com.pg.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

	Optional<UserEntity> findByUsername(String username);

	Optional<UserEntity> findByMobile(String mobile);

	boolean existsByUsername(String username);

	boolean existsByMobile(String mobile);

	boolean existsByRole(Role role);

	List<UserEntity> findByPgIdOrderByUsername(Long pgId);

	Optional<UserEntity> findByPgIdAndId(Long pgId, Long id);

	Optional<UserEntity> findByPgIdAndResidentId(Long pgId, Long residentId);
}
