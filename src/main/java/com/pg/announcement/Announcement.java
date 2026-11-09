package com.pg.announcement;

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

/** A notice the owner posts for the residents of one property. */
@Entity
@Table(name = "announcements")
@Getter
@Setter
public class Announcement extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(nullable = false, length = 2000)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AnnouncementCategory category = AnnouncementCategory.ANNOUNCEMENT;

	@Column(nullable = false)
	private boolean important;
}
