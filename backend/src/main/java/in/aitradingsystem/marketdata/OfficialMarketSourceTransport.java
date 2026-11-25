package in.aitradingsystem.marketdata;

import java.net.URI;

public interface OfficialMarketSourceTransport {
    Response get(URI sourceUri, URI bootstrapUri, int maxBytes);

    record Response(String contentType, byte[] payload) {
        public Response {
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            if (payload == null) {
                payload = new byte[0];
            } else {
                payload = payload.clone();
            }
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
