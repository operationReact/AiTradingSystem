package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.util.UUID;

public record OfficialMarketSourceSnapshot(
        UUID snapshotId,
        OfficialMarketSourceFamily sourceFamily,
        String sourceVariant,
        String sourceUrl,
        Instant observedAt,
        String payloadSha256,
        String contentType,
        byte[] payload,
        int byteCount,
        Instant createdAt) {

    public OfficialMarketSourceSnapshot {
        payload = payload == null ? null : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload == null ? null : payload.clone();
    }
}
