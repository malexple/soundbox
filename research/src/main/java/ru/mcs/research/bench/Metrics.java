package ru.mcs.research.bench;

import ru.mcs.research.dsp.Signals;

public final class Metrics {

    private Metrics() {
    }

    /** SNR исходного сигнала к внесённым изменениям. 999 = изменений нет. */
    public static double snrDb(double[] original, double[] modified) {
        int n = Math.min(original.length, modified.length);
        if (n == 0) {
            return 0.0;
        }

        double signalPower = 0.0;
        double errorPower = 0.0;

        for (int i = 0; i < n; i++) {
            double v = original[i];
            double e = modified[i] - v;
            signalPower += v * v;
            errorPower += e * e;
        }

        if (errorPower < 1e-20) {
            return 999.0;
        }
        if (signalPower < 1e-20) {
            return 0.0;
        }

        return 10.0 * Math.log10(signalPower / errorPower);
    }

    /** BER по битам. Недостающие биты считаются ошибками. */
    /** BER по битам. Недостающие биты считаются ошибками, лишний хвост игнорируется. */
    public static double ber(boolean[] expected, boolean[] actual) {
        if (expected.length == 0) {
            return 0.0;
        }

        int n = Math.min(expected.length, actual.length);

        int errors = 0;

        if (actual.length < expected.length) {
            errors += expected.length - actual.length;
        }

        for (int i = 0; i < n; i++) {
            if (expected[i] != actual[i]) {
                errors++;
            }
        }

        return (double) errors / expected.length;
    }

    public static double rms(double[] x) {
        return Signals.rms(x);
    }
}