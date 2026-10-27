package com.rbac_platform.lib.core.event;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code rbac.events.unhandled}: {@code ignore} (default) or {@code fail}. */
@ConfigurationProperties("rbac.events")
public record EventProperties(@DefaultValue("ignore") UnhandledEventPolicy unhandled) {
}
