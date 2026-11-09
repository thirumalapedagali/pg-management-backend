package com.pg.pg;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Links an owner login to a property (PG) they own. An owner can have several. */
@Entity
@Table(name = "owner_pgs", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "pg_id" }))
@Getter
@Setter
@NoArgsConstructor
public class OwnerPg {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "pg_id", nullable = false)
	private Long pgId;

	public OwnerPg(Long userId, Long pgId) {
		this.userId = userId;
		this.pgId = pgId;
	}
}
