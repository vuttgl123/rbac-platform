package com.rbac_platform.lib.security.access;

import java.util.UUID;

/** What a permission is asked about: an object type and its id, e.g. {@code ("tenant", "<uuid>")}. */
public record ObjectRef(String type, String id) {

    public static ObjectRef tenant(UUID tenantId) {
        return new ObjectRef("tenant", tenantId.toString());
    }
}
