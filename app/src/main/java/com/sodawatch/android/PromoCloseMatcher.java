package com.sodawatch.android;

import android.graphics.Rect;

import java.util.function.IntBinaryOperator;

final class PromoCloseMatcher {
    private static final int[] DIRECTIONS = {-1, 1};
    private static final int[] CROSS_STEPS = {18, 27, 35};

    static Rect find(int width, int height, Rect download, IntBinaryOperator pixel) {
        int scale = Math.max(2, width / 320);
        int bestScore = 0;
        Rect match = null;
        int radiusMin = width * 27 / 1_000;
        int radiusMax = width * 50 / 1_000;
        int center = download.centerX();
        int startY = download.bottom + height * 31 / 1_000;
        int endY = Math.min(download.bottom + height * 13 / 100, height - radiusMax - 3);
        for (int y = Math.max(startY, radiusMax + 3); y <= endY; y += scale) {
            for (int x = center - width / 19; x <= center + width / 19; x += scale) {
                if (x <= radiusMax + 3 || x >= width - radiusMax - 3 || peak(pixel, x, y, 2) < 90) continue;
                for (int radius = radiusMin; radius <= radiusMax; radius += scale) {
                    int score = evaluate(pixel, x, y, radius);
                    if (score <= bestScore) continue;
                    bestScore = score;
                    int half = Math.max(3, radius / 3);
                    match = new Rect(x - half, y - half, x + half, y + half);
                }
            }
        }
        return match;
    }

    private static int evaluate(IntBinaryOperator pixel, int x, int y, int radius) {
        int gap = radius * 49 / 100;
        int background = (gray(pixel.applyAsInt(x - gap, y)) + gray(pixel.applyAsInt(x + gap, y))
                + gray(pixel.applyAsInt(x, y - gap)) + gray(pixel.applyAsInt(x, y + gap))) / 4;
        int cross = 0;
        int tolerance = Math.max(2, radius / 17);
        for (int horizontal : DIRECTIONS) {
            for (int vertical : DIRECTIONS) {
                for (int step : CROSS_STEPS) {
                    int distance = radius * step / 100;
                    if (peak(pixel, x + horizontal * distance, y + vertical * distance, tolerance) >= Math.max(90, background + 48)) cross++;
                }
            }
        }
        if (cross < 9) return 0;

        int ring = 0;
        int diagonal = radius * 707 / 1_000;
        int ringThreshold = Math.max(42, background + 25);
        for (int direction : DIRECTIONS) {
            if (peak(pixel, x + direction * radius, y, tolerance) >= ringThreshold) ring++;
            if (peak(pixel, x, y + direction * radius, tolerance) >= ringThreshold) ring++;
            for (int other : DIRECTIONS) {
                if (peak(pixel, x + direction * diagonal, y + other * diagonal, tolerance) >= ringThreshold) ring++;
            }
        }
        if (ring < 6) return 0;
        return cross * 3 + ring;
    }

    private static int peak(IntBinaryOperator pixel, int x, int y, int tolerance) {
        int brightest = gray(pixel.applyAsInt(x, y));
        for (int vertical : DIRECTIONS) {
            for (int horizontal : DIRECTIONS) {
                brightest = Math.max(brightest, gray(pixel.applyAsInt(x + horizontal * tolerance, y + vertical * tolerance)));
            }
        }
        return brightest;
    }

    private static int gray(int pixel) {
        int red = (pixel >> 16) & 255;
        int green = (pixel >> 8) & 255;
        int blue = pixel & 255;
        if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 36) return 0;
        return (red + green * 2 + blue) / 4;
    }
}
