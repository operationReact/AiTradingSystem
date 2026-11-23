package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse.Contract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, source-verified read-only option/future subscription selector.
 *
 * Requires two independently verifiable current provider sources:
 * - actual NSE_FO identities from the Upstox daily BOD master
 * - an actual Upstox V2 chain of the same underlying and expiry, for ordering
 *
 * The V2 spot orders near-the-money candidates only; it is never a fresh
 * V3 tick, trading signal, authorized execution quote or training source.
 * Does not create a socket, place a broker order, or invent a strike/contract.
 */
public final class UpstoxFnoListedSubscriptionPlanner {
    private UpstoxFnoListedSubscriptionPlanner() {}

    public static List<String> plan(List<Contract> master,
            UpstoxAnalyticsSource.OptionChainSnapshot chain,
            String underlying, LocalDate expiry, LocalDate today,
            int budget) {
        if (master == null || chain == null || underlying == null ||
                expiry == null || today == null || expiry.isBefore(today) ||
                budget < 2 || budget > UpstoxFnoV3LiveFeedService.MAX_INCREMENTAL_KEYS ||
                !"UPSTOX_ANALYTICS".equals(chain.source()) ||
                chain.exchangeQuoteTimeVerified() || chain.strikes() == null ||
                chain.strikes().isEmpty()) {
            throw new IllegalArgumentException("FNO_SUBSCRIPTION_SOURCE_UNVERIFIED");
        }
        BigDecimal spot = chain.strikes().getFirst().underlyingSpotPrice();
        if (spot == null || spot.signum() <= 0)
            throw new IllegalArgumentException("FNO_SUBSCRIPTION_SPOT_UNVERIFIED");
        var futures = new ArrayList<Contract>();
        var options = new HashMap<String, Contract>();
        for (Contract contract : master) {
            if (!underlying.equals(contract.underlyingKey()) ||
                    !contract.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("FNO_SUBSCRIPTION_UNLISTED_CONTRACT");
            }
            if ("FUT".equals(contract.type())) {
                if (!contract.expiry().isBefore(today)) futures.add(contract);
            } else if (expiry.equals(contract.expiry()) &&
                    ("CE".equals(contract.type()) || "PE".equals(contract.type()))) {
                if (options.put(contract.instrumentKey(), contract) != null)
                    throw new IllegalArgumentException("FNO_SUBSCRIPTION_DUPLICATE_CONTRACT");
            }
        }
        futures.sort(Comparator.comparing(Contract::expiry)
            .thenComparing(Contract::instrumentKey));
        var selected = new ArrayList<String>();
        for (int i = 0; i < futures.size() && i < 2 && selected.size() < budget; i++) {
            selected.add(futures.get(i).instrumentKey());
        }
        var strikes = new ArrayList<>(chain.strikes());
        strikes.sort(Comparator
            .comparing((UpstoxAnalyticsSource.FnoOptionStrike s) ->
                s.strikePrice().subtract(spot).abs())
            .thenComparing(UpstoxAnalyticsSource.FnoOptionStrike::strikePrice));
        Set<String> seen = new HashSet<>(selected);
        for (var row : strikes) {
            if (selected.size() + 2 > budget) break;
            if (!underlying.equals(row.underlyingKey()) ||
                    !expiry.equals(row.expiry()) ||
                    row.strikePrice() == null || row.strikePrice().signum() <= 0 ||
                    row.call() == null || row.put() == null) {
                continue;
            }
            Contract call = options.get(row.call().instrumentKey());
            Contract put = options.get(row.put().instrumentKey());
            if (call == null || put == null ||
                    !"CE".equals(call.type()) || !"PE".equals(put.type()) ||
                    call.strikePrice().compareTo(row.strikePrice()) != 0 ||
                    put.strikePrice().compareTo(row.strikePrice()) != 0 ||
                    seen.contains(call.instrumentKey()) ||
                    seen.contains(put.instrumentKey())) continue;
            selected.add(call.instrumentKey());
            selected.add(put.instrumentKey());
            seen.add(call.instrumentKey());
            seen.add(put.instrumentKey());
        }
        if (selected.isEmpty() || seen.size() != selected.size()) {
            throw new IllegalStateException("FNO_SUBSCRIPTION_NO_VERIFIED_CONTRACTS");
        }
        return List.copyOf(selected);
    }
}
