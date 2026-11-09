package com.pg.attachment;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.pg.common.BadRequestException;

/**
 * Stores files in Cloudinary. Set {@code CLOUDINARY_URL} (cloudinary://key:secret@cloud) to switch it on; without it
 * the app still starts and every upload is refused with a clear message.
 */
@Service
public class CloudinaryService {

	private static final Logger log = LoggerFactory.getLogger(CloudinaryService.class);

	/** What Cloudinary kept: its public id (needed to delete the file later) and the HTTPS link. */
	public record Stored(String id, String url) {
	}

	/** Null until {@code cloudinary.url} is configured. */
	private final Cloudinary cloudinary;

	public CloudinaryService(@Value("${cloudinary.url:}") String url) {
		this.cloudinary = url == null || url.isBlank() ? null : new Cloudinary(url.trim());
	}

	public Stored upload(MultipartFile file, String folder) {
		Cloudinary client = client();
		try {
			Map<?, ?> result = client.uploader().upload(file.getBytes(),
					ObjectUtils.asMap("resource_type", "auto", "folder", folder));
			return new Stored(result.get("public_id").toString(), result.get("secure_url").toString());
		} catch (IOException e) {
			log.error("Cloudinary upload failed", e);
			throw new IllegalStateException("File upload failed", e);
		}
	}

	/** Best effort: a file that cannot be removed from Cloudinary must not block clearing the link in our database. */
	public void delete(String id, String url) {
		if (cloudinary == null || id == null || id.isBlank()) {
			return;
		}
		try {
			cloudinary.uploader().destroy(id,
					ObjectUtils.asMap("resource_type", resourceType(url), "invalidate", true));
		} catch (Exception e) {
			log.warn("Could not delete Cloudinary file {}", id, e);
		}
	}

	/** Cloudinary needs the kind of asset to delete it; it is part of the URL (.../image/upload/..., .../raw/upload/...). */
	static String resourceType(String url) {
		if (url != null && url.contains("/raw/upload/")) {
			return "raw";
		}
		if (url != null && url.contains("/video/upload/")) {
			return "video";
		}
		return "image";
	}

	private Cloudinary client() {
		if (cloudinary == null) {
			throw new BadRequestException("File uploads are not configured on this server (set CLOUDINARY_URL)");
		}
		return cloudinary;
	}
}
