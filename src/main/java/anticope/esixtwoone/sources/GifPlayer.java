package anticope.esixtwoone.sources;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class GifPlayer {
    private static final boolean DEBUG = true;

    private final Identifier textureId;
    private final List<NativeImageBackedTexture> frameTextures = new ArrayList<>();
    private final List<Integer> delays = new ArrayList<>();
    private int currentFrame = 0;
    private long lastUpdateTime = 0L;
    private final MinecraftClient client;
    private final double aspectRatio;
    private boolean paused = false;
    private final int reductionFactor;
    private static final int MAX_FRAMES = 10000;
    private final String debugId;

    public GifPlayer(InputStream gifStream, Identifier textureId, int reductionFactor) throws Exception {
        this.textureId = textureId;
        this.client = MinecraftClient.getInstance();
        this.reductionFactor = Math.max(1, reductionFactor);
        this.debugId = "GifPlayer-" + System.currentTimeMillis();
        if (DEBUG) {
            System.out.printf("[%s] Creating GifPlayer (reduction=%d)%n", debugId, this.reductionFactor);
        }

        try (ImageInputStream imgStream = ImageIO.createImageInputStream(gifStream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) {
                throw new Exception("No GIF ImageReader found");
            }
            ImageReader reader = readers.next();
            reader.setInput(imgStream, false, false);

            int total = Math.min(reader.getNumImages(true), MAX_FRAMES);
            if (DEBUG) {
                System.out.printf("[%s] Frame count (capped): %d%n", debugId, total);
            }

            // load first frame to determine aspect ratio
            NativeImage firstImg = decodeReduced(reader, 0, this.reductionFactor);
            this.aspectRatio = (double) firstImg.getHeight() / firstImg.getWidth();
            frameTextures.add(new NativeImageBackedTexture(firstImg));
            delays.add(getFrameDelay(reader.getImageMetadata(0)));
            if (DEBUG) {
                System.out.printf("[%s] Loaded frame 0 (size=%dx%d, delay=%dms)%n", debugId,
                    firstImg.getWidth(), firstImg.getHeight(), delays.get(0));
            }

            // load remaining frames
            for (int i = 1; i < total; i++) {
                long freeMem = Runtime.getRuntime().freeMemory();
                if (freeMem < 50L * 1024L * 1024L) {
                    if (DEBUG) {
                        System.out.printf("[%s] Low memory (%.1fMB free) — stopping frame load at index %d%n", debugId,
                            freeMem / (1024f * 1024f), i);
                    }
                    break;
                }
                NativeImage img = decodeReduced(reader, i, this.reductionFactor);
                frameTextures.add(new NativeImageBackedTexture(img));
                int delay = getFrameDelay(reader.getImageMetadata(i));
                delays.add(delay);
                if (DEBUG) {
                    System.out.printf("[%s] Loaded frame %d (size=%dx%d, delay=%dms)%n", debugId,
                        i, img.getWidth(), img.getHeight(), delay);
                }
            }

            reader.dispose();
            if (DEBUG) {
                System.out.printf("[%s] Finished loading %d frames%n", debugId, frameTextures.size());
            }
        }

        this.lastUpdateTime = System.currentTimeMillis();
        // register initial texture
        client.getTextureManager().registerTexture(textureId, frameTextures.get(currentFrame));
    }

    private NativeImage decodeReduced(ImageReader reader, int index, int reduction) throws Exception {
        if (DEBUG) {
            System.out.printf("[%s] Decoding frame index %d (reduction=%d)%n", debugId, index, reduction);
        }
        BufferedImage original = reader.read(index);
        int newW = original.getWidth() / reduction;
        int newH = original.getHeight() / reduction;
        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        resized.getGraphics().drawImage(original.getScaledInstance(newW, newH, BufferedImage.SCALE_SMOOTH),
            0, 0, null);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(resized, "png", baos);
            byte[] data = baos.toByteArray();
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
                return NativeImage.read(bais);
            }
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            this.lastUpdateTime = System.currentTimeMillis();
        }
    }

    /** Call this each tick/render or appropriate update loop. */
    public void update() {
        if (paused || frameTextures.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        int delay = delays.get(currentFrame);
        if (now - lastUpdateTime >= delay) {
            int previous = currentFrame;
            currentFrame++;
            if (currentFrame >= frameTextures.size()) {
                currentFrame = 0;  // **loop back to first frame**
                if (DEBUG) {
                    System.out.printf("[%s] Looping back to start (frame %d -> %d)%n", debugId, previous, currentFrame);
                }
            }
            lastUpdateTime = now;

            // register the new texture frame
            client.getTextureManager().registerTexture(textureId, frameTextures.get(currentFrame));
            if (DEBUG) {
                System.out.printf("[%s] Switched to frame %d%n", debugId, currentFrame);
            }
        }
    }

    public void destroy() {
        if (client == null) return;
        if (DEBUG) {
            System.out.printf("[%s] Destroying GifPlayer and cleaning up textures%n", debugId);
        }

        client.execute(() -> {
            int destroyed = 0;
            for (NativeImageBackedTexture tex : frameTextures) {
                try {
                    if (tex != null && tex.getGlId() != 0) {
                        tex.close();
                        destroyed++;
                    }
                } catch (Exception e) {
                    System.err.printf("[%s] Error closing texture: %s%n", debugId, e.getMessage());
                }
            }
            frameTextures.clear();

            if (textureId != null && client.getTextureManager().getTexture(textureId) != null) {
                try {
                    client.getTextureManager().destroyTexture(textureId);
                    if (DEBUG) {
                        System.out.printf("[%s] Destroyed main texture %s%n", debugId, textureId);
                    }
                } catch (Exception e) {
                    System.err.printf("[%s] Error destroying main texture: %s%n", debugId, e.getMessage());
                }
            }
            if (DEBUG) {
                System.out.printf("[%s] Cleanup complete — destroyed %d frame textures%n", debugId, destroyed);
            }
        });
    }

    public double getAspectRatio() {
        return aspectRatio;
    }
}
