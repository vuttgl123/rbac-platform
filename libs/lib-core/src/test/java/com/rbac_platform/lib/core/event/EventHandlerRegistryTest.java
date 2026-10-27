package com.rbac_platform.lib.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventHandlerRegistryTest {

    private static EventHandler handler(String... types) {
        return new EventHandler() {
            @Override
            public Set<String> eventTypes() {
                return Set.of(types);
            }

            @Override
            public void handle(EventEnvelope event) {
            }
        };
    }

    @Test
    void handlersAreGroupedByTypeWithWildcardsLast() {
        EventHandler h1 = handler("user.updated");
        EventHandler h2 = handler(EventHandler.ANY);
        EventHandler h3 = handler("user.updated", "user.deleted");
        EventHandlerRegistry registry = new EventHandlerRegistry(List.of(h1, h2, h3));

        assertThat(registry.handlersFor("user.updated")).containsExactly(h1, h3, h2);
        assertThat(registry.handlersFor("user.deleted")).containsExactly(h3, h2);
        assertThat(registry.handlersFor("x")).containsExactly(h2);
        assertThat(registry.hasSpecificHandler("user.updated")).isTrue();
        assertThat(registry.hasSpecificHandler("x")).isFalse();
    }

    @Test
    void rejectsAHandlerWithoutTypes() {
        EventHandler empty = handler();
        EventHandler blank = handler(" ");
        EventHandler nullTypes = new EventHandler() {
            @Override
            public Set<String> eventTypes() {
                return null;
            }

            @Override
            public void handle(EventEnvelope event) {
            }
        };

        assertThatThrownBy(() -> new EventHandlerRegistry(List.of(empty)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(empty.getClass().getName());
        assertThatThrownBy(() -> new EventHandlerRegistry(List.of(blank)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(blank.getClass().getName());
        assertThatThrownBy(() -> new EventHandlerRegistry(List.of(nullTypes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(nullTypes.getClass().getName());
    }
}
