package com.pg.complaint;

import java.time.LocalDateTime;

import com.pg.tenant.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** An issue a resident raises for the owner or manager of their property to resolve. */
@Entity
@Table(name = "complaints", indexes = { @Index(columnList = "pg_id, status"), @Index(columnList = "pg_id, resident_id") })
@Getter
@Setter
public class Complaint extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "resident_id", nullable = false)
	private Long residentId;

	/** Code of a COMPLAINT_CATEGORY lookup value. */
	@Column(nullable = false, length = 40)
	private String category;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(nullable = false, length = 1000)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ComplaintPriority priority = ComplaintPriority.MEDIUM;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ComplaintStatus status = ComplaintStatus.OPEN;

	@Column(name = "resolution_notes", length = 1000)
	private String resolutionNotes;

	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	/** Username of the owner or manager who last handled it. */
	@Column(name = "handled_by", length = 80)
	private String handledBy;

	/** False while the resident has not yet looked at the latest status change. */
	@Column(name = "resident_seen", nullable = false)
	private boolean residentSeen = true;
}
