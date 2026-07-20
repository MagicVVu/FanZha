package com.magicvvu.fanzha.backend.service.fraud.captcha;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

public class Tess4jCaptchaSolver implements CaptchaSolver {

    private final Tesseract tesseract;

    public Tess4jCaptchaSolver(String tessdataPath) {
        Tesseract t = new Tesseract();
        if (tessdataPath != null && !tessdataPath.trim().isEmpty()) {
            t.setDatapath(tessdataPath);
        }
        t.setLanguage("eng");
        this.tesseract = t;
    }

    @Override
    public String solve(byte[] imageBytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            return "";
        }
        try {
            return tesseract.doOCR(image).replaceAll("\\s+", "").trim();
        } catch (TesseractException e) {
            return "";
        }
    }
}
