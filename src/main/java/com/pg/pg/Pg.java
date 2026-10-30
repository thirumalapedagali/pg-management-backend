package com.pg.pg;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A tenant of the platform: one paying-guest business. */
@Entity
@Table(name = "pgs")
@Getter
@Setter
@NoArgsConstructor
public class Pg {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;
	private String ownerName;
	private String mobile;
	private String email;
	private String address;
	private String city;
	private String state;

	private String plan;
	@Column(unique = true)
	private String licenseKey;
	private LocalDateTime licenseStart;
	private LocalDateTime licenseExpiry;
	private boolean active;

	@Column(updatable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	public boolean isLicenseValid() {
		return active && licenseExpiry != null && licenseExpiry.isAfter(LocalDateTime.now());
	}
}
