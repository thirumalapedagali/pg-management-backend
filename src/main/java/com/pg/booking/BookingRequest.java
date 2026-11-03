package com.pg.booking;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "booking_requests")
public class BookingRequest extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_id")
	private Long roomId;

	@Column(name = "room_number", length = 50)
	private String roomNumber;

	@Column(name = "section_id")
	private Long sectionId;

	@Column(name = "section_name", length = 100)
	private String sectionName;

	@Column(name = "bed_id")
	private Long bedId;

	@Column(name = "bed_number", length = 50)
	private String bedNumber;

	@Column(name = "name", nullable = false, length = 150)
	private String name;

	@Column(name = "mobile", nullable = false, length = 30)
	private String mobile;

	@Column(name = "email", length = 150)
	private String email;

	@Column(name = "expected_join_date")
	private LocalDate expectedJoinDate;

	@Column(name = "message", length = 1000)
	private String message;

	@Column(name = "rent", precision = 10, scale = 2)
	private BigDecimal rent;

	@Column(name = "deposit", precision = 10, scale = 2)
	private BigDecimal deposit;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private BookingRequestStatus status = BookingRequestStatus.PENDING;

	@Column(name = "rejection_reason", length = 500)
	private String rejectionReason;

	@Column(name = "resident_id")
	private Long residentId;
}
