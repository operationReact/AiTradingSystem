package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperFillRepository.FillWrite;
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

class PaperLearningResearchExportServiceIT extends AbstractPostgresIT {
    @Autowired PaperLearningResearchExportService service;
    @Autowired PaperTradeLotRepository lots;
    @Autowired PaperFillRepository fills;
    @Autowired JdbcClient jdbc;

    private final List<UUID> orderIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID orderId : orderIds) {
            jdbc.sql("""
                    DELETE FROM paper_trade_lot_close
                     WHERE exit_order_id = :id
                        OR lot_id IN (SELECT id FROM paper_trade_lot WHERE entry_order_id = :id)
                    """).param("id", orderId).update();
        }
        for (UUID orderId : orderIds) {
            jdbc.sql("DELETE FROM paper_trade_lot WHERE entry_order_id = :id").param("id", orderId).update();
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id").param("id", orderId).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", orderId).update();
        }
        orderIds.clear();
    }

    @Test
    void exportsOnlyFullyClosedAuthoritativeLineage() {
        Instant openedAt = Instant.parse("2026-11-02T04:00:00Z");
        UUID entryOrder = seedOrder("LEARN", Side.BUY, 4, openedAt, "AUTOMATED");
        UUID entryFill = appendFill(entryOrder, "LEARN", Side.BUY, 4, "100.00", "2.00", "0.40", "0.00", openedAt);
        UUID closedLot = lots.openLot(entryOrder, entryFill, null, "PAPER", "LEARN", ProductType.INTRADAY,
            4, bd("100.00"), bd("402.00"), bd("2.00"), bd("0.40"), openedAt);

        Instant closedAt = openedAt.plusSeconds(600);
        UUID exitOrder = seedOrder("LEARN", Side.SELL, 4, closedAt, "AUTOMATED");
        UUID exitFill = appendFill(exitOrder, "LEARN", Side.SELL, 4, "105.00", "1.20", "0.20", "16.80", closedAt);
        lots.allocateClose(exitOrder, exitFill, null, "LEARN", ProductType.INTRADAY, 4, 4,
            bd("105.00"), bd("1.20"), bd("0.20"), bd("16.80"), "TARGET", closedAt);

        Instant activeAt = openedAt.plusSeconds(1200);
        UUID activeOrder = seedOrder("ACTIVE", Side.BUY, 2, activeAt, "MANUAL");
        UUID activeFill = appendFill(activeOrder, "ACTIVE", Side.BUY, 2, "99.00", "0.50", "0.10", "0.00", activeAt);
        lots.openLot(activeOrder, activeFill, null, "PAPER", "ACTIVE", ProductType.INTRADAY,
            2, bd("99.00"), bd("198.50"), bd("0.50"), bd("0.10"), activeAt);

        PaperLearningResearchExport export = service.export(100);

        assertThat(export.contractVersion()).isEqualTo(PaperLearningResearchExportService.CONTRACT_VERSION);
        assertThat(export.trades()).hasSize(1);
        var trade = export.trades().getFirst();
        assertThat(trade.lotId()).isEqualTo(closedLot);
        assertThat(trade.entryOrderId()).isEqualTo(entryOrder);
        assertThat(trade.entryFillId()).isEqualTo(entryFill);
        assertThat(trade.exitOrderId()).isEqualTo(exitOrder);
        assertThat(trade.exitFillId()).isEqualTo(exitFill);
        assertThat(trade.symbol()).isEqualTo("LEARN");
        assertThat(trade.executionOrigin()).isEqualTo("AUTOMATED");
        assertThat(trade.entrySide()).isEqualTo("BUY");
        assertThat(trade.quantity()).isEqualTo(4);
        assertThat(trade.expectedEntryPrice()).isEqualByComparingTo("100.00");
        assertThat(trade.entryPrice()).isEqualByComparingTo("100.00");
        assertThat(trade.exitPrice()).isEqualByComparingTo("105.00");
        assertThat(trade.allocatedEntryFeeAmount()).isEqualByComparingTo("2.00");
        assertThat(trade.allocatedExitFeeAmount()).isEqualByComparingTo("1.20");
        assertThat(trade.allocatedEntrySlippageAmount()).isEqualByComparingTo("0.40");
        assertThat(trade.allocatedExitSlippageAmount()).isEqualByComparingTo("0.20");
        assertThat(trade.allocatedRealizedPnl()).isEqualByComparingTo("16.80");
        assertThat(trade.exitReason()).isEqualTo("TARGET");
        assertThat(trade.openedAt()).isEqualTo(openedAt);
        assertThat(trade.closedAt()).isEqualTo(closedAt);
    }

    @Test
    void entryMoneyAllocationReconcilesAcrossMultipleExitFills() {
        Instant openedAt = Instant.parse("2026-11-02T05:00:00Z");
        UUID entryOrder = seedOrder("ROUND", Side.BUY, 3, openedAt, "AUTOMATED");
        UUID entryFill = appendFill(entryOrder, "ROUND", Side.BUY, 3, "100.00", "0.01", "0.02", "0.00", openedAt);
        UUID lotId = lots.openLot(entryOrder, entryFill, null, "PAPER", "ROUND", ProductType.INTRADAY,
            3, bd("100.00"), bd("300.01"), bd("0.01"), bd("0.02"), openedAt);

        Instant firstClose = openedAt.plusSeconds(300);
        UUID firstExitOrder = seedOrder("ROUND", Side.SELL, 1, firstClose, "AUTOMATED");
        UUID firstExitFill = appendFill(firstExitOrder, "ROUND", Side.SELL, 1, "101.00", "0.01", "0.01", "0.98", firstClose);
        lots.allocateClose(firstExitOrder, firstExitFill, null, "ROUND", ProductType.INTRADAY, 1, 3,
            bd("101.00"), bd("0.01"), bd("0.01"), bd("0.98"), "TARGET", firstClose);

        Instant secondClose = openedAt.plusSeconds(600);
        UUID secondExitOrder = seedOrder("ROUND", Side.SELL, 2, secondClose, "AUTOMATED");
        UUID secondExitFill = appendFill(secondExitOrder, "ROUND", Side.SELL, 2, "102.00", "0.01", "0.01", "3.98", secondClose);
        lots.allocateClose(secondExitOrder, secondExitFill, null, "ROUND", ProductType.INTRADAY, 2, 2,
            bd("102.00"), bd("0.01"), bd("0.01"), bd("3.98"), "TARGET", secondClose);

        List<PaperLearningResearchExport.ClosedTrade> trades = service.export(100).trades().stream()
            .filter(row -> row.lotId().equals(lotId))
            .toList();

        assertThat(trades).hasSize(2);
        assertThat(trades).extracting(PaperLearningResearchExport.ClosedTrade::allocatedEntryFeeAmount)
            .containsExactly(bd("0.00"), bd("0.01"));
        assertThat(trades).extracting(PaperLearningResearchExport.ClosedTrade::allocatedEntrySlippageAmount)
            .containsExactly(bd("0.01"), bd("0.01"));
        assertThat(trades.stream().map(PaperLearningResearchExport.ClosedTrade::allocatedEntryFeeAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0.01");
        assertThat(trades.stream().map(PaperLearningResearchExport.ClosedTrade::allocatedEntrySlippageAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0.02");
    }

    @Test
    void rejectsUnboundedExportLimit() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.export(5001))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit");
    }

    private UUID seedOrder(String symbol, Side side, int quantity, Instant at, String origin) {
        UUID orderId = UUID.randomUUID();
        orderIds.add(orderId);
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity, submitted_quantity,
                    filled_quantity, reference_price, stop_loss, target_price, status,
                    product_type, execution_origin, lifecycle_reason, created_at, updated_at)
                VALUES (
                    :id, :key, :hash, :symbol, :side, :qty, :qty, 0,
                    100, 95, 110, 'READY', 'INTRADAY', :origin, 'STRATEGY_SIGNAL', :at, :at)
                """)
            .param("id", orderId)
            .param("key", "learning-export-it-" + orderId)
            .param("hash", "b".repeat(64))
            .param("symbol", symbol)
            .param("side", side.name())
            .param("qty", quantity)
            .param("origin", origin)
            .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .update();
        return orderId;
    }

    private UUID appendFill(UUID orderId, String symbol, Side side, int quantity, String price,
                            String fee, String slippage, String realizedPnl, Instant at) {
        BigDecimal fillPrice = bd(price);
        BigDecimal gross = fillPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal net = side == Side.BUY ? gross.add(bd(fee)).negate() : gross.subtract(bd(fee));
        return fills.appendFill(orderId, 1, new FillWrite(
            symbol, side, quantity, fillPrice, fillPrice, gross, bd(fee), bd(slippage), net,
            side == Side.BUY ? gross.add(bd(fee)) : gross,
            bd(realizedPnl), "PAPER_EXECUTION_V1", "FULL_LIQUIDITY", at));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
