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
        if (maxPage < 1) maxPage = 1;
        int pageNum = random.nextInt(1, maxPage + 1);
        JsonObject result = Http.get(domain + "/posts.json?limit=320&tags="+filter+"&page="+ pageNum).sendJson(JsonObject.class);
        if (result.get("posts") instanceof JsonArray array) {
            if(array.size() <= 0) {
                maxPage = pageNum - 1;
                return null;
            }
            for (int tries = 0; tries < array.size(); tries++) {
                JsonObject post = array.get(random.nextInt(array.size())).getAsJsonObject();
            
                if (!post.has(size.toString())) continue;
            
                JsonObject sizeObj = post.get(size.toString()).getAsJsonObject();
            
                if (!sizeObj.has("url")) continue;
            
                String url = sizeObj.get("url").getAsString();
            
                if (url.endsWith(".webm")) continue;
            
                return url;
            }
            return null; // no valid image found
            
        }
        return null;
    }
}
