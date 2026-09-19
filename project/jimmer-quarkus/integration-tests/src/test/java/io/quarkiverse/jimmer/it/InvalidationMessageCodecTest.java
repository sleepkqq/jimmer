package io.quarkiverse.jimmer.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.babyfish.jimmer.sql.cache.CacheTracker;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.jimmer.it.entity.Book;
import io.quarkiverse.jimmer.runtime.cache.QuarkusRedisCacheTracker;

/**
 * Round-trips the tracker's pub/sub message through Jackson — the same serialization the Quarkus
 * Redis client applies on the channel. Guards the typed-ids rebuild in toEvent(): JSON narrows
 * longs to ints and turns UUIDs into strings, which would never match cache keys otherwise.
 */
class InvalidationMessageCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsObjectInvalidationWithTypedIds() throws Exception {
        UUID trackerId = UUID.randomUUID();
        QuarkusRedisCacheTracker.InvalidationMessage message =
                new QuarkusRedisCacheTracker.InvalidationMessage();
        message.trackerId = trackerId;
        message.typeName = Book.class.getName();
        message.propName = null;
        message.ids = List.of(1L, 2L, 3L);

        QuarkusRedisCacheTracker.InvalidationMessage decoded = roundTrip(message);

        assertEquals(trackerId, decoded.trackerId);
        assertEquals(Book.class.getName(), decoded.typeName);
        assertNull(decoded.propName);

        CacheTracker.InvalidateEvent event = toEvent(decoded);
        assertEquals(List.of(1L, 2L, 3L), List.copyOf(event.getIds()));
    }

    @Test
    void roundTripsAssociationInvalidation() throws Exception {
        QuarkusRedisCacheTracker.InvalidationMessage message =
                new QuarkusRedisCacheTracker.InvalidationMessage();
        message.trackerId = UUID.randomUUID();
        message.typeName = Book.class.getName();
        message.propName = "authors";
        message.ids = List.of(42L);

        CacheTracker.InvalidateEvent event = toEvent(roundTrip(message));

        assertEquals("authors", event.getProp().getName());
        assertEquals(List.of(42L), List.copyOf(event.getIds()));
    }

    private QuarkusRedisCacheTracker.InvalidationMessage roundTrip(
            QuarkusRedisCacheTracker.InvalidationMessage message) throws Exception {
        byte[] json = mapper.writeValueAsBytes(message);
        return mapper.readValue(json, QuarkusRedisCacheTracker.InvalidationMessage.class);
    }

    private CacheTracker.InvalidateEvent toEvent(
            QuarkusRedisCacheTracker.InvalidationMessage message) throws Exception {
        var method = message.getClass().getDeclaredMethod("toEvent");
        method.setAccessible(true);
        return (CacheTracker.InvalidateEvent) method.invoke(message);
    }
}
