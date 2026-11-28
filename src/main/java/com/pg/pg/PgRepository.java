package com.pg.pg;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgRepository extends JpaRepository<Pg, Long> {

	List<Pg> findByActiveTrueOrderByIdAsc();

	@Query("select p from Pg p where p.active = true and (:city = '' or lower(p.city) like concat('%', lower(:city), '%')) order by p.id asc")
	List<Pg> findActivePgsByCity(@Param("city") String city);

	@Query("select p from Pg p where p.active = true and (:city = '' or lower(p.city) like concat('%', lower(:city), '%')) order by p.id asc")
	Page<Pg> findActivePgsByCityPaged(@Param("city") String city, Pageable pageable);

	@Query("select p from Pg p where p.active = true and (:city = '' or lower(p.city) like concat('%', lower(:city), '%')) " +
	       "order by (case when p.latitude is not null and p.longitude is not null then " +
	       "((p.latitude - :lat)*(p.latitude - :lat) + (p.longitude - :lon)*(p.longitude - :lon)) else 999999.0 end) asc, p.id asc")
	Page<Pg> findActivePgsByLocationPaged(@Param("city") String city, @Param("lat") double lat, @Param("lon") double lon, Pageable pageable);
}
