package anticope.esixtwoone.sources;

import com.google.gson.JsonObject;
import meteordevelopment.meteorclient.utils.network.Http;
import java.util.ArrayList;
import java.util.List;

public class NekosLife implements Source {
    private static final String BASE_URL = "https://nekos.life/api/v2/img/";

    @Override
    public List<String> getPageImageUrls(String filter, Size size, int page) {
        List<String> urls = new ArrayList<>();
        String url = randomImage(filter, size);
        if (url != null) urls.add(url);
        return urls;
    }

    @Override
    public List<String> getAllImageUrls(String filter, Size size) {
        return getPageImageUrls(filter, size, 1);
    }

    @Override
    public String randomImage(String filter, Size size) {
        try {
            JsonObject result = Http.get(BASE_URL + filter)
                .sendJson(JsonObject.class);
            return result != null && result.has("url") ? result.get("url").getAsString() : null;
        } catch (Exception e) {
            System.err.println("[NekosLife] API error:");
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void reset() {}
}