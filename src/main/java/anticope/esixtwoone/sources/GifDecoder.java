package anticope.esixtwoone.sources;

import net.minecraft.client.texture.NativeImage;

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
        try (ImageInputStream imgStream = ImageIO.createImageInputStream(inputStream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) {
                throw new IOException("No GIF ImageReader found");
            }
            ImageReader reader = readers.next();
            reader.setInput(imgStream, false, false);

            int frameCount = reader.getNumImages(true);
            List<GifFrame> frames = new ArrayList<>(frameCount);

            for (int i = 0; i < frameCount; i++) {
                try {
                    BufferedImage buf = reader.read(i);
                    IIOMetadata metadata = reader.getImageMetadata(i);
                    int delay = getFrameDelay(metadata);

                    NativeImage nativeImg = bufferedImageToNativeImage(buf);
                    frames.add(new GifFrame(nativeImg, delay));
                } catch (Exception e) {
                    throw new IOException("Failed to read GIF frame index " + i, e);
                }
            }

            reader.dispose();
            return frames;
        }
    }

    private static int getFrameDelay(IIOMetadata metadata) {
        String format = metadata.getNativeMetadataFormatName();
        if (format == null) {
            return 100;
        }
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        // look for GraphicControlExtension
        IIOMetadataNode gceNode = null;
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i) instanceof IIOMetadataNode) {
                IIOMetadataNode node = (IIOMetadataNode) root.item(i);
                if ("GraphicControlExtension".equals(node.getNodeName())) {
                    gceNode = node;
                    break;
                }
            }
        }
        if (gceNode == null) {
            return 100;
        }
        String delayValue = gceNode.getAttribute("delayTime");
        if (delayValue == null || delayValue.isEmpty()) {
            return 100;
        }
        try {
            int hundredths = Integer.parseInt(delayValue);
            int ms = hundredths * 10;
            return ms < 20 ? 100 : ms;
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private static NativeImage bufferedImageToNativeImage(BufferedImage buf) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(buf, "png", baos);
            byte[] bytes = baos.toByteArray();
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
                return NativeImage.read(bais);
            }
        }
    }

    public static class GifFrame {
        public final NativeImage image;
        public final int delay; // milliseconds

        public GifFrame(NativeImage image, int delay) {
            this.image = image;
            this.delay = delay;
        }
    }
}
