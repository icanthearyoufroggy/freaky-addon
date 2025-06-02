package anticope.esixtwoone.sources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import meteordevelopment.meteorclient.utils.network.Http;

import java.util.ArrayList;
import java.util.List;

public class Gelbooru implements Source {
    private final String domain;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    public Gelbooru(String domain) {
        this.domain = domain;
    }

    @Override
    public List<String> getPageImageUrls(String filter, Size size, int page) {
        List<String> urls = new ArrayList<>();
        try {
            String url = String.format("%s/index.php?page=dapi&s=post&q=index&json=1&tags=%s&pid=%d",
                domain, filter.trim().replace(" ", "+"), page-1);
            
            JsonObject result = Http.get(url)
                .header("User-Agent", USER_AGENT)
                .sendJson(JsonObject.class);

            if (result != null && result.has("post")) {
                JsonArray posts = result.getAsJsonArray("post");
                for (int i = 0; i < posts.size(); i++) {
                    JsonObject post = posts.get(i).getAsJsonObject();
                    String urlStr = getUrlForSize(post, size);
                    if (urlStr != null && isSupportedFormat(urlStr)) {
                        urls.add(urlStr);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Gelbooru] API error: " + e.getMessage());
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
    public void reset() {}

    private String getUrlForSize(JsonObject post, Size size) {
        if (post == null) return null;
        switch (size) {
            case preview: return post.has("preview_url") ? post.get("preview_url").getAsString() : null;
            case sample: return post.has("sample_url") ? post.get("sample_url").getAsString() : null;
            case file: return post.has("file_url") ? post.get("file_url").getAsString() : null;
            default: return null;
        }
    }

    private boolean isSupportedFormat(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.endsWith(".jpg") || 
               lower.endsWith(".jpeg") ||
               lower.endsWith(".png") ||
               lower.endsWith(".gif");
    }
}