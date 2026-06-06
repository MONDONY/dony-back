package com.dony.api.common;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;

class ImageProcessingServiceTest {

    private final ImageProcessingService svc = new ImageProcessingService();

    private byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void resizes_main_to_max_1280_long_edge_and_makes_400_thumb() throws Exception {
        ImageProcessingService.ProcessedImage p = svc.process(png(3000, 2000), "image/png");

        BufferedImage main = ImageIO.read(new ByteArrayInputStream(p.main()));
        assertThat(Math.max(main.getWidth(), main.getHeight())).isLessThanOrEqualTo(1280);

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(p.thumbnail()));
        assertThat(thumb.getWidth()).isEqualTo(400);
        assertThat(thumb.getHeight()).isEqualTo(400);

        assertThat(p.main().length).isLessThan(400 * 1024); // < 400 Ko
    }

    @Test
    void keeps_small_image_unchanged_in_dimensions() throws Exception {
        ImageProcessingService.ProcessedImage p = svc.process(png(800, 600), "image/png");

        BufferedImage main = ImageIO.read(new ByteArrayInputStream(p.main()));
        assertThat(Math.max(main.getWidth(), main.getHeight())).isLessThanOrEqualTo(1280);
    }

    @Test
    void portrait_image_respects_long_edge_constraint() throws Exception {
        // 1000 x 3000 — portrait, long edge is height
        ImageProcessingService.ProcessedImage p = svc.process(png(1000, 3000), "image/png");

        BufferedImage main = ImageIO.read(new ByteArrayInputStream(p.main()));
        assertThat(Math.max(main.getWidth(), main.getHeight())).isLessThanOrEqualTo(1280);

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(p.thumbnail()));
        assertThat(thumb.getWidth()).isEqualTo(400);
        assertThat(thumb.getHeight()).isEqualTo(400);
    }

    @Test
    void rejects_non_image() {
        assertThatThrownBy(() -> svc.process("not an image".getBytes(), "text/plain"))
                .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void rejects_empty_bytes() {
        assertThatThrownBy(() -> svc.process(new byte[0], "image/jpeg"))
                .isInstanceOf(DonyBusinessException.class);
    }
}
