package in.aitradingsystem.news;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

@Component
public class RssFeedParser {
    public List<NewsArticle> parse(byte[] feed, NewsSource source, List<String> watchlist, int maxItems) {
        if (feed == null || feed.length == 0 || feed.length > 2_097_152) {
            throw new IllegalArgumentException("RSS feed payload must be between 1 and 2097152 bytes");
        }
        if (source == null || !NewsSource.SUPPORTED.contains(source)) {
            throw new IllegalArgumentException("Unsupported RSS source");
        }
        if (watchlist == null || maxItems < 1 || maxItems > 100) {
            throw new IllegalArgumentException("Invalid RSS parsing limits");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setXmlProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setXmlProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(feed), "UTF-8");
            List<NewsArticle> articles = new ArrayList<>();
            while (reader.hasNext() && articles.size() < maxItems) {
                if (reader.next() == XMLStreamConstants.DTD) {
                    throw new IllegalArgumentException("DTD is not allowed in news feeds");
                }
                if (reader.isStartElement() && "item".equals(reader.getLocalName())) {
                    NewsArticle article = readItem(reader, source, watchlist);
                    if (article != null) articles.add(article);
                }
            }
            return articles;
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid RSS feed", e);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (XMLStreamException ignored) {}
            }
        }
    }

    private NewsArticle readItem(XMLStreamReader reader, NewsSource source, List<String> watchlist)
        throws XMLStreamException {
        String title = "";
        String link = "";
        String content = "";
        String pubDate = "";
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT && "item".equals(reader.getLocalName())) break;
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            String name = reader.getLocalName().toLowerCase(Locale.ROOT);
            if (name.equals("title")) title = readText(reader, 1000);
            else if (name.equals("link")) link = readText(reader, 2048);
            else if (name.equals("description") || name.equals("encoded")) {
                String value = readText(reader, 10000);
                if (value.length() > content.length()) content = value;
            } else if (name.equals("pubdate") || name.equals("published") || name.equals("updated")) {
                pubDate = readText(reader, 128);
            }
        }
        title = NewsText.normalize(title, 500);
        content = NewsText.normalize(content, 8000);
        if (title.isBlank() || !NewsUrlValidator.isAllowedArticleUrl(link, source)) return null;
        Set<String> symbols = new LinkedHashSet<>();
        String searchable = (title + " " + content).toUpperCase(Locale.ROOT);
        for (String symbol : watchlist) {
            if (symbol != null && symbol.matches("[A-Z0-9&-]{1,20}")
                && Pattern.compile("(?<![A-Z0-9&-])" + Pattern.quote(symbol)
                    + "(?![A-Z0-9&-])").matcher(searchable).find()) {
                symbols.add(symbol);
            }
        }
        return new NewsArticle(source.id(), source.publisher(), link, parseDate(pubDate), title,
            content, NewsText.hash(title, content), List.copyOf(symbols));
    }

    private static String readText(XMLStreamReader reader, int maxLength) throws XMLStreamException {
        String text = reader.getElementText();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static Instant parseDate(String value) {
        try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
        catch (DateTimeParseException ignored) {
            try { return Instant.parse(value); } catch (DateTimeParseException ignoredAgain) { return null; }
        }
    }

    private static void setXmlProperty(XMLInputFactory factory, String name, Object value) {
        try { factory.setProperty(name, value); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("Secure XML parsing is unavailable", e); }
    }
}
