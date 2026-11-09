package com.pg.registration;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRegistrationRepository extends JpaRepository<PropertyRegistration, Long> {

	List<PropertyRegistration> findAllByOrderByIdDesc();

	List<PropertyRegistration> findByStatusOrderByIdDesc(RegistrationStatus status);

	boolean existsByMobileAndStatus(String mobile, RegistrationStatus status);

	Optional<PropertyRegistration> findFirstByMobileOrderByIdDesc(String mobile);

	long countByStatus(RegistrationStatus status);
}
