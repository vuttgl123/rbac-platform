package com.rbac_platform.lib.core.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Looks handlers up by event type. Order follows the list given, which for beans is the {@code @Order}
 * order.
 */
public final class EventHandlerRegistry {

    private final Map<String, List<EventHandler>> specific = new HashMap<>();
    private final List<EventHandler> any = new ArrayList<>();

    public EventHandlerRegistry(List<EventHandler> handlers) {
        for (EventHandler handler : handlers) {
            Set<String> types = handler.eventTypes();
            String name = handler.getClass().getName();
            if (types == null || types.isEmpty()) {
                throw new IllegalArgumentException("Event handler " + name + " declares no event types");
            }
            for (String type : types) {
                if (type == null || type.isBlank()) {
                    throw new IllegalArgumentException("Event handler " + name + " declares a blank event type");
                }
                if (EventHandler.ANY.equals(type)) {
                    if (!any.contains(handler)) {
                        any.add(handler);
                    }
                } else {
                    specific.computeIfAbsent(type, t -> new ArrayList<>()).add(handler);
                }
            }
        }
    }

    /** The handlers declaring {@code eventType}, then those declaring {@link EventHandler#ANY}. */
    public List<EventHandler> handlersFor(String eventType) {
        List<EventHandler> result = new ArrayList<>(specific.getOrDefault(eventType, List.of()));
        for (EventHandler handler : any) {
            if (!result.contains(handler)) {
                result.add(handler);
            }
        }
        return result;
    }

    public boolean hasSpecificHandler(String eventType) {
        return specific.containsKey(eventType);
    }
}
