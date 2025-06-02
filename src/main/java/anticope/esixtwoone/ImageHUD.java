package anticope.esixtwoone;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.*;
import meteordevelopment.meteorclient.utils.network.Http;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.*;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import anticope.esixtwoone.sources.*;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;

public class ImageHUD extends HudElement {
    public static final HudElementInfo<ImageHUD> INFO = new HudElementInfo<>(Hud.GROUP, "e621-image", "Displays images from various sources", ImageHUD::new);

    // Texture and rendering
    private static final Identifier TEXID = Identifier.of("meteor-client", "e621-image");
    private NativeImageBackedTexture staticTexture;
    private GifPlayer gifPlayer;
    private double aspectRatio = 1.0;
    
    // Loading state
    private boolean isLoading;
    private String currentUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    // Image management
    private final List<String> cachedImageUrls = new ArrayList<>();
    private int currentImageIndex = 0;
    private long lastCycleTime;
    private boolean needsNewFetch = true;
    private String lastTags = "";
    
    // Pagination
    private int currentPage = 1;
    private boolean hasMorePages = true;
    private final Object fetchLock = new Object();
    
    // GIF preloading
    private final Queue<CompletableFuture<GifPlayer>> preloadQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, GifPlayer> preloadedGifs = new ConcurrentHashMap<>();
    private volatile int activePreloads = 0;
    private final Object preloadLock = new Object();

    // System monitoring
    private volatile double lastVramUsage = 0;
    private volatile double lastMemoryUsage = 0;
    private long lastSystemCheckTime = 0;
    
