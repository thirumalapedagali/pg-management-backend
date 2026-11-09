package com.pg.pg;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.pg.user.Role;
import com.pg.user.UserEntity;

/**
 * Which properties a user may work in. An owner owns their own {@code pgId} plus every property linked in
 * {@code owner_pgs}; everybody else (manager, resident) only has their own {@code pgId}.
 */
@Component
public class OwnerPgAccess {

	private final OwnerPgRepository links;

	public OwnerPgAccess(OwnerPgRepository links) {
		this.links = links;
	}

	public List<Long> ownedPgIds(UserEntity user) {
		Set<Long> ids = new LinkedHashSet<>();
		if (user.getPgId() != null) {
			ids.add(user.getPgId());
		}
		if (user.getRole() == Role.OWNER) {
			links.findByUserId(user.getId()).forEach(l -> ids.add(l.getPgId()));
		}
		return List.copyOf(ids);
	}

	public boolean owns(UserEntity user, Long pgId) {
		if (pgId == null) {
			return false;
		}
		return pgId.equals(user.getPgId())
				|| (user.getRole() == Role.OWNER && links.existsByUserIdAndPgId(user.getId(), pgId));
	}

	public void link(Long userId, Long pgId) {
		if (userId != null && pgId != null && !links.existsByUserIdAndPgId(userId, pgId)) {
			links.save(new OwnerPg(userId, pgId));
		}
	}
}
