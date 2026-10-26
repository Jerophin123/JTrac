package com.jtac.realtime.internal;

import com.jtac.realtime.RealtimeHub;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** In-memory registry of SSE connections per (organization, user). */
@Component
class SseHub implements RealtimeHub {

    static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final int MAX_CONNECTIONS_PER_USER = 10;

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    SseEmitter connect(UUID organizationId, UUID userId) {
        String key = key(organizationId, userId);
        List<SseEmitter> list = emitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        while (list.size() >= MAX_CONNECTIONS_PER_USER) {
            SseEmitter oldest = list.removeFirst();
            oldest.complete();
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(key, list);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        trySend(emitter, "connected", Map.of());
        return emitter;
    }

    @Override
    public void send(UUID organizationId, Collection<UUID> userIds, String type, Map<String, Object> data) {
        for (UUID userId : Set.copyOf(userIds)) {
            List<SseEmitter> list = emitters.get(key(organizationId, userId));
            if (list != null) {
                list.forEach(emitter -> trySend(emitter, type, data));
            }
        }
    }

    /** Keeps proxies from closing idle connections and detects dead clients. */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        emitters.values().forEach(list -> list.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }));
    }

    int connectionCount() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }

    private static void trySend(SseEmitter emitter, String type, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(data));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private static String key(UUID organizationId, UUID userId) {
        return organizationId + ":" + userId;
    }
}
