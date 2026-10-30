package com.pg.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByPgIdAndId(Long pgId, Long id);

	List<Payment> findByPgIdAndDateBetweenOrderByDateDescIdDesc(Long pgId, LocalDate from, LocalDate to);

	List<Payment> findByPgIdAndResidentIdAndDateBetweenOrderByDateDescIdDesc(Long pgId, Long residentId,
			LocalDate from, LocalDate to);

	List<Payment> findByPgIdAndResidentIdOrderByDateDescIdDesc(Long pgId, Long residentId);

	void deleteByPgIdAndResidentId(Long pgId, Long residentId);

	@Query("select coalesce(sum(p.amount), 0) from Payment p where p.pgId = :pgId")
	BigDecimal sumAll(@Param("pgId") Long pgId);

	@Query("select coalesce(sum(p.amount), 0) from Payment p where p.pgId = :pgId and p.date between :from and :to")
	BigDecimal sumBetween(@Param("pgId") Long pgId, @Param("from") LocalDate from, @Param("to") LocalDate to);

	@Query("select p.residentId, sum(p.amount) from Payment p where p.pgId = :pgId group by p.residentId")
	List<Object[]> sumByResident(@Param("pgId") Long pgId);

	@Query("select coalesce(sum(p.amount), 0) from Payment p where p.pgId = :pgId and p.residentId = :residentId")
	BigDecimal sumForResident(@Param("pgId") Long pgId, @Param("residentId") Long residentId);
}
