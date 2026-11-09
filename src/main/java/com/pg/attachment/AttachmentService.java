package com.pg.attachment;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.pg.common.BadRequestException;
import com.pg.common.NotFoundException;
import com.pg.tenant.BaseEntity;
import com.pg.tenant.TenantContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Attaches one file to any tenant-owned row. Every entity that extends {@link BaseEntity} carries an
 * {@code attachmentId} / {@code attachmentUrl}, so a new entity gets attachments without any new code.
 * Rows are addressed by entity name (e.g. {@code complaint}, {@code payment}, {@code room}) and id, and only rows of
 * the caller's own property can be reached.
 */
@Service
public class AttachmentService {

	private static final long MAX_BYTES = 10L * 1024 * 1024;
	private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif",
			"application/pdf", "application/msword",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel",
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	public record AttachmentResponse(String id, String url) {
	}

	private final Map<String, Class<? extends BaseEntity>> entities = new HashMap<>();
	private final EntityManager em;
	private final TransactionTemplate tx;
	private final CloudinaryService cloud;

	public AttachmentService(EntityManagerFactory emf, EntityManager em, PlatformTransactionManager tm,
			CloudinaryService cloud) {
		this.em = em;
		this.tx = new TransactionTemplate(tm);
		this.cloud = cloud;
		emf.getMetamodel().getEntities().forEach(t -> {
			if (BaseEntity.class.isAssignableFrom(t.getJavaType())) {
				entities.put(key(t.getJavaType().getSimpleName()), t.getJavaType().asSubclass(BaseEntity.class));
			}
		});
	}

	public AttachmentResponse get(String entity, Long id) {
		return tx.execute(s -> view(find(entity, id)));
	}

	/** Uploads the file and links it to the row, replacing (and deleting) any earlier file. */
	public AttachmentResponse attach(String entity, Long id, MultipartFile file) {
		check(file);
		tx.executeWithoutResult(s -> find(entity, id)); // fail before uploading anything for a row that is not there
		CloudinaryService.Stored stored = cloud.upload(file,
				"mystay/pg-" + TenantContext.requirePgId() + "/" + key(entity));
		String[] previous = new String[2];
		try {
			tx.executeWithoutResult(s -> {
				BaseEntity row = find(entity, id);
				previous[0] = row.getAttachmentId();
				previous[1] = row.getAttachmentUrl();
				row.setAttachmentId(stored.id());
				row.setAttachmentUrl(stored.url());
			});
		} catch (RuntimeException e) {
			cloud.delete(stored.id(), stored.url()); // do not leave an orphan in Cloudinary
			throw e;
		}
		cloud.delete(previous[0], previous[1]);
		return new AttachmentResponse(stored.id(), stored.url());
	}

	public void remove(String entity, Long id) {
		String[] previous = new String[2];
		tx.executeWithoutResult(s -> {
			BaseEntity row = find(entity, id);
			previous[0] = row.getAttachmentId();
			previous[1] = row.getAttachmentUrl();
			row.setAttachmentId(null);
			row.setAttachmentUrl(null);
		});
		cloud.delete(previous[0], previous[1]);
	}

	private AttachmentResponse view(BaseEntity row) {
		return new AttachmentResponse(row.getAttachmentId(), row.getAttachmentUrl());
	}

	/** Must run inside a transaction. A row of another property looks exactly like a missing one. */
	private BaseEntity find(String entity, Long id) {
		Class<? extends BaseEntity> type = entities.get(key(entity));
		if (type == null) {
			throw new NotFoundException("Unknown item type: " + entity);
		}
		BaseEntity row = em.find(type, id);
		if (row == null || !TenantContext.requirePgId().equals(row.getPgId())) {
			throw new NotFoundException("Item not found");
		}
		return row;
	}

	private static void check(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BadRequestException("Choose a file to upload");
		}
		if (file.getSize() > MAX_BYTES) {
			throw new BadRequestException("The file is larger than 10 MB");
		}
		String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
		if (!TYPES.contains(type)) {
			throw new BadRequestException("Only images, PDF, Word and Excel files can be attached");
		}
	}

	/** "BookingRequest", "booking-request" and "bookingrequest" are the same entity. */
	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
