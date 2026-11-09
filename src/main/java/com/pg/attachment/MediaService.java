package com.pg.attachment;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.pg.common.BadRequestException;
import com.pg.common.ForbiddenException;
import com.pg.common.NotFoundException;
import com.pg.pg.OwnerPgAccess;
import com.pg.pg.PgRepository;
import com.pg.resident.ResidentRepository;
import com.pg.room.RoomRepository;
import com.pg.tenant.TenantContext;
import com.pg.user.Role;
import com.pg.user.UserEntity;
import com.pg.user.UserRepository;

/**
 * Pictures of properties and rooms (a gallery, up to {@value #MAX_GALLERY} each) and of residents (one photo and one
 * ID card). Properties are addressed by their own id, so a super admin or an owner can add pictures to a property
 * they have just created before switching into it; rooms and residents belong to the property of the session.
 */
@Service
public class MediaService {

	public static final int MAX_GALLERY = 10;
	private static final long MAX_BYTES = 10L * 1024 * 1024;
	private static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

	public record MediaResponse(Long id, String kind, String url) {
	}

	private final MediaFileRepository files;
	private final CloudinaryService cloud;
	private final UserRepository users;
	private final OwnerPgAccess access;
	private final PgRepository pgs;
	private final RoomRepository rooms;
	private final ResidentRepository residents;

	public MediaService(MediaFileRepository files, CloudinaryService cloud, UserRepository users, OwnerPgAccess access,
			PgRepository pgs, RoomRepository rooms, ResidentRepository residents) {
		this.files = files;
		this.cloud = cloud;
		this.users = users;
		this.access = access;
		this.pgs = pgs;
		this.rooms = rooms;
		this.residents = residents;
	}

	public List<MediaResponse> list(String type, Long id, Authentication auth) {
		String owner = owner(type);
		Long pgId = scope(owner, id, auth);
		return files.findByPgIdAndOwnerTypeAndOwnerIdOrderById(pgId, owner, id).stream().map(MediaService::view)
				.toList();
	}

	/** Adds a picture. A resident's photo / ID card replaces the earlier one; property and room pictures are added. */
	public MediaResponse add(String type, Long id, String kind, MultipartFile file, Authentication auth) {
		String owner = owner(type);
		String k = kind(owner, kind);
		Long pgId = scope(owner, id, auth);
		check(file, "ID_CARD".equals(k));
		List<MediaFile> same = files.findByPgIdAndOwnerTypeAndOwnerIdAndKind(pgId, owner, id, k);
		if (!"RESIDENT".equals(owner) && same.size() >= MAX_GALLERY) {
			throw new BadRequestException("At most " + MAX_GALLERY + " pictures can be added here");
		}
		CloudinaryService.Stored stored = cloud.upload(file, "mystay/pg-" + pgId + "/" + owner.toLowerCase(Locale.ROOT));
		MediaFile row = new MediaFile();
		row.setPgId(pgId);
		row.setOwnerType(owner);
		row.setOwnerId(id);
		row.setKind(k);
		row.setPublicId(stored.id());
		row.setUrl(stored.url());
		try {
			row = files.save(row);
		} catch (RuntimeException e) {
			cloud.delete(stored.id(), stored.url());
			throw e;
		}
		if ("RESIDENT".equals(owner)) {
			same.forEach(this::drop);
		}
		return view(row);
	}

	public void remove(String type, Long id, Long mediaId, Authentication auth) {
		String owner = owner(type);
		Long pgId = scope(owner, id, auth);
		drop(files.findByIdAndPgIdAndOwnerTypeAndOwnerId(mediaId, pgId, owner, id)
				.orElseThrow(() -> new NotFoundException("Picture not found")));
	}

	/** Resident id -> link to the resident's profile photo, for every resident of the property that has one. */
	public java.util.Map<Long, String> residentPhotos(Long pgId) {
		java.util.Map<Long, String> out = new java.util.HashMap<>();
		files.findByPgIdAndOwnerTypeAndKindOrderById(pgId, "RESIDENT", "PHOTO").forEach(m -> out.put(m.getOwnerId(), m.getUrl()));
		return out;
	}

	public String residentPhoto(Long pgId, Long residentId) {
		return files.findByPgIdAndOwnerTypeAndOwnerIdAndKind(pgId, "RESIDENT", residentId, "PHOTO").stream()
				.reduce((first, last) -> last).map(MediaFile::getUrl).orElse(null);
	}

	/** Called when a room, resident or property is deleted. */
	public void removeAll(String ownerType, Long pgId, Long ownerId) {
		files.findByPgIdAndOwnerTypeAndOwnerIdOrderById(pgId, ownerType, ownerId).forEach(this::drop);
	}

	private void drop(MediaFile f) {
		files.delete(f);
		cloud.delete(f.getPublicId(), f.getUrl());
	}

	/** The property the pictures are stored under; throws when the caller may not touch the item. */
	private Long scope(String owner, Long id, Authentication auth) {
		if ("PROPERTY".equals(owner)) {
			UserEntity user = users.findByUsername(auth.getName())
					.orElseThrow(() -> new ForbiddenException("Unknown user"));
			if (pgs.findById(id).isEmpty()) {
				throw new NotFoundException("Property not found");
			}
			if (user.getRole() != Role.SUPER_ADMIN && !access.owns(user, id)) {
				throw new ForbiddenException("You do not have access to this property");
			}
			return id;
		}
		Long pgId = TenantContext.requirePgId();
		boolean found = "ROOM".equals(owner) ? rooms.findByPgIdAndId(pgId, id).isPresent()
				: residents.findByPgIdAndId(pgId, id).isPresent();
		if (!found) {
			throw new NotFoundException("Item not found");
		}
		return pgId;
	}

	private static String owner(String type) {
		String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
		return switch (t) {
			case "property", "pg" -> "PROPERTY";
			case "room" -> "ROOM";
			case "resident" -> "RESIDENT";
			default -> throw new NotFoundException("Unknown item type: " + type);
		};
	}

	private static String kind(String owner, String kind) {
		String k = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(Locale.ROOT);
		if ("RESIDENT".equals(owner)) {
			if (!"PHOTO".equals(k) && !"ID_CARD".equals(k)) {
				throw new BadRequestException("kind must be PHOTO or ID_CARD");
			}
			return k;
		}
		if (k != null && !"IMAGE".equals(k)) {
			throw new BadRequestException("kind must be IMAGE");
		}
		return "IMAGE";
	}

	private static void check(MultipartFile file, boolean allowPdf) {
		if (file == null || file.isEmpty()) {
			throw new BadRequestException("Choose a picture to upload");
		}
		if (file.getSize() > MAX_BYTES) {
			throw new BadRequestException("The file is larger than 10 MB");
		}
		String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
		if (!IMAGES.contains(type) && !(allowPdf && type.equals("application/pdf"))) {
			throw new BadRequestException(allowPdf ? "Upload an image or a PDF" : "Upload an image (JPG, PNG or WebP)");
		}
	}

	private static MediaResponse view(MediaFile f) {
		return new MediaResponse(f.getId(), f.getKind(), f.getUrl());
	}
}
