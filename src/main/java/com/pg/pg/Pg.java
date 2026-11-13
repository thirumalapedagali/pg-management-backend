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
	/** Another number residents can reach the property on; optional. */
	@Column(length = 15)
	private String alternateMobile;
	private String email;
	private String address;
	private String city;
	private String state;

	/**
	 * Where the property is. Latitude/longitude are WGS84 degrees. On PostgreSQL a generated column
	 * {@code location geography(Point,4326)} is derived from them (see PostgisInitializer), so it is always in step.
	 */
	private Double latitude;
	private Double longitude;
	@Column(length = 120)
	private String locality;
	@Column(name = "sub_locality", length = 120)
	private String subLocality;
	@Column(length = 12)
	private String pincode;
	@Column(length = 80)
	private String country;

	private String plan;
	@Column(name = "total_floors", nullable = false)
	private Integer totalFloors = 3;

	@Column(name = "stay_type", length = 40)
	private String stayType = "PG";

	@Column(name = "unit_label", length = 40)
	private String unitLabel = "Room";

	@Column(name = "location_url", length = 1000)
	private String locationUrl;

	@Column(length = 2000)
	private String description;

	@Column(length = 1500)
	private String amenities;

	/** House rules and regulations shown to residents on their notice board. */
	@Column(length = 4000)
	private String rules;

	@Column(name = "starting_price", precision = 12, scale = 2)
	private java.math.BigDecimal startingPrice;

	@Column(name = "gender_type", length = 30)
	private String genderType;

	@Column(name = "upi_id", length = 100)
	private String upiId;

	@Column(name = "upi_name", length = 150)
	private String upiName;

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
