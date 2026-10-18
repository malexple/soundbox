package ru.mcs.research.dsp;

public final class Signals {

    private Signals() {
    }

    public static double[] normalizePeak(double[] x, double peak) {
        double max = 0.0;
        for (double v : x) {
            max = Math.max(max, Math.abs(v));
        }

        double[] out = new double[x.length];
        if (max < 1e-12) {
            return out;
        }

        double k = peak / max;
        for (int i = 0; i < x.length; i++) {
            out[i] = x[i] * k;
        }
        return out;
    }

    public static double rms(double[] x) {
        if (x.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : x) {
            sum += v * v;
        }
        return Math.sqrt(sum / x.length);
    }
}