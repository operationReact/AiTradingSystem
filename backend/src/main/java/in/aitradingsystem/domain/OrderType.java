package in.aitradingsystem.domain;

/** Explicit PAPER order instruction type. Broker-specific mapping is deferred to the execution-provider phase. */
public enum OrderType {
    MARKET,
    LIMIT
}
