package com.pg.resident;

import java.math.BigDecimal;
import java.time.LocalDate;

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

@Entity
@Table(name = "residents", indexes = {
		@Index(columnList = "pg_id, room_id"),
		@Index(columnList = "pg_id, section_id"),
		@Index(columnList = "pg_id, bed_id"),
		@Index(columnList = "pg_id, sharing_option_id"),
		@Index(columnList = "phone"),
		@Index(columnList = "pg_id, status")
})
@Getter
@Setter
public class Resident extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(length = 20)
	private String phone;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Column(name = "section_id")
	private Long sectionId;

	@Column(name = "bed_id")
	private Long bedId;

	@Column(name = "sharing_option_id")
	private Long sharingOptionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "stay_type", length = 20, nullable = false)
	private StayType stayType = StayType.MONTHLY;

	@Column(name = "daily_rate", precision = 12, scale = 2)
	private BigDecimal dailyRate;

	@Column(name = "checkout_date")
	private LocalDate checkOutDate;

	@Column(name = "check_in_time")
	private java.time.LocalDateTime checkInTime;

	@Column(name = "checkout_time")
	private java.time.LocalDateTime checkOutTime;

	@Column(name = "rent_override", precision = 12, scale = 2)
	private BigDecimal rentOverride;

	@Column(name = "security_deposit", precision = 12, scale = 2)
	private BigDecimal securityDeposit = BigDecimal.ZERO;

	@Column(name = "returned_deposit", precision = 12, scale = 2)
	private BigDecimal returnedDeposit = BigDecimal.ZERO;

	@Column(nullable = false)
	private LocalDate joinedOn;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private ResidentStatus status = ResidentStatus.ACTIVE;

	/** Notice to vacate: null when the resident has not given one. */
	@Enumerated(EnumType.STRING)
	@Column(name = "notice_status", length = 20)
	private NoticeStatus noticeStatus;

	@Column(name = "notice_given_on")
	private LocalDate noticeGivenOn;

	/** The last day the resident wants to stay; the bed is available from this date once approved. */
	@Column(name = "vacate_on")
	private LocalDate vacateOn;

	@Column(name = "notice_reason", length = 300)
	private String noticeReason;
}
