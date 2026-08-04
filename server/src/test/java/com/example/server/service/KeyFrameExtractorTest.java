package com.example.server.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyFrameExtractorTest {

    private final KeyFrameExtractor extractor = new KeyFrameExtractor();

    @Test
    void identicalImagesProduceSameHash() {
        BufferedImage a = solidGray(100);
        BufferedImage b = solidGray(100);
        assertEquals(extractor.differenceHash(a), extractor.differenceHash(b));
    }

    @Test
    void uniformImageProducesZeroHash() {
        assertEquals(0L, extractor.differenceHash(solidGray(128)));
    }

    @Test
    void differentPatternsProduceDivergentHash() {
        long checker = extractor.differenceHash(checkerboard());
        long solid = extractor.differenceHash(solidGray(128));
        assertTrue(Long.bitCount(checker ^ solid) > 5);
    }

    private static BufferedImage solidGray(int value) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY);
        int rgb = (value << 16) | (value << 8) | value;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static BufferedImage checkerboard() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, (x ^ y) % 2 == 0 ? 0 : 0xFFFFFF);
            }
        }
        return image;
    }
}
