package anticope.esixtwoone.sources;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class GifPlayer {
    private static final boolean DEBUG = true;
    
    public final Identifier textureId;
    private final List<NativeImageBackedTexture> frames = new ArrayList<>();
    private final int[] delays;
    private int currentFrame;
    private long lastUpdateTime;
    private final MinecraftClient client;
    private final double aspectRatio;
    private boolean paused = false;
    private final int reductionFactor;
    private static final int MAX_FRAMES = 10000;
    private final String debugId;

    public GifPlayer(InputStream gifStream, Identifier textureId, int reductionFactor) throws Exception {
        this.textureId = textureId;
        this.client = MinecraftClient.getInstance();
        this.currentFrame = 0;
        this.lastUpdateTime = System.currentTimeMillis();
        this.reductionFactor = Math.max(1, reductionFactor);
        this.debugId = "GIF-" + System.currentTimeMillis();

        if (DEBUG) System.out.printf("[%s] Creating new GifPlayer (reduction: %d)%n", debugId, reductionFactor);

        try (ImageInputStream stream = ImageIO.createImageInputStream(gifStream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) {
                throw new Exception("No GIF reader available");
            }

            ImageReader reader = readers.next();
            reader.setInput(stream);

            int frameCount = Math.min(reader.getNumImages(true), MAX_FRAMES);
            this.delays = new int[frameCount];
            
            if (DEBUG) System.out.printf("[%s] Loading %d frames%n", debugId, frameCount);

            // Load first frame
            NativeImage firstFrame = readReducedFrame(reader, 0, this.reductionFactor);
            this.aspectRatio = (double)firstFrame.getHeight() / firstFrame.getWidth();
            frames.add(new NativeImageBackedTexture(firstFrame));
            delays[0] = getFrameDelay(reader.getImageMetadata(0));
            if (DEBUG) System.out.printf("[%s] Loaded frame 0 (%dx%d, delay: %dms)%n", 
                debugId, firstFrame.getWidth(), firstFrame.getHeight(), delays[0]);

            // Load remaining frames
            for (int i = 1; i < frameCount; i++) {
                long freeMem = Runtime.getRuntime().freeMemory();
                if (freeMem < 50 * 1024 * 1024) {
                    if (DEBUG) System.out.printf("[%s] Low memory (%.1fMB free), stopping frame loading%n", 
                        debugId, freeMem / (1024f * 1024f));
                    break;
                }
                
                NativeImage frame = readReducedFrame(reader, i, this.reductionFactor);
                frames.add(new NativeImageBackedTexture(frame));
                delays[i] = getFrameDelay(reader.getImageMetadata(i));
                if (DEBUG) System.out.printf("[%s] Loaded frame %d (%dx%d, delay: %dms)%n", 
                    debugId, i, frame.getWidth(), frame.getHeight(), delays[i]);
            }

            reader.dispose();
            if (DEBUG) System.out.printf("[%s] Successfully loaded %d/%d frames%n", 
                debugId, frames.size(), frameCount);
        } catch (Exception e) {
            if (DEBUG) System.err.printf("[%s] Error loading GIF: %s%n", debugId, e.getMessage());
            throw e;
        }
    }

    private NativeImage readReducedFrame(ImageReader reader, int index, int reduction) throws Exception {
        if (DEBUG) System.out.printf("[%s] Decoding frame %d (reduction: %d)%n", debugId, index, reduction);
        
        BufferedImage original = reader.read(index);
        int newWidth = original.getWidth() / reduction;
        int newHeight = original.getHeight() / reduction;
        
        BufferedImage reduced = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        reduced.getGraphics().drawImage(
            original.getScaledInstance(newWidth, newHeight, BufferedImage.SCALE_SMOOTH), 
            0, 0, null);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(reduced, "png", baos);
        byte[] imageData = baos.toByteArray();
        baos.close();
        
        ByteArrayInputStream bais = new ByteArrayInputStream(imageData);
        NativeImage nativeImage = NativeImage.read(bais);
        bais.close();
        
        return nativeImage;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            lastUpdateTime = System.currentTimeMillis();
        }
    }

    public void update() {
        if (paused || frames.isEmpty() || frames.size() <= 1) return;
        
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime >= delays[currentFrame]) {
            int prevFrame = currentFrame;
            currentFrame = (currentFrame + 1) % frames.size();
            lastUpdateTime = now;
            
            if (DEBUG && currentFrame == 0) {
                System.out.printf("[%s] Looping animation (frame %d -> 0)%n", debugId, prevFrame);
            }
            
            client.getTextureManager().registerTexture(textureId, frames.get(currentFrame));
        }
    }

    public void destroy() {
        if (client == null) return;
        
        if (DEBUG) System.out.printf("[%s] Destroying GifPlayer%n", debugId);
        
        client.execute(() -> {
            try {
                if (DEBUG) System.out.printf("[%s] Starting texture cleanup on render thread%n", debugId);
                
                int destroyedFrames = 0;
                for (NativeImageBackedTexture texture : frames) {
                    try {
                        if (texture != null && texture.getGlId() != 0) {
                            texture.close();
                            destroyedFrames++;
                        }
                    } catch (Exception e) {
                        System.err.printf("[%s] Error closing frame texture: %s%n", debugId, e.getMessage());
                    }
                }
                frames.clear();
                
                if (DEBUG) System.out.printf("[%s] Destroyed %d frame textures%n", debugId, destroyedFrames);
                
                if (textureId != null && client.getTextureManager() != null) {
                    try {
                        if (client.getTextureManager().getTexture(textureId) != null) {
                            client.getTextureManager().destroyTexture(textureId);
                            if (DEBUG) System.out.printf("[%s] Destroyed main texture%n", debugId);
                        }
                    } catch (Exception e) {
                        System.err.printf("[%s] Error destroying texture: %s%n", debugId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.printf("[%s] Error during destroy: %s%n", debugId, e.getMessage());
            }
        });
    }

    public double getAspectRatio() {
        return aspectRatio;
    }

    private int getFrameDelay(IIOMetadata metadata) {
        try {
            Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeName().equalsIgnoreCase("GraphicControlExtension")) {
                    Node delay = node.getAttributes().getNamedItem("delayTime");
                    if (delay != null) {
                        return Math.max(20, Integer.parseInt(delay.getNodeValue()) * 10);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GifPlayer] Error reading frame delay:");
            e.printStackTrace();
        }
        return 100;
    }
}