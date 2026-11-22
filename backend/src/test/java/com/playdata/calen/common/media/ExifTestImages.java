package com.playdata.calen.common.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;

/** In-memory JPEG fixtures with an EXIF Orientation tag; no image files are stored in the repository. */
public final class ExifTestImages {

    private ExifTestImages() {
    }

    /** Blue picture with a red rectangle in the raw top-left corner (about 1/4 x 0.3 of the picture). */
    public static byte[] markerJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0x33, 0x66, 0xcc));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, width / 4, Math.max(1, height * 3 / 10));
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    public static byte[] withOrientation(byte[] jpeg, int orientation, boolean littleEndian) {
        ByteBuffer tiff = ByteBuffer.allocate(26).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        tiff.put((byte) (littleEndian ? 'I' : 'M')).put((byte) (littleEndian ? 'I' : 'M'));
        tiff.putShort((short) 42).putInt(8);
        tiff.putShort((short) 1).putShort((short) 0x0112).putShort((short) 3).putInt(1);
        tiff.putShort((short) orientation).putShort((short) 0).putInt(0);

        ByteBuffer segment = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN);
        segment.putShort((short) 0xFFE1).putShort((short) (6 + 26 + 2));
        segment.put(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        segment.put(tiff.array());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(segment.array(), 0, segment.array().length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
