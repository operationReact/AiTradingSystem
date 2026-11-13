package in.aitradingsystem.marketdata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses an official niftyindices.com constituent list
 * ({@code Company Name,Industry,Symbol,Series,ISIN Code}) into NSE cash-equity members.
 *
 * Fails closed: a missing column, an unexpected series, an invalid symbol or a member count
 * outside the published index size means the file is rejected rather than partially applied.
 */
public final class IndexConstituentCsv {
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9&._-]{1,46}");
    private static final Pattern ISIN = Pattern.compile("IN[A-Z0-9]{10}");

    private IndexConstituentCsv() {}

    public record Member(String tradingSymbol, String companyName, String industry, String isin) {}

    /** Universe code and published member count for each constituent family. */
    public record Universe(String code, int expectedMembers, int tolerance) {}

    public static Optional<Universe> universeFor(OfficialMarketSourceFamily family) {
        return Optional.ofNullable(switch (family) {
            case NIFTY50_CONSTITUENTS -> new Universe("NIFTY50", 50, 0);
            case NIFTY100_CONSTITUENTS -> new Universe("NIFTY100", 100, 1);
            case NIFTY500_CONSTITUENTS -> new Universe("NIFTY500", 500, 3);
            case NIFTY_MIDCAP150_CONSTITUENTS -> new Universe("MIDCAP150", 150, 2);
            case NIFTY_SMALLCAP250_CONSTITUENTS -> new Universe("SMALLCAP250", 250, 2);
            case NIFTY_MICROCAP250_CONSTITUENTS -> new Universe("MICROCAP250", 250, 2);
            default -> null;
        });
    }

    public static List<Member> parse(byte[] payload, Universe universe) {
        if (payload == null || payload.length == 0) throw invalid("Empty constituent list");
        String text = new String(payload, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 2) throw invalid("Constituent list has no rows");
        List<String> header = splitCsv(lines[0]);
        int name = column(header, "company name");
        int industry = column(header, "industry");
        int symbol = column(header, "symbol");
        int series = column(header, "series");
        int isin = column(header, "isin code");

        Map<String, Member> members = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> cells = splitCsv(lines[i]);
            if (cells.size() < header.size()) throw invalid("Short constituent row " + i);
            String code = cells.get(symbol).trim().toUpperCase(Locale.ROOT);
            String rowSeries = cells.get(series).trim().toUpperCase(Locale.ROOT);
            String company = cells.get(name).trim();
            String sector = cells.get(industry).trim();
            String isinCode = cells.get(isin).trim().toUpperCase(Locale.ROOT);
            if (!SYMBOL.matcher(code).matches()) throw invalid("Invalid constituent symbol on row " + i);
            if (!"EQ".equals(rowSeries)) throw invalid("Unexpected constituent series on row " + i);
            if (company.isEmpty() || company.length() > 200) throw invalid("Invalid company name on row " + i);
            if (!ISIN.matcher(isinCode).matches()) throw invalid("Invalid ISIN on row " + i);
            if (sector.length() > 80) sector = sector.substring(0, 80);
            if (sector.isEmpty()) sector = "Unclassified";
            Member member = new Member(code + "-EQ", company, sector, isinCode);
            if (members.putIfAbsent(member.tradingSymbol(), member) != null) {
                throw invalid("Duplicate constituent " + code);
            }
        }
        int count = members.size();
        if (Math.abs(count - universe.expectedMembers()) > universe.tolerance()) {
            throw invalid(universe.code() + " list has " + count + " members, expected "
                + universe.expectedMembers());
        }
        return List.copyOf(members.values());
    }

    private static int column(List<String> header, String wanted) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().toLowerCase(Locale.ROOT).equals(wanted)) return i;
        }
        throw invalid("Constituent list is missing the '" + wanted + "' column");
    }

    /** Minimal RFC 4180 splitter: quoted cells may contain commas and doubled quotes. */
    static List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static MarketDataException invalid(String message) {
        return new MarketDataException("INVALID_INDEX_CONSTITUENT_LIST", message);
    }
}
