package anticope.esixtwoone.sources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import meteordevelopment.meteorclient.utils.network.Http;

public class ESixTwoOne extends Source {

    private final String domain;

    public ESixTwoOne(String domain) {
        this.domain = domain;
    }
    private int maxPage = 30;

    @Override
    public void reset() {
        maxPage = 30;
    }

    @Override
    public String randomImage(String filter, Size size) {
        int pageNum = random.nextInt(1, maxPage);
        JsonObject result = Http.get(domain + "/posts.json?limit=320&tags="+filter+"&page="+ pageNum).sendJson(JsonObject.class);
        if (result.get("posts") instanceof JsonArray array) {
            if(array.size() <= 0) {
                maxPage = pageNum - 1;
                return null;
            }
            if (array.get(random.nextInt(array.size())) instanceof JsonObject post) {
                return post.get(size.toString()).getAsJsonObject().get("url").getAsString();
            }
        }
        return null;
    }
}
