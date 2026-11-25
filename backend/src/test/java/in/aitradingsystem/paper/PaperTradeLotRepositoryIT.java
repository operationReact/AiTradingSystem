package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperFillRepository.FillWrite;
import in.aitradingsystem.paper.PaperTradeLotRepository.AllocationResult;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PaperTradeLotRepositoryIT extends AbstractPostgresIT {
    @Autowired PaperTradeLotRepository lots;
    @Autowired PaperFillRepository fills;
    @Autowired JdbcClient jdbc;

    private final List<UUID> orderIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM paper_trade_lot_close").update();
        jdbc.sql("DELETE FROM paper_trade_lot").update();
        for (UUID orderId : orderIds) {
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id").param("id", orderId).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", orderId).update();
        }
        orderIds.clear();
    }

    @Test
    void allocatesExitAcrossEntryFillsFifoAndReconcilesMoneyExactly() {
        Instant firstAt = Instant.parse("2026-10-30T04:00:00Z");
        Instant secondAt = firstAt.plusSeconds(60);
        UUID firstOrder = seedOrder(Side.BUY, 4, firstAt);
        UUID firstFill = appendFill(firstOrder, Side.BUY, 4, "100.00", "1.00", "0.20", "0.00", firstAt);
        UUID secondOrder = seedOrder(Side.BUY, 6, secondAt);
        UUID secondFill = appendFill(secondOrder, Side.BUY, 6, "102.00", "1.50", "0.30", "0.00", secondAt);

        UUID firstLot = lots.openLot(firstOrder, firstFill, null, "PAPER", "TESTLOT", ProductType.INTRADAY,
            4, bd("100.00"), bd("401.00"), bd("1.00"), bd("0.20"), firstAt);
        UUID secondLot = lots.openLot(secondOrder, secondFill, null, "PAPER", "TESTLOT", ProductType.INTRADAY,
            6, bd("102.00"), bd("613.50"), bd("1.50"), bd("0.30"), secondAt);

        Instant exitAt = secondAt.plusSeconds(60);
        UUID exitOrder = seedOrder(Side.SELL, 5, exitAt);
        UUID exitFill = appendFill(exitOrder, Side.SELL, 5, "110.00", "2.01", "0.51", "37.77", exitAt);

        AllocationResult result = lots.allocateClose(
            exitOrder, exitFill, null, "TESTLOT", ProductType.INTRADAY, 5, 10,
            bd("110.00"), bd("2.01"), bd("0.51"), bd("37.77"), "TARGET", exitAt);

        assertThat(result.complete()).isTrue();
        assertThat(result.incompleteReason()).isNull();
        assertThat(result.allocations()).hasSize(2);
        assertThat(result.allocations().get(0).lotId()).isEqualTo(firstLot);
        assertThat(result.allocations().get(0).allocatedQuantity()).isEqualTo(4);
        assertThat(result.allocations().get(0).remainingLotQuantity()).isZero();
        assertThat(result.allocations().get(1).lotId()).isEqualTo(secondLot);
        assertThat(result.allocations().get(1).allocatedQuantity()).isEqualTo(1);
        assertThat(result.allocations().get(1).remainingLotQuantity()).isEqualTo(5);
        assertThat(result.allocations()).extracting(it -> it.allocatedFeeAmount())
            .containsExactly(bd("1.61"), bd("0.40"));
        assertThat(result.allocations()).extracting(it -> it.allocatedSlippageAmount())
            .containsExactly(bd("0.41"), bd("0.10"));
        assertThat(result.allocations()).extracting(it -> it.allocatedRealizedPnl())
            .containsExactly(bd("30.22"), bd("7.55"));

        assertThat(lotState(firstLot)).isEqualTo(new LotState(0, "EXHAUSTED", null));
        assertThat(lotState(secondLot)).isEqualTo(new LotState(5, "ACTIVE", null));
    }

    @Test
    void invalidatesTrackedLotsWhenLegacyPositionQuantityMakesAttributionUnsafe() {
        Instant entryAt = Instant.parse("2026-10-30T04:00:00Z");
        UUID entryOrder = seedOrder(Side.BUY, 4, entryAt);
        UUID entryFill = appendFill(entryOrder, Side.BUY, 4, "100.00", "1.00", "0.20", "0.00", entryAt);
        UUID lotId = lots.openLot(entryOrder, entryFill, null, "PAPER", "TESTLOT", ProductType.INTRADAY,
            4, bd("100.00"), bd("401.00"), bd("1.00"), bd("0.20"), entryAt);

        Instant exitAt = entryAt.plusSeconds(60);
        UUID exitOrder = seedOrder(Side.SELL, 2, exitAt);
        UUID exitFill = appendFill(exitOrder, Side.SELL, 2, "105.00", "0.50", "0.10", "8.00", exitAt);

        AllocationResult result = lots.allocateClose(
            exitOrder, exitFill, null, "TESTLOT", ProductType.INTRADAY, 2, 10,
            bd("105.00"), bd("0.50"), bd("0.10"), bd("8.00"), "STOP_LOSS", exitAt);

        assertThat(result.complete()).isFalse();
        assertThat(result.incompleteReason()).isEqualTo(PaperTradeLotRepository.POSITION_QUANTITY_MISMATCH);
        assertThat(result.allocations()).isEmpty();
        assertThat(lotState(lotId)).isEqualTo(
            new LotState(0, "INVALIDATED", PaperTradeLotRepository.POSITION_QUANTITY_MISMATCH));
        Integer closeCount = jdbc.sql("SELECT count(*) FROM paper_trade_lot_close WHERE exit_fill_id = :fillId")
            .param("fillId", exitFill).query(Integer.class).single();
        assertThat(closeCount).isZero();
    }

    private UUID seedOrder(Side side, int quantity, Instant at) {
        UUID orderId = UUID.randomUUID();
        orderIds.add(orderId);
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity, submitted_quantity,
                    filled_quantity, reference_price, stop_loss, target_price, status,
                    product_type, execution_origin, created_at, updated_at)
                VALUES (
                    :id, :key, :hash, 'TESTLOT', :side, :qty, :qty, 0,
                    100, 95, 110, 'READY', 'INTRADAY', 'AUTOMATED', :at, :at)
                """)
            .param("id", orderId)
            .param("key", "lot-it-" + orderId)
            .param("hash", "a".repeat(64))
            .param("side", side.name())
            .param("qty", quantity)
            .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .update();
        return orderId;
    }

    private UUID appendFill(UUID orderId, Side side, int quantity, String price,
                            String fee, String slippage, String realizedPnl, Instant at) {
        BigDecimal fillPrice = bd(price);
        BigDecimal gross = fillPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal net = side == Side.BUY
            ? gross.add(bd(fee)).negate()
            : gross.subtract(bd(fee));
        return fills.appendFill(orderId, 1, new FillWrite(
            "TESTLOT", side, quantity, fillPrice, fillPrice, gross, bd(fee), bd(slippage), net,
            side == Side.BUY ? gross.add(bd(fee)) : gross,
            bd(realizedPnl), "PAPER_EXECUTION_V1", "FULL_LIQUIDITY", at));
    }

    private LotState lotState(UUID lotId) {
        return jdbc.sql("""
                SELECT remaining_quantity, lineage_status, invalidation_reason
                  FROM paper_trade_lot WHERE id = :id
                """)
            .param("id", lotId)
            .query((rs, row) -> new LotState(
                rs.getInt("remaining_quantity"), rs.getString("lineage_status"),
                rs.getString("invalidation_reason")))
            .single();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record LotState(int remainingQuantity, String status, String invalidationReason) {}
}
