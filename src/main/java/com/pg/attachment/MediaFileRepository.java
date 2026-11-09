package com.pg.attachment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFileRepository extends JpaRepository<MediaFile, Long> {

	List<MediaFile> findByPgIdAndOwnerTypeAndOwnerIdOrderById(Long pgId, String ownerType, Long ownerId);

	List<MediaFile> findByPgIdAndOwnerTypeAndOwnerIdAndKind(Long pgId, String ownerType, Long ownerId, String kind);

	List<MediaFile> findByOwnerTypeAndOwnerIdInOrderById(String ownerType, Collection<Long> ownerIds);

	List<MediaFile> findByPgIdAndOwnerTypeOrderById(Long pgId, String ownerType);

	List<MediaFile> findByPgIdAndOwnerTypeAndKindOrderById(Long pgId, String ownerType, String kind);

	Optional<MediaFile> findByIdAndPgIdAndOwnerTypeAndOwnerId(Long id, Long pgId, String ownerType, Long ownerId);
}
