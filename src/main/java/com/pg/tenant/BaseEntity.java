package com.pg.tenant;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/** Base for every tenant-owned table: PG id + audit columns. */
@Getter
@Setter
@MappedSuperclass
@EntityListeners({ AuditingEntityListener.class, TenantEntityListener.class })
public abstract class BaseEntity {

	@JsonIgnore
	@Column(name = "pg_id", nullable = false, updatable = false)
	private Long pgId;

	@JsonIgnore
	@CreatedDate
	@Column(updatable = false)
	private LocalDateTime createdAt;

	@JsonIgnore
	@CreatedBy
	@Column(updatable = false)
	private String createdBy;

	@JsonIgnore
	@LastModifiedDate
	private LocalDateTime updatedAt;

	@JsonIgnore
	@LastModifiedBy
	private String updatedBy;
}
