package ru.mcs.research.bench;

import java.util.Arrays;
import java.util.Random;

public final class Attacks {

    private Attacks() {
    }

    public static double[] gain(double[] x, double factor) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = x[i] * factor;
        }
        return out;
    }

    /** Гауссов шум с заданным SNR относительно RMS сигнала. */
    public static double[] noise(double[] x, double snrDb, long seed) {
        double rms = Metrics.rms(x);
        double noiseRms = rms / Math.pow(10.0, snrDb / 20.0);

        Random rnd = new Random(seed);
        double[] out = new double[x.length];

        for (int i = 0; i < x.length; i++) {
            out[i] = x[i] + rnd.nextGaussian() * noiseRms;
        }
        return out;
    }

    /** Потеря начала сигнала: проверяет чувствительность к синхронизации. */
    public static double[] crop(double[] x, int count) {
        int from = Math.min(count, x.length);
        return Arrays.copyOfRange(x, from, x.length);
    }

    /** Простой каузальный ФНЧ — скользящее среднее. */
    public static double[] lowpass(double[] x, int window) {
        if (window < 1) {
            throw new IllegalArgumentException("window >= 1");
        }

        double[] out = new double[x.length];
        double sum = 0.0;

        for (int i = 0; i < x.length; i++) {
            sum += x[i];
            if (i >= window) {
                sum -= x[i - window];
            }
            out[i] = sum / Math.min(i + 1, window);
        }

        return out;
    }

    /** Сдвиг начала на произвольное число сэмплов без потери данных. */
    public static double[] padStart(double[] x, int count) {
        double[] out = new double[x.length + count];
        System.arraycopy(x, 0, out, count, x.length);
        return out;
    }
}