package com.rbac_platform.lib.security.access;

import com.rbac_platform.lib.security.Caller;

/**
 * Asks whether the caller holds a relation on an object, and throws when not. A seam so authz-service can
 * answer in-process while other services ask it over HTTP ({@link HttpAccessChecker}).
 */
public interface AccessChecker {

    /**
     * @throws com.rbac_platform.lib.core.error.ApiException 403 when the caller lacks the relation, 503
     *         {@code authz_unavailable} when no decision could be obtained (never treated as allowed)
     */
    void require(Caller caller, String relation, ObjectRef object);
}
