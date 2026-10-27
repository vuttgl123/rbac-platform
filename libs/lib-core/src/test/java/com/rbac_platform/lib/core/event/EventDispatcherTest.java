package com.rbac_platform.lib.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class EventDispatcherTest {

    private final List<String> calls = new ArrayList<>();

    private EventHandler handler(String name, String type) {
        return handler(name, type, null);
    }

    private EventHandler handler(String name, String type, Exception failure) {
        return new EventHandler() {
            @Override
            public Set<String> eventTypes() {
                return Set.of(type);
            }

            @Override
            public void handle(EventEnvelope event) throws Exception {
                calls.add(name);
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    private static EventEnvelope event(String type) {
        return new EventEnvelope(UUID.randomUUID(), UUID.randomUUID(), "user", "42", type,
                JsonNodeFactory.instance.objectNode(), Map.of(), Instant.now());
    }

    private static EventDispatcher dispatcher(UnhandledEventPolicy policy, EventHandler... handlers) {
        return new EventDispatcher(new EventHandlerRegistry(List.of(handlers)), policy);
    }

    @Test
    void dispatchRunsEveryMatchingHandlerInOrder() throws Exception {
        dispatcher(UnhandledEventPolicy.FAIL,
                handler("a", "user.updated"), handler("any", EventHandler.ANY), handler("b", "user.updated"),
                handler("other", "user.deleted"))
                .dispatch(event("user.updated"));

        assertThat(calls).containsExactly("a", "b", "any");
    }

    @Test
    void firstFailureStopsTheRemainingHandlers() {
        IllegalStateException boom = new IllegalStateException("boom");
        EventDispatcher dispatcher = dispatcher(UnhandledEventPolicy.IGNORE,
                handler("a", "user.updated"), handler("b", "user.updated", boom), handler("c", "user.updated"),
                handler("any", EventHandler.ANY));

        assertThatThrownBy(() -> dispatcher.dispatch(event("user.updated"))).isSameAs(boom);
        assertThat(calls).containsExactly("a", "b");
    }

    @Test
    void ignorePolicyStillRunsWildcardHandlers() throws Exception {
        dispatcher(UnhandledEventPolicy.IGNORE, handler("any", EventHandler.ANY), handler("a", "user.updated"))
                .dispatch(event("unknown"));

        assertThat(calls).containsExactly("any");
    }

    @Test
    void ignorePolicyWithNoHandlersAtAllDoesNothing() throws Exception {
        dispatcher(UnhandledEventPolicy.IGNORE).dispatch(event("unknown"));

        assertThat(calls).isEmpty();
    }

    @Test
    void failPolicyThrowsUnhandledEventExceptionAndRunsNothing() {
        EventDispatcher dispatcher = dispatcher(UnhandledEventPolicy.FAIL,
                handler("any", EventHandler.ANY), handler("a", "user.updated"));

        assertThatThrownBy(() -> dispatcher.dispatch(event("unknown")))
                .isInstanceOf(UnhandledEventException.class)
                .hasMessageContaining("unknown");
        assertThat(calls).isEmpty();
    }
}
