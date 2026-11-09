package com.pg.attachment;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pg.attachment.AttachmentService.AttachmentResponse;

/** The file attached to one row, e.g. {@code /api/attachments/complaint/12}. Staff only. */
@RestController
@RequestMapping("/api/attachments/{entity}/{id}")
public class AttachmentController {

	private final AttachmentService service;

	public AttachmentController(AttachmentService service) {
		this.service = service;
	}

	@GetMapping
	public AttachmentResponse get(@PathVariable String entity, @PathVariable Long id) {
		return service.get(entity, id);
	}

	/** Multipart form with one part named {@code file}; replaces any file already attached. */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public AttachmentResponse upload(@PathVariable String entity, @PathVariable Long id,
			@RequestParam("file") MultipartFile file) {
		return service.attach(entity, id, file);
	}

	@DeleteMapping
	public ResponseEntity<Void> remove(@PathVariable String entity, @PathVariable Long id) {
		service.remove(entity, id);
		return ResponseEntity.noContent().build();
	}
}
