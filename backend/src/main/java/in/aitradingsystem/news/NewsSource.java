package in.aitradingsystem.news;

import java.net.URI;
import java.util.List;
import java.util.Set;

public record NewsSource(String id, String publisher, URI feedUri, Set<String> articleHosts) {
    public static final List<NewsSource> SUPPORTED = List.of(
        new NewsSource("nse-announcements", "NSE India",
            URI.create("https://nsearchives.nseindia.com/content/RSS/Online_announcements.xml"),
            Set.of("nsearchives.nseindia.com", "www.nseindia.com", "nseindia.com")),
        new NewsSource("moneycontrol", "Moneycontrol",
            URI.create("https://www.moneycontrol.com/rss/marketreports.xml"),
            Set.of("www.moneycontrol.com", "moneycontrol.com")),
        new NewsSource("economic-times", "The Economic Times",
            URI.create("https://economictimes.indiatimes.com/markets/stocks/rssfeeds/2146842.cms"),
            Set.of("economictimes.indiatimes.com")),
        new NewsSource("sebi", "Securities and Exchange Board of India",
            URI.create("https://www.sebi.gov.in/sebirss.xml"),
            Set.of("www.sebi.gov.in", "sebi.gov.in")),
        new NewsSource("business-standard-markets", "Business Standard · Markets",
            URI.create("https://www.business-standard.com/rss/markets-106.rss"),
            Set.of("www.business-standard.com", "business-standard.com")),
        new NewsSource("business-standard-stock-market", "Business Standard · Stock Market News",
            URI.create("https://www.business-standard.com/rss/markets/stock-market-news-10618.rss"),
            Set.of("www.business-standard.com", "business-standard.com")),
        new NewsSource("livemint-markets", "Mint · Markets",
            URI.create("https://www.livemint.com/rss/markets"),
            Set.of("www.livemint.com", "livemint.com")),
        new NewsSource("livemint-companies", "Mint · Companies",
            URI.create("https://www.livemint.com/rss/companies"),
            Set.of("www.livemint.com", "livemint.com")),
        new NewsSource("rbi-press-releases", "Reserve Bank of India · Press Releases",
            URI.create("https://rbi.org.in/pressreleases_rss.xml"),
            Set.of("www.rbi.org.in", "rbi.org.in")),
        new NewsSource("rbi-notifications", "Reserve Bank of India · Notifications",
            URI.create("https://rbi.org.in/notifications_rss.xml"),
            Set.of("www.rbi.org.in", "rbi.org.in")),
        new NewsSource("rbi-speeches", "Reserve Bank of India · Speeches",
            URI.create("https://rbi.org.in/speeches_rss.xml"),
            Set.of("www.rbi.org.in", "rbi.org.in"))
    );

    public static NewsSource byId(String id) {
        return SUPPORTED.stream().filter(source -> source.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported news source: " + id));
    }
}
