package anticope.esixtwoone.sources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import meteordevelopment.meteorclient.utils.network.Http;

import java.util.ArrayList;
import java.util.List;

public class ESixTwoOne implements Source {
    private final String domain;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    public ESixTwoOne(String domain) {
        this.domain = domain;
    }

    @Override
    public List<String> getPageImageUrls(String filter, Size size, int page) {
        List<String> urls = new ArrayList<>();
        try {
            String formattedTags = filter.trim()
                .replace(" ", "+")
                .replace(":", "%3A");

            String url = String.format("%s/posts.json?tags=%s&limit=320&page=%d", 
                domain, formattedTags, page);
            
            JsonObject result = Http.get(url)
                .header("User-Agent", USER_AGENT)
                .sendJson(JsonObject.class);

            if (result == null || !result.has("posts")) {
                return urls;
            }

            JsonArray posts = result.getAsJsonArray("posts");
            for (int i = 0; i < posts.size(); i++) {
                JsonObject post = posts.get(i).getAsJsonObject();
                String urlStr = getUrlForSize(post, size);
                if (urlStr != null && !isWebP(urlStr)) {
                    urls.add(urlStr);
                }
            }
        } catch (Exception e) {
            System.err.println("[E621] API error: " + e.getMessage());
        }
        return urls;
    }

    @Override
    public List<String> getAllImageUrls(String filter, Size size) {
        return getPageImageUrls(filter, size, 1); // Just get first page
    }

    @Override
    public String randomImage(String filter, Size size) {
        List<String> urls = getPageImageUrls(filter, size, 1);
        return urls.isEmpty() ? null : urls.get(0);
    }

    @Override
    public void reset() {
        // Reset any internal state if needed
    }

    private String getUrlForSize(JsonObject post, Size size) {
        if (post == null) return null;
        try {
            switch (size) {
                case preview: return post.has("preview") ? post.getAsJsonObject("preview").get("url").getAsString() : null;
                case sample: return post.has("sample") ? post.getAsJsonObject("sample").get("url").getAsString() : null;
                case file: return post.has("file") ? post.getAsJsonObject("file").get("url").getAsString() : null;
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isWebP(String url) {
        return url != null && url.toLowerCase().endsWith(".webp");
    }
}