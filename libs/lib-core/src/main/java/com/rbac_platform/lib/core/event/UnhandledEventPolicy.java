package com.rbac_platform.lib.core.event;

/** What to do with an event no handler declares by type. */
public enum UnhandledEventPolicy {
    /** Log a warning and carry on: the event counts as processed. */
    IGNORE,
    /** Fail the delivery, so the event is retried and eventually surfaces as failed. */
    FAIL
}
