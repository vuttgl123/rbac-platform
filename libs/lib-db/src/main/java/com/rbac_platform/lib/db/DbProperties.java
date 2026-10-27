package com.rbac_platform.lib.db;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param expectedRole the database role this service must connect as (rbac.db.expected-role); the startup check enforces it */
@ConfigurationProperties("rbac.db")
public record DbProperties(String expectedRole) {
}
