package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Original intraday NSE_EQ and NSE_FO BID/ASK, not finalized EOD V105. */
@Component
public class Fno170VerifiedIntradayPairStore {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String SQL="""
        SELECT s.provider_code,s.spot_key,s.futures_key,s.listed_from,
               s.expiry,s.lot_size,s.spot_bid,s.spot_ask,
               s.futures_bid,s.futures_ask,s.spot_exchange_at,
               s.futures_exchange_at,s.spot_first_observed_at,
               s.futures_first_observed_at,s.funding_annual_bps,
               s.dividend_annual_bps,s.round_trip_cost_bps,s.min_net_edge_bps,
               s.spot_source_digest,s.futures_source_digest,
               s.listing_source_digest,s.financing_cost_source_digest,
               s.delivery_verified,s.costs_verified
        FROM fno_verified_intraday_cash_futures_basis_source s
        JOIN golden_data_provider provider
          ON provider.provider_code=s.provider_code
          AND provider.source_kind='EXTERNAL'
        JOIN golden_historical_exchange_session_evidence cash_cal
          ON cash_cal.evidence_id=s.spot_calendar_evidence_id
          AND cash_cal.provider_id=provider.provider_id
          AND cash_cal.source_domain='nse_eq_trading_calendar'
          AND cash_cal.exchange='NSE'
          AND cash_cal.session_status='TRADING'
          AND cash_cal.session_date=
              (s.spot_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        JOIN golden_historical_exchange_session_evidence future_cal
          ON future_cal.evidence_id=s.futures_calendar_evidence_id
          AND future_cal.provider_id=provider.provider_id
          AND future_cal.source_domain='nse_fo_trading_calendar'
          AND future_cal.exchange='NSE'
          AND future_cal.session_status='TRADING'
          AND future_cal.session_date=
              (s.futures_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        JOIN golden_source_rights_receipt spot_rights
          ON spot_rights.receipt_id=s.spot_rights_receipt_id
          AND spot_rights.provider_id=provider.provider_id
          AND spot_rights.source_domain='nse_eq_intraday_bidask'
          AND spot_rights.permitted_use='MODEL_INFERENCE'
          AND spot_rights.receipt_status='VERIFIED'
        JOIN golden_source_rights_receipt future_rights
          ON future_rights.receipt_id=s.futures_rights_receipt_id
          AND future_rights.provider_id=provider.provider_id
          AND future_rights.source_domain='nse_fo_intraday_bidask'
          AND future_rights.permitted_use='MODEL_INFERENCE'
          AND future_rights.receipt_status='VERIFIED'
        JOIN golden_source_rights_receipt listing_rights
          ON listing_rights.receipt_id=s.listing_rights_receipt_id
          AND listing_rights.provider_id=provider.provider_id
          AND listing_rights.source_domain='nse_fo_contract_listing'
          AND listing_rights.permitted_use='MODEL_INFERENCE'
          AND listing_rights.receipt_status='VERIFIED'
        JOIN golden_source_rights_receipt cost_rights
          ON cost_rights.receipt_id=s.costs_rights_receipt_id
          AND cost_rights.provider_id=provider.provider_id
          AND cost_rights.source_domain='nse_eq_futures_basis_costs'
          AND cost_rights.permitted_use='MODEL_INFERENCE'
          AND cost_rights.receipt_status='VERIFIED'
        WHERE s.spot_key=? AND s.futures_key=?
          AND s.expiry=? AND s.lot_size=?
          AND s.spot_exchange_at>=? AND s.spot_exchange_at<=?
          AND s.futures_exchange_at>=? AND s.futures_exchange_at<=?
          AND s.spot_first_observed_at<=?
          AND s.futures_first_observed_at<=?
          AND s.captured_at<=?
          AND s.retain_until>=?
          AND cash_cal.first_available_at<=?
          AND cash_cal.verified_at<=? AND cash_cal.captured_at<=?
          AND future_cal.first_available_at<=?
          AND future_cal.verified_at<=? AND future_cal.captured_at<=?
          AND spot_rights.valid_from<=?
          AND (spot_rights.valid_to IS NULL OR spot_rights.valid_to>?)
          AND spot_rights.verified_at<=? AND spot_rights.captured_at<=?
          AND future_rights.valid_from<=?
          AND (future_rights.valid_to IS NULL OR future_rights.valid_to>?)
          AND future_rights.verified_at<=? AND future_rights.captured_at<=?
          AND listing_rights.valid_from<=?
          AND (listing_rights.valid_to IS NULL OR listing_rights.valid_to>?)
          AND listing_rights.verified_at<=? AND listing_rights.captured_at<=?
          AND cost_rights.valid_from<=?
          AND (cost_rights.valid_to IS NULL OR cost_rights.valid_to>?)
          AND cost_rights.verified_at<=? AND cost_rights.captured_at<=?
        ORDER BY s.futures_exchange_at DESC LIMIT 1
        """;

