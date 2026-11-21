package com.pg.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Error body shape: {"code": "...", "message": "..."} */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<Map<String, String>> badRequest(BadRequestException ex) {
		log.warn("BadRequestException: {}", ex.getMessage());
		return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
	}

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<Map<String, String>> notFound(NotFoundException ex) {
		log.warn("NotFoundException: {}", ex.getMessage());
		return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
	}

	@ExceptionHandler(UnauthorizedException.class)
	public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException ex) {
		log.warn("UnauthorizedException: {}", ex.getMessage());
		return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage());
	}

	@ExceptionHandler(ForbiddenException.class)
	public ResponseEntity<Map<String, String>> forbidden(ForbiddenException ex) {
		log.warn("ForbiddenException: {}", ex.getMessage());
		return body(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
	}

	@ExceptionHandler(ServiceUnavailableException.class)
	public ResponseEntity<Map<String, String>> unavailable(ServiceUnavailableException ex) {
		log.error("ServiceUnavailableException: {}", ex.getMessage(), ex);
		return body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.getMessage());
	}

	@ExceptionHandler(LicenseExpiredException.class)
	public ResponseEntity<Map<String, String>> licenseExpired(LicenseExpiredException ex) {
		log.warn("LicenseExpiredException: {}", ex.getMessage());
		Map<String, String> body = new java.util.LinkedHashMap<>();
		body.put("code", "LICENSE_EXPIRED");
		body.put("message", ex.getMessage());
		if (ex.getRole() != null) body.put("role", ex.getRole());
		if (ex.getExpiry() != null) body.put("licenseExpiry", ex.getExpiry().toLocalDate().toString());
		return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException ex) {
		String msg = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> e.getField() + " " + e.getDefaultMessage()).findFirst().orElse("Invalid request");
		log.warn("Validation error: {}", msg);
		return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", msg);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<Map<String, String>> unreadable(Exception ex) {
		log.warn("Malformed request: {}", ex.getMessage());
		return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request");
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException ex) {
		log.warn("File upload size exceeded: {}", ex.getMessage());
		return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The file is larger than 10 MB");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException ex) {
		log.error("Data integrity violation constraint error: {}", ex.getMessage(), ex);
		return body(HttpStatus.CONFLICT, "CONFLICT", "Operation violates a data constraint (duplicate or in use)");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> unhandled(Exception ex) {
		log.error("Unhandled internal server exception: {}", ex.getMessage(), ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An unexpected error occurred. Please try again later.");
	}
}
