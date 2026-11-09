package com.pg.attachment;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One uploaded picture of a property, a room or a resident (several per owner are allowed, unlike the single
 * {@code attachment} on a row). {@code pgId} is the property the picture belongs to; for a property picture that is
 * the property itself.
 */
@Entity
@Table(name = "media_files", indexes = @Index(name = "ix_media_owner", columnList = "pg_id, owner_type, owner_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class MediaFile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pg_id", nullable = false, updatable = false)
	private Long pgId;

	/** PROPERTY, ROOM or RESIDENT. */
	@Column(name = "owner_type", nullable = false, length = 20, updatable = false)
	private String ownerType;

	@Column(name = "owner_id", nullable = false, updatable = false)
	private Long ownerId;

	/** IMAGE (property, room), PHOTO or ID_CARD (resident). */
	@Column(nullable = false, length = 20)
	private String kind;

	/** Cloudinary public id, needed to delete the file. */
	@Column(name = "public_id", nullable = false)
	private String publicId;

	@Column(nullable = false, length = 1000)
	private String url;

	@CreatedDate
	@Column(updatable = false)
	private LocalDateTime createdAt;
}
