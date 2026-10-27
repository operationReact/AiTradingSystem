package in.aitradingsystem.news;

import java.net.URI;
import java.util.Locale;

public final class NewsUrlValidator {
    private NewsUrlValidator() {}

    public static boolean isAllowedArticleUrl(String value, NewsSource source) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                && host != null
                && source.articleHosts().contains(host.toLowerCase(Locale.ROOT))
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getUserInfo() == null
                && uri.getFragment() == null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
