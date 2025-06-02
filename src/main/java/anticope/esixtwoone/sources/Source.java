package anticope.esixtwoone.sources;

import java.util.List;

public interface Source {
    enum Size { preview, sample, file }
    enum SourceType { e621, e926, danbooru, gelbooru, nekoslife }
    
    List<String> getPageImageUrls(String filter, Size size, int page);
    List<String> getAllImageUrls(String filter, Size size);
    String randomImage(String filter, Size size);
    void reset();

    static Source getSource(SourceType type) {
        switch (type) {
            case e621: return new ESixTwoOne("https://e621.net");
            case e926: return new ESixTwoOne("https://e926.net");
            case danbooru: return new Danbooru("https://danbooru.donmai.us");
            case gelbooru: return new Gelbooru("https://gelbooru.com");
            case nekoslife: return new NekosLife();
            default: throw new IllegalArgumentException("Unknown source type");
        }
    }
}