package com.pg.pg;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerPgRepository extends JpaRepository<OwnerPg, Long> {

	List<OwnerPg> findByUserId(Long userId);
	List<OwnerPg> findByPgId(Long pgId);



	boolean existsByUserIdAndPgId(Long userId, Long pgId);
}
