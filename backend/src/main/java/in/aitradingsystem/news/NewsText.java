package in.aitradingsystem.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NewsText {
    private static final Pattern TAGS = Pattern.compile("<[^>]{0,1000}>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private NewsText() {}

    public static String normalize(String value, int maxLength) {
        if (value == null) return "";
        String text = TAGS.matcher(value).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&#160;", " ")
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    public static String hash(String title, String content) {
        String normalized = normalize(title, 500).toLowerCase(Locale.ROOT) + "\n"
            + normalize(content, 8000).toLowerCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
