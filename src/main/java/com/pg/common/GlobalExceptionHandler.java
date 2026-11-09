package com.pg.common;

import java.util.Map;

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

	private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<Map<String, String>> badRequest(BadRequestException ex) {
		return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
	}

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<Map<String, String>> notFound(NotFoundException ex) {
		return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
	}

	@ExceptionHandler(UnauthorizedException.class)
	public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException ex) {
		return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage());
	}

	@ExceptionHandler(ForbiddenException.class)
	public ResponseEntity<Map<String, String>> forbidden(ForbiddenException ex) {
		return body(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
	}

	@ExceptionHandler(ServiceUnavailableException.class)
	public ResponseEntity<Map<String, String>> unavailable(ServiceUnavailableException ex) {
		return body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.getMessage());
	}

	@ExceptionHandler(LicenseExpiredException.class)
	public ResponseEntity<Map<String, String>> licenseExpired(LicenseExpiredException ex) {
		return body(HttpStatus.PAYMENT_REQUIRED, "LICENSE_EXPIRED", ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException ex) {
		String msg = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> e.getField() + " " + e.getDefaultMessage()).findFirst().orElse("Invalid request");
		return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", msg);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<Map<String, String>> unreadable(Exception ex) {
		return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request");
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException ex) {
		return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The file is larger than 10 MB");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException ex) {
		return body(HttpStatus.CONFLICT, "CONFLICT", "Operation violates a data constraint (duplicate or in use)");
	}
}