    // Pause state
    private boolean paused = false;
    private boolean wasPaused = false;
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Double> width = sgGeneral.add(new DoubleSetting.Builder()
        .name("width")
        .description("Image width in pixels")
        .defaultValue(328.0)
        .min(50.0)
        .sliderRange(50.0, 1000.0)
        .onChanged(val -> updateSize())
        .build()
    );
    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("Image height (0 for auto-scale)")
        .defaultValue(0.0)
        .min(0.0)
        .sliderRange(0.0, 1000.0)
        .onChanged(val -> updateSize())
        .build()
    );
    private final Setting<String> tags = sgGeneral.add(new StringSetting.Builder()
        .name("tags")
        .description("Search tags (space separated)")
        .defaultValue("")
        .onChanged(val -> {
            if (!val.equals(lastTags)) {
                lastTags = val;
                forceReload();
            }
        })
        .build()
    );
    private final Setting<Source.Size> size = sgGeneral.add(new EnumSetting.Builder<Source.Size>()
        .name("size")
        .description("Image size quality")
        .defaultValue(Source.Size.file)
        .onChanged(val -> forceReload())
        .build()
    );
    private final Setting<Source.SourceType> source = sgGeneral.add(new EnumSetting.Builder<Source.SourceType>()
        .name("source")
        .description("Image source")
        .defaultValue(Source.SourceType.e621)
        .onChanged(val -> forceReload())
        .build()
    );
    private final Setting<Double> cycleTime = sgGeneral.add(new DoubleSetting.Builder()
        .name("cycle-time")
        .description("Seconds between image changes (0 to disable)")
        .defaultValue(3.0)
        .min(0)
        .sliderRange(0, 60)
        .build()
    );
    private final Setting<Boolean> allowGifs = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-gifs")
        .description("Enable animated GIF playback")
        .defaultValue(true)
        .onChanged(val -> forceReload())
        .build()
    );
    private final Setting<Boolean> showDebug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-info")
        .description("Show loading debug information")
        .defaultValue(false)
        .build()
    );
    private final Setting<Double> maxVramUsage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-vram-usage")
        .description("Maximum VRAM usage percentage (0 to disable)")
        .defaultValue(70.0)
        .min(0)
        .max(100)
        .sliderRange(0, 100)
        .build()
    );
    private final Setting<Boolean> adaptiveQuality = sgGeneral.add(new BoolSetting.Builder()
        .name("adaptive-quality")
        .description("Automatically reduce quality when VRAM is constrained")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> maxConcurrentGifs = sgGeneral.add(new IntSetting.Builder()
        .name("max-concurrent-gifs")
        .description("Maximum GIFs to decode at once (reduce if you get crashes)")
        .defaultValue(2)
        .min(1)
        .max(10)
        .sliderRange(1, 5)
        .build()
    );
    private final Setting<Boolean> prioritizeStatic = sgGeneral.add(new BoolSetting.Builder()
        .name("prioritize-static")
        .description("Prioritize static images over GIFs when resource constrained")
        .defaultValue(true)
        .build()
    );

    public ImageHUD() {
        super(INFO);
        updateSize();
    }

    private void debug(String message) {
        if (showDebug.get()) {
            System.out.printf("[ImageHUD] %s%n", message);
        }
    }

    private void debugPreloadState() {
        if (!showDebug.get()) return;
        
        synchronized (preloadLock) {
            System.out.printf("[Preload] State: %d active, %d queued, %d cached%n",
                activePreloads, preloadQueue.size(), preloadedGifs.size());
            System.out.printf("[Preload] Current cache: %s%n", preloadedGifs.keySet());
        }
    }

    @Override
    public void render(HudRenderer renderer) {
        boolean shouldPause = !showDebug.get() && shouldPause();
        
        if (shouldPause != wasPaused) {
            wasPaused = shouldPause;
            if (shouldPause) {
                debug("Pausing HUD - cleaning up resources");
                cleanup();
            } else {
                debug("Resuming HUD - initializing preloading");
                initializePreloading();
                loadImage();
            }
        }
        
        if (shouldPause && !showDebug.get()) {
            debug("Currently paused - skipping render");
            return;
        }

        renderer.quad(x - 1, y - 1, getWidth() + 2, getHeight() + 2, new Color(0, 0, 0, 100));

        if (showDebug.get()) {
            String debugText = String.format(
                "%s\nURL: %s\nTags: %s\nCache: %d/%d\nPreloads: %d/%d\nPage: %d",
                isLoading ? "Loading..." : "Ready",
                currentUrl != null ? currentUrl : "None",
                tags.get(),
                currentImageIndex,
                cachedImageUrls.size(),
                preloadedGifs.size(),
                maxConcurrentGifs.get(),
                currentPage
            );
            renderer.text(debugText, x, y, Color.WHITE, true);
        }

        if (isLoading && !showDebug.get()) {
            debug("Currently loading - skipping render");
            return;
        }

        if (gifPlayer != null && !shouldPause) {
            gifPlayer.update();
        }

        renderCurrentImage(renderer);

        if (!shouldPause && !isLoading && cycleTime.get() > 0 && 
            System.currentTimeMillis() - lastCycleTime > cycleTime.get() * 1000L) {
            debug("Cycle time elapsed - loading next image");
            loadNextImage();
        }
        
        if (System.currentTimeMillis() - lastSystemCheckTime > 5000) {
            debug("Performing system check");
            updateSystemStats();
        }
    }

    private void renderCurrentImage(HudRenderer renderer) {
        try {
            if (gifPlayer != null) {
                renderer.texture(
                    gifPlayer.textureId,
                    x, y,
                    width.get(),
                    height.get() > 0 ? height.get() : width.get() * aspectRatio,
                    Color.WHITE
                );
            } else if (staticTexture != null) {
                renderer.texture(
                    TEXID,
                    x, y,
                    width.get(),
                    height.get() > 0 ? height.get() : width.get() * aspectRatio,
                    Color.WHITE
                );
            }
        } catch (Exception e) {
            debug("Render error: " + e.getMessage());
            if (showDebug.get()) {
                renderer.text("Render Error", x, y, Color.RED, true);
            }
            cleanup();
            loadImage();
        }
    }

    private void initializePreloading() {
        if (cachedImageUrls.isEmpty()) {
            debug("No cached images - skipping preload init");
            return;
        }

        synchronized (preloadLock) {
            debug("Initializing preloading with " + cachedImageUrls.size() + " cached URLs");
            
            preloadedGifs.values().forEach(gif -> {
                debug("Cleaning up existing preloaded GIF");
                gif.destroy();
            });
            preloadedGifs.clear();
            preloadQueue.clear();
            activePreloads = 0;

            int preloadCount = Math.min(maxConcurrentGifs.get(), cachedImageUrls.size());
            debug("Starting " + preloadCount + " initial preloads");
            
            for (int i = 0; i < preloadCount; i++) {
                int index = (currentImageIndex + i) % cachedImageUrls.size();
                String url = cachedImageUrls.get(index);
                if (allowGifs.get() && url.toLowerCase().endsWith(".gif") && !preloadedGifs.containsKey(url)) {
                    debug("Scheduling preload for " + url);
                    scheduleGifPreload(url);
                }
            }
        }
    }

    private void scheduleGifPreload(String url) {
        if (activePreloads >= maxConcurrentGifs.get()) {
            debug("Preload queue full, skipping " + url);
            return;
        }

        debug("Starting preload for " + url);
        debugPreloadState();
        
        activePreloads++;
        CompletableFuture<GifPlayer> future = CompletableFuture.supplyAsync(() -> {
            try {
                debug("Downloading GIF data for " + url);
                InputStream stream = Http.get(url).sendInputStream();
                if (stream == null) {
                    debug("Failed to download " + url);
                    return null;
                }

                byte[] gifData = stream.readAllBytes();
                debug(String.format("Downloaded %s (%.1fKB)", url, gifData.length / 1024f));
                
                return mc.submit(() -> {
                    try {
                        int reduction = calculateSafeReductionFactor(url, gifData.length);
                        debug(String.format("Creating GifPlayer for %s (reduction: %d)", url, reduction));
                        
                        GifPlayer player = new GifPlayer(
                            new ByteArrayInputStream(gifData),
                            Identifier.of("meteor-client", "e621-preload-" + System.currentTimeMillis()),
                            reduction
                        );
                        
                        synchronized (preloadLock) {
                            preloadedGifs.put(url, player);
                            debug("Successfully preloaded " + url);
                            debugPreloadState();
                        }
                        return player;
                    } catch (Exception e) {
                        debug("Error creating GifPlayer for " + url + ": " + e.getMessage());
                        return null;
                    }
                }).get();
            } catch (Exception e) {
                debug("Error during preload of " + url + ": " + e.getMessage());
                return null;
            } finally {
                synchronized (preloadLock) {
                    activePreloads--;
                    debug("Completed preload for " + url + " (" + activePreloads + " active remaining)");
                    
                    // Schedule next preload if queue isn't empty
                    if (!preloadQueue.isEmpty()) {
                        String nextUrl = findNextGifToPreload();
                        if (nextUrl != null) {
                            debug("Scheduling next preload for " + nextUrl);
                            scheduleGifPreload(nextUrl);
                        }
                    }
                }
            }
        }, executor);

        preloadQueue.add(future);
    }

    private String findNextGifToPreload() {
        synchronized (preloadLock) {
            for (int i = 0; i < cachedImageUrls.size(); i++) {
                int index = (currentImageIndex + i) % cachedImageUrls.size();
                String url = cachedImageUrls.get(index);
                if (allowGifs.get() && url.toLowerCase().endsWith(".gif") && !preloadedGifs.containsKey(url)) {
                    return url;
                }
            }
            return null;
        }
    }

    private void loadImage() {
        if (isLoading || paused) {
            debug("Skipping load - " + (isLoading ? "already loading" : "paused"));
            return;
        }
        
        if (needsNewFetch || cachedImageUrls.isEmpty()) {
            debug("Need new fetch - " + (needsNewFetch ? "forced" : "empty cache"));
            fetchAllPosts();
        } else {
            debug("Loading next image from cache");
            loadNextImage();
        }
    }

    private void fetchAllPosts() {
        if (isLoading || !hasMorePages) {
            debug("Skipping fetch - " + (isLoading ? "already loading" : "no more pages"));
            return;
        }
        
        debug("Fetching posts for page " + currentPage);
        isLoading = true;
        executor.execute(() -> {
            synchronized (fetchLock) {
                try {
                    String searchTags = tags.get().trim();
                    if (searchTags.isEmpty()) {
                        debug("No tags specified - skipping fetch");
                        isLoading = false;
                        return;
                    }

                    Source source = Source.getSource(this.source.get());
                    debug("Fetching from source: " + source.getClass().getSimpleName());
                    List<String> newUrls = source.getPageImageUrls(searchTags, size.get(), currentPage);
                    
                    if (newUrls.isEmpty()) {
                        debug("No more pages available");
                        hasMorePages = false;
                    } else {
                        debug("Fetched " + newUrls.size() + " new URLs");
                        cachedImageUrls.addAll(newUrls);
                        currentPage++;
                        
                        if (currentPage == 2) {
                            debug("First page fetched - loading initial image");
                            loadNextImage();
                        }
                    }
                } catch (Exception e) {
                    debug("Error fetching posts: " + e.getMessage());
                } finally {
                    isLoading = false;
                }
            }
        });
    }

    private void loadNextImage() {
        if (paused) {
            debug("Paused, skipping image load");
            needsNewFetch = true;
            return;
        }

        if (cachedImageUrls.size() - currentImageIndex < 50 && hasMorePages) {
            debug("Low cache (" + (cachedImageUrls.size() - currentImageIndex) + "), fetching more posts");
            fetchAllPosts();
        }

        if (currentImageIndex >= cachedImageUrls.size()) {
            if (hasMorePages) {
                debug("Reached end of cache, fetching more posts");
                fetchAllPosts();
            } else {
                debug("Resetting to start of cache");
                currentImageIndex = 0;
            }
            return;
        }

        currentUrl = cachedImageUrls.get(currentImageIndex);
        currentImageIndex++;
        isLoading = true;
        
        debug("Loading image #" + currentImageIndex + ": " + currentUrl);

        GifPlayer preloaded = preloadedGifs.remove(currentUrl);
        if (preloaded != null) {
            debug("Using preloaded GIF for " + currentUrl);
            MeteorClient.mc.executeTask(() -> {
                cleanup();
                gifPlayer = preloaded;
                aspectRatio = gifPlayer.getAspectRatio();
                updateSize();
                lastCycleTime = System.currentTimeMillis();
                isLoading = false;
                scheduleNextPreload();
            });
            return;
        }

        boolean isGif = allowGifs.get() && currentUrl.toLowerCase().endsWith(".gif");
        if (isGif && prioritizeStatic.get() && isSystemConstrained()) {
            debug("System constrained, loading GIF as static: " + currentUrl);
            loadAsStaticImage(currentUrl);
        } else if (isGif) {
            debug("Loading as GIF: " + currentUrl);
            loadAsGifImage(currentUrl);
        } else {
            debug("Loading as static image: " + currentUrl);
            loadAsStaticImage(currentUrl);
        }
    }

    private void scheduleNextPreload() {
        synchronized (preloadLock) {
            if (preloadedGifs.size() >= maxConcurrentGifs.get()) {
                debug("Preload cache full - skipping next preload");
                return;
            }

            for (int i = 0; i < cachedImageUrls.size(); i++) {
                int index = (currentImageIndex + i) % cachedImageUrls.size();
                String url = cachedImageUrls.get(index);
                if (allowGifs.get() && url.toLowerCase().endsWith(".gif") && !preloadedGifs.containsKey(url)) {
                    debug("Scheduling next preload for " + url);
                    scheduleGifPreload(url);
                    break;
                }
            }
        }
    }

    private void loadAsGifImage(String url) {
        debug("Starting GIF load for " + url);
        executor.execute(() -> {
            InputStream stream = null;
            try {
                cleanup();
                Http.Request request = Http.get(url);
                stream = request.sendInputStream();
                if (stream == null) {
                    debug("Failed to get stream for " + url);
                    tryFallbackImageLoading(url);
                    return;
                }

                byte[] gifData = stream.readAllBytes();
                if (gifData == null || gifData.length == 0) {
                    debug("Empty GIF data for " + url);
                    tryFallbackImageLoading(url);
                    return;
                }

                debug("Successfully downloaded GIF (" + gifData.length + " bytes)");
                MeteorClient.mc.executeTask(() -> {
                    try {
                        int reduction = calculateSafeReductionFactor(url, gifData.length);
                        debug("Creating GifPlayer with reduction " + reduction);
                        gifPlayer = new GifPlayer(
                            new ByteArrayInputStream(gifData),
                            TEXID,
                            reduction
                        );
                        aspectRatio = gifPlayer.getAspectRatio();
                        updateSize();
                        lastCycleTime = System.currentTimeMillis();
                        debug("GIF loaded successfully");
                    } catch (Exception e) {
                        debug("Error creating GifPlayer: " + e.getMessage());
                        tryFallbackImageLoading(url);
                    } finally {
                        isLoading = false;
                    }
                });
            } catch (Exception e) {
                debug("Error loading GIF: " + e.getMessage());
                tryFallbackImageLoading(url);
            } finally {
                if (stream != null) {
                    try { stream.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private void loadAsStaticImage(String url) {
        debug("Loading static image from " + url);
        executor.execute(() -> {
            try {
                cleanup();
                try (InputStream stream = Http.get(url).sendInputStream()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(ImageIO.read(stream), "png", baos);
                    NativeImage image = NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));
                    aspectRatio = (double) image.getHeight() / image.getWidth();
                    debug("Static image loaded (" + image.getWidth() + "x" + image.getHeight() + ")");
                    MeteorClient.mc.executeTask(() -> {
                        try {
                            staticTexture = new NativeImageBackedTexture(image);
                            MeteorClient.mc.getTextureManager().registerTexture(TEXID, staticTexture);
                            updateSize();
                            lastCycleTime = System.currentTimeMillis();
                            debug("Static texture registered");
                        } finally {
                            isLoading = false;
                        }
                    });
                }
            } catch (Exception e) {
                debug("Error loading static image: " + e.getMessage());
                isLoading = false;
            }
        });
    }

    private void tryFallbackImageLoading(String url) {
        debug("Attempting fallback loading for " + url);
        if (url.toLowerCase().endsWith(".gif") && !url.equals(currentUrl)) {
            loadAsStaticImage(url);
        } else {
            isLoading = false;
        }
    }

    private boolean shouldPause() {
        if (showDebug.get()) return false;
        if (mc.world == null || !mc.isWindowFocused()) return true;
        if (mc.currentScreen == null) return false;
        return !(mc.currentScreen instanceof InventoryScreen ||
                mc.currentScreen instanceof HandledScreen<?> ||
                mc.currentScreen instanceof CreativeInventoryScreen);
    }

    private void updateSystemStats() {
        try {
            long totalVram = GL11.glGetInteger(0x9047);
            long usedVram = GL11.glGetInteger(0x9049);
            lastVramUsage = 100 - ((usedVram * 100.0) / totalVram);
            
            Runtime runtime = Runtime.getRuntime();
            lastMemoryUsage = (runtime.totalMemory() - runtime.freeMemory()) * 100.0 / runtime.maxMemory();
            
            lastSystemCheckTime = System.currentTimeMillis();
            debug(String.format("System stats - VRAM: %.1f%%, RAM: %.1f%%", lastVramUsage, lastMemoryUsage));
        } catch (Exception ignored) {}
    }

    private boolean isSystemConstrained() {
        if (!MinecraftClient.getInstance().isOnThread()) {
            return false;
        }

        try {
            long totalVram = GL11.glGetInteger(0x9047);
            long usedVram = GL11.glGetInteger(0x9049);
            lastVramUsage = 100 - ((usedVram * 100.0) / totalVram);
            
            Runtime runtime = Runtime.getRuntime();
            lastMemoryUsage = (runtime.totalMemory() - runtime.freeMemory()) * 100.0 / runtime.maxMemory();
            
            boolean overVram = maxVramUsage.get() > 0 && lastVramUsage > maxVramUsage.get();
            boolean overRam = lastMemoryUsage > 85;
            
            debug(String.format("System check - VRAM: %.1f%% (%s), RAM: %.1f%% (%s)",
                lastVramUsage, overVram ? "OVER" : "OK",
                lastMemoryUsage, overRam ? "OVER" : "OK"));
            
            return adaptiveQuality.get() && (overVram || overRam);
        } catch (Exception e) {
            debug("Error checking system constraints: " + e.getMessage());
            return false;
        }
    }

    private int calculateSafeReductionFactor(String url, long fileSize) {
        if (MinecraftClient.getInstance().isOnThread()) {
            if (isSystemConstrained()) {
                debug("System constrained - using higher reduction (4)");
                return 4;
            }
            if (fileSize > 5 * 1024 * 1024) {
                debug("Large file (" + (fileSize/1024/1024) + "MB) - using medium reduction (2)");
                return 2;
            }
            debug("Using default reduction (1)");
            return 1;
        } else {
            return fileSize > 5 * 1024 * 1024 ? 2 : 1;
        }
    }

    private void cleanup() {
        debug("Starting cleanup");
        try {
            mc.execute(() -> {
                try {
                    debug("Running cleanup on render thread");
                    
                    if (staticTexture != null) {
                        try {
                            debug("Cleaning up static texture");
                            if (MeteorClient.mc.getTextureManager().getTexture(TEXID) != null) {
                                MeteorClient.mc.getTextureManager().destroyTexture(TEXID);
                                debug("Destroyed static texture");
                            }
                            staticTexture.close();
                        } catch (Exception e) {
                            debug("Error closing static texture: " + e.getMessage());
                        } finally {
                            staticTexture = null;
                        }
                    }

                    if (gifPlayer != null) {
                        try {
                            debug("Cleaning up GIF player");
                            gifPlayer.destroy();
                        } catch (Exception e) {
                            debug("Error destroying GIF player: " + e.getMessage());
                        } finally {
                            gifPlayer = null;
                        }
                    }

                    synchronized (preloadLock) {
                        debug("Cleaning up " + preloadedGifs.size() + " preloaded GIFs");
                        int destroyed = 0;
                        Iterator<Map.Entry<String, GifPlayer>> it = preloadedGifs.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<String, GifPlayer> entry = it.next();
                            try {
                                if (entry.getValue() != null) {
                                    entry.getValue().destroy();
                                    destroyed++;
                                }
                            } catch (Exception e) {
                                debug("Error destroying preloaded GIF: " + e.getMessage());
                            }
                            it.remove();
                        }
                        debug("Destroyed " + destroyed + " preloaded GIFs");
                        preloadQueue.clear();
                        activePreloads = 0;
                    }
                } catch (Exception e) {
                    debug("Error during cleanup: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            debug("Error scheduling cleanup: " + e.getMessage());
        }
    }

    private void forceReload() {
        synchronized (fetchLock) {
            debug("Force reload triggered");
            cleanup();
            cachedImageUrls.clear();
            currentPage = 1;
            currentImageIndex = 0;
            hasMorePages = true;
            needsNewFetch = true;
            
            if (!paused) {
                initializePreloading();
                fetchAllPosts();
            }
        }
    }

    private void updateSize() {
        setSize(width.get(), height.get() > 0 ? height.get() : width.get() * aspectRatio);
    }

    @Override
    public void remove() {
        debug("Removing HUD - cleaning up resources");
        executor.shutdownNow();
        cleanup();
        super.remove();
    }
}