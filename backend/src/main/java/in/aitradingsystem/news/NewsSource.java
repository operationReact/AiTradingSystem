package in.aitradingsystem.news;

import java.net.URI;
import java.util.List;
import java.util.Set;

public record NewsSource(String id, String publisher, URI feedUri, Set<String> articleHosts) {
    public static final List<NewsSource> SUPPORTED = List.of(
        new NewsSource("moneycontrol", "Moneycontrol",
            URI.create("https://www.moneycontrol.com/rss/marketreports.xml"),
            Set.of("www.moneycontrol.com", "moneycontrol.com")),
        new NewsSource("economic-times", "The Economic Times",
            URI.create("https://economictimes.indiatimes.com/markets/stocks/rssfeeds/2146842.cms"),
            Set.of("economictimes.indiatimes.com"))
    );

    public static NewsSource byId(String id) {
        return SUPPORTED.stream().filter(source -> source.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported news source: " + id));
    }
}
