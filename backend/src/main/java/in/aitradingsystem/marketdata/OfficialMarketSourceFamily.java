package in.aitradingsystem.marketdata;

public enum OfficialMarketSourceFamily {
    NIFTY50_CONSTITUENTS(2_000_000, false),
    NIFTY50_HISTORY(5_000_000, false),
    INDIA_VIX_HISTORY(5_000_000, false),
    SECTOR_INDEX_HISTORY(5_000_000, true);

    private final int maxBytes;
    private final boolean variantRequired;

    OfficialMarketSourceFamily(int maxBytes, boolean variantRequired) {
        this.maxBytes = maxBytes;
        this.variantRequired = variantRequired;
    }

    public int maxBytes() {
        return maxBytes;
    }

    public boolean variantRequired() {
        return variantRequired;
    }
}
