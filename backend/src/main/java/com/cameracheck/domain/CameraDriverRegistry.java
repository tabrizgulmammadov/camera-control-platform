package com.cameracheck.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Looks up the {@link CameraDriver} for a protocol via lightweight
 * {@link CameraDriverDescriptor} beans. The descriptors are always
 * instantiated (they are cheap metadata); the driver beans themselves are
 * Spring-lazy and only get created the first time {@link #forProtocol} is
 * called for their id. Adding a vendor driver is just adding a descriptor
 * bean (plus its lazy driver bean) — no changes in api/domain.
 */
@Component
public class CameraDriverRegistry {

    private final Map<String, CameraDriverDescriptor> byId;

    public CameraDriverRegistry(List<CameraDriverDescriptor> descriptors) {
        Map<String, CameraDriverDescriptor> map = new LinkedHashMap<>();
        descriptors.stream()
                .sorted(Comparator.comparing(CameraDriverDescriptor::id))
                .forEach(d -> {
                    if (map.put(d.id().toUpperCase(), d) != null) {
                        throw new IllegalStateException("Duplicate camera driver id: " + d.id());
                    }
                });
        this.byId = map;
    }

    /** Driver metadata (id + displayName), without instantiating any driver. */
    public List<CameraDriverDescriptor> descriptors() {
        return List.copyOf(byId.values());
    }

    /**
     * Resolves the driver for the given id (case-insensitive). This is the
     * only place a driver bean gets instantiated.
     */
    public CameraDriver forProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            throw new CameraException(ErrorCode.BAD_REQUEST, "driver id must not be blank");
        }
        CameraDriverDescriptor descriptor = byId.get(protocol.trim().toUpperCase());
        if (descriptor == null) {
            throw new CameraException(ErrorCode.BAD_REQUEST,
                    "Unknown driver: " + protocol + " (available: "
                            + String.join(", ", byId.keySet()) + ")");
        }
        return descriptor.factory().get();
    }
}
