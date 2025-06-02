package anticope.esixtwoone.sources;

import net.minecraft.client.texture.NativeImage;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class GifDecoder {
    public static List<GifFrame> readGifFrames(InputStream inputStream) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(inputStream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) {
                throw new IOException("No GIF ImageReader found");
            }

            ImageReader reader = readers.next();
            reader.setInput(stream);

            int numFrames = reader.getNumImages(true);
            List<GifFrame> frames = new ArrayList<>(numFrames);

            for (int i = 0; i < numFrames; i++) {
                try {
                    BufferedImage frame = reader.read(i);
                    IIOMetadata metadata = reader.getImageMetadata(i);
                    int delay = getFrameDelay(metadata);
                    NativeImage nativeImage = bufferedImageToNativeImage(frame);
                    frames.add(new GifFrame(nativeImage, delay));
                } catch (Exception e) {
                    throw new IOException("Failed to read frame " + i, e);
                }
            }

            reader.dispose();
            return frames;
        }
    }

    private static int getFrameDelay(IIOMetadata metadata) {
        String metaFormat = metadata.getNativeMetadataFormatName();
        if (metaFormat == null) {
            return 100; // Default delay
        }

        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metaFormat);
        NodeList children = root.getElementsByTagName("GraphicControlExtension");
        if (children.getLength() == 0) {
            return 100;
        }

        Node child = children.item(0);
        Node delayNode = child.getAttributes().getNamedItem("delayTime");
        if (delayNode == null) {
            return 100;
        }

        try {
            int delay = Integer.parseInt(delayNode.getNodeValue()) * 10;
            return delay < 20 ? 100 : delay; // Enforce minimum delay
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private static NativeImage bufferedImageToNativeImage(BufferedImage bufferedImage) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "png", baos);
            return NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));
        }
    }

    public static class GifFrame {
        public final NativeImage image;
        public final int delay; // in milliseconds

        public GifFrame(NativeImage image, int delay) {
            this.image = image;
            this.delay = delay;
        }
    }
}