package am.retailai.tenant;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed tenant identifier. Never pass a raw UUID through the import pipeline. */
public record TenantId(UUID value) {
    public TenantId {
        Objects.requireNonNull(value, "tenant id must not be null");
    }

    public static TenantId of(String uuid) {
        return new TenantId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
