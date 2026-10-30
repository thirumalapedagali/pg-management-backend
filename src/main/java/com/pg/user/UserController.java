package com.pg.user;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pg.user.UserService.CreateUserRequest;
import com.pg.user.UserService.UpdateUserRequest;
import com.pg.user.UserService.UserResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserService service;

	public UserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	public List<UserResponse> list() {
		return service.list();
	}

	@GetMapping("/{id}")
	public UserResponse get(@PathVariable Long id) {
		return service.get(id);
	}

	@PostMapping
	public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
		return service.create(request);
	}

	@PutMapping("/{id}")
	public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request,
			Authentication auth) {
		return service.update(id, request, auth);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
		service.delete(id, auth);
		return ResponseEntity.noContent().build();
	}
}
