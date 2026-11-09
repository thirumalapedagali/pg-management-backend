package com.pg.support;

import com.pg.tenant.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A phone contact (warden, plumber, emergency...) the owner chooses to share with residents. */
@Entity
@Table(name = "support_contacts")
@Getter
@Setter
public class SupportContact extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 80)
	private String name;

	@Column(nullable = false, length = 20)
	private String phone;

	@Column(length = 160)
	private String note;
}
