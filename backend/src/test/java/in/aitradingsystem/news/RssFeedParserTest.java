package in.aitradingsystem.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RssFeedParserTest {
    private final RssFeedParser parser = new RssFeedParser();

    @Test
    void parsesBoundedRssAndLinksExactWatchlistSymbols() {
        String feed = """
            <rss><channel>
              <item><title>Results for TCS</title><link>https://www.moneycontrol.com/news/tcs.html</link>
                <description><![CDATA[<p>INFY is also mentioned, but TCS2 is not a match.</p>]]></description>
                <pubDate>Tue, 27 Oct 2026 10:00:00 GMT</pubDate>
              </item>
              <item><title>Second article</title><link>https://www.moneycontrol.com/news/second.html</link></item>
            </channel></rss>
            """;
        var parsed = parser.parse(feed.getBytes(StandardCharsets.UTF_8), NewsSource.byId("moneycontrol"),
            List.of("TCS", "INFY"), 1);
        assertEquals(1, parsed.size());
        assertEquals("Results for TCS", parsed.get(0).title());
        assertEquals(List.of("TCS", "INFY"), parsed.get(0).linkedSymbols());
    }

    @Test
    void rejectsDtdAndDoesNotResolveExternalEntities() {
        String feed = """
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss><channel><item><title>&xxe;</title></item></channel></rss>
            """;
        assertThrows(IllegalArgumentException.class, () -> parser.parse(feed.getBytes(StandardCharsets.UTF_8),
            NewsSource.byId("moneycontrol"), List.of(), 10));
    }

    @Test
    void discardsUntrustedArticleUrls() {
        String feed = """
            <rss><channel><item><title>Bad link</title><link>http://127.0.0.1/admin</link>
            <description>Content</description></item></channel></rss>
            """;
        assertEquals(0, parser.parse(feed.getBytes(StandardCharsets.UTF_8),
            NewsSource.byId("moneycontrol"), List.of(), 10).size());
    }

    @Test
    void rejectsOversizedPayloadAndInvalidParserLimits() {
        var source = NewsSource.byId("moneycontrol");
        byte[] valid = "<rss><channel/></rss>".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(new byte[2_097_153], source, List.of(), 10));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(valid, source, List.of(), 0));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(valid, source, List.of(), 101));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(valid, source, null, 10));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(valid, null, List.of(), 10));
    }

    @Test
    void normalizesContentBeforeHashingForIdempotentDeduplication() {
        assertEquals(NewsText.hash("TCS results", "Results beat estimates"),
            NewsText.hash("  TCS   results ", "Results  beat estimates "));
    }
}
