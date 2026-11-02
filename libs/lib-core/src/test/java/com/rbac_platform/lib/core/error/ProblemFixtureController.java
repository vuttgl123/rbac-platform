package com.rbac_platform.lib.core.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RestController;

/** Test fixture: endpoints that fail in the ways the error model must map. */
@RestController
class ProblemFixtureController {

    record Form(@NotBlank String name, @Email String email) {
    }

    @GetMapping("/test/conflict")
    String conflict() {
        throw ApiException.conflict("slug_taken", "Slug acme is taken");
    }

    @PostMapping("/test/validate")
    String validate(@Valid @RequestBody Form form) {
        return "ok";
    }

    @GetMapping("/test/header")
    String header(@RequestHeader("X-Thing") String thing) {
        return thing;
    }

    @GetMapping("/test/denied")
    String denied() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @GetMapping("/test/param")
    String param(@RequestParam int limit) {
        return "ok";
    }
}
