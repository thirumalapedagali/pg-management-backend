package com.pg.common;

import java.util.List;

import org.springframework.data.domain.Page;

/** Stable JSON page shape: {content, page, size, totalElements, totalPages}. */
public record Paged<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static <T> Paged<T> of(Page<T> p) {
		return new Paged<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
	}
}