    public record Pair(
        String provider,String spotKey,String futuresKey,LocalDate listedFrom,
        LocalDate expiry,int lotSize,BigDecimal spotBid,BigDecimal spotAsk,
        BigDecimal futuresBid,BigDecimal futuresAsk,
        Instant spotExchangeAt,Instant futuresExchangeAt,
        Instant spotFirstObservedAt,Instant futuresFirstObservedAt,
        BigDecimal fundingAnnualBps,BigDecimal dividendAnnualBps,
        BigDecimal roundTripCostBps,BigDecimal minNetEdgeBps,
        String spotDigest,String futuresDigest,String listingDigest,
        String financingCostDigest,boolean deliveryVerified,
        boolean costsVerified) {}

    private final JdbcTemplate jdbc;
    public Fno170VerifiedIntradayPairStore(JdbcTemplate jdbc){
        this.jdbc=Objects.requireNonNull(jdbc);
    }

    /** Four independent rights and both calendars must predate asOf. */
    public List<Pair> readExactPair(
            String spot,String future,LocalDate expiry,int lot,Instant asOf) {
        if(spot==null||!spot.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                ||future==null||!future.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                ||expiry==null||lot<1||lot>100000||asOf==null
                ||expiry.isBefore(asOf.atZone(IST).toLocalDate()))
            throw new IllegalArgumentException("STR170_PAIRED_SOURCE_SCOPE_INVALID");

        Object[] bind=new Object[34];
        bind[0]=spot;bind[1]=future;bind[2]=Date.valueOf(expiry);bind[3]=lot;
        Timestamp lower=Timestamp.from(asOf.minusSeconds(15));
        Timestamp cutoff=Timestamp.from(asOf);
        bind[4]=lower;bind[5]=cutoff;bind[6]=lower;bind[7]=cutoff;
        bind[8]=cutoff;bind[9]=cutoff;bind[10]=cutoff;
        bind[11]=Date.valueOf(LocalDate.now(IST));
        Arrays.fill(bind,12,bind.length,cutoff);
        return jdbc.query(SQL,(rs,n)->new Pair(
            rs.getString("provider_code"),rs.getString("spot_key"),
            rs.getString("futures_key"),
            rs.getObject("listed_from",LocalDate.class),
            rs.getObject("expiry",LocalDate.class),
            rs.getInt("lot_size"),rs.getBigDecimal("spot_bid"),
            rs.getBigDecimal("spot_ask"),rs.getBigDecimal("futures_bid"),
            rs.getBigDecimal("futures_ask"),
            rs.getTimestamp("spot_exchange_at").toInstant(),
            rs.getTimestamp("futures_exchange_at").toInstant(),
            rs.getTimestamp("spot_first_observed_at").toInstant(),
            rs.getTimestamp("futures_first_observed_at").toInstant(),
            rs.getBigDecimal("funding_annual_bps"),
            rs.getBigDecimal("dividend_annual_bps"),
            rs.getBigDecimal("round_trip_cost_bps"),
            rs.getBigDecimal("min_net_edge_bps"),
            rs.getString("spot_source_digest"),
            rs.getString("futures_source_digest"),
            rs.getString("listing_source_digest"),
            rs.getString("financing_cost_source_digest"),
            rs.getBoolean("delivery_verified"),
            rs.getBoolean("costs_verified")),bind);
    }

    public int purgeExpired(LocalDate today){
        return jdbc.update(
            "DELETE FROM fno_verified_intraday_cash_futures_basis_source "
            +"WHERE retain_until < ?",Date.valueOf(Objects.requireNonNull(today)));
    }
    @Scheduled(cron="0 59 3 * * *",zone="Asia/Kolkata")
    public void scheduledRetentionPurge(){
        purgeExpired(LocalDate.now(IST));
    }
}
