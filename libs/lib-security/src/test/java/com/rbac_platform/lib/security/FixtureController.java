package com.rbac_platform.lib.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test fixture: endpoints behind and outside the security chain. */
@RestController
class FixtureController {

    record WhoAmI(UUID tenantId, UUID subjectId, UUID sessionId, List<String> amr) {
    }

    @GetMapping("/test/whoami")
    WhoAmI whoami(Caller caller) {
        return new WhoAmI(caller.tenantId(), caller.subjectId(), caller.sessionId(), caller.amr());
    }

    @GetMapping("/test/public/ping")
    String ping() {
        return "pong";
    }

    @GetMapping("/test/public/caller")
    String publicCaller(Caller caller) {
        return "unreachable";
    }

    @GetMapping("/internal/test/ping")
    String internal() {
        return "reached";
    }

    @GetMapping("/test/denied")
    String denied() {
        throw new AccessDeniedException("fixture denies everyone");
    }
}
