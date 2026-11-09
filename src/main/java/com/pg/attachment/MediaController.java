package com.pg.attachment;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pg.attachment.MediaService.MediaResponse;

/**
 * Pictures of a property, room or resident: {@code /api/media/property/3}, {@code /api/media/room/7},
 * {@code /api/media/resident/12?kind=ID_CARD}. Staff only.
 */
@RestController
@RequestMapping("/api/media/{type}/{id}")
public class MediaController {

	private final MediaService service;

	public MediaController(MediaService service) {
		this.service = service;
	}

	@GetMapping
	public List<MediaResponse> list(@PathVariable String type, @PathVariable Long id, Authentication auth) {
		return service.list(type, id, auth);
	}

	/** Multipart form with one part named {@code file}. */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public MediaResponse add(@PathVariable String type, @PathVariable Long id,
			@RequestParam(required = false) String kind, @RequestParam("file") MultipartFile file,
			Authentication auth) {
		return service.add(type, id, kind, file, auth);
	}

	@DeleteMapping("/{mediaId}")
	public ResponseEntity<Void> remove(@PathVariable String type, @PathVariable Long id,
			@PathVariable Long mediaId, Authentication auth) {
		service.remove(type, id, mediaId, auth);
		return ResponseEntity.noContent().build();
	}
}
