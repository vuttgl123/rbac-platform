package com.rbac_platform.lib.core.error;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test fixture: an endpoint that always fails, to exercise the error response. */
@RestController
class BoomController {

    @GetMapping("/test/boom")
    String boom() {
        throw new IllegalStateException("boom");
    }
}
