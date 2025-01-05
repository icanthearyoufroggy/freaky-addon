package anticope.esixtwoone.sources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import meteordevelopment.meteorclient.utils.network.Http;

public class DanBooru extends Source {

    private final String domain;
    private final int lastPage;

    public DanBooru(String domain, int lastPage) {
        this.domain = domain;
        this.lastPage = lastPage;
    }

    @Override
    public void reset() {}

    @Override
    public String randomImage(String filter, Size size) {
        String query = String.format("%s/posts.json?tags=%s&page=%d&format=json&limit=10", domain, filter, random.nextInt(0, lastPage));
        JsonElement result = Http.get(query).sendJson(JsonElement.class);
        if (result == null) return null;

        if (result instanceof JsonArray array) {
            if (array.get(random.nextInt(0, Math.min(11, array.size()))) instanceof JsonObject post) {
                return switch (size) {
                    case preview -> post.get("preview_file_url").getAsString();
                    case sample -> post.get("large_file_url").getAsString();
                    case file -> post.get("file_url").getAsString();
                };
            }
        }
        return null;
    }
}
