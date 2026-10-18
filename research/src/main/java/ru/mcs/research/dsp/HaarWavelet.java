package ru.mcs.research.dsp;

/** Ортонормированное разложение по вейвлету Хаара. Длина — степень двойки. */
public final class HaarWavelet {

    private static final double S = Math.sqrt(0.5);

    private HaarWavelet() {
    }

    public static double[] forward(double[] x) {
        int n = x.length;
        checkLength(n);

        double[] a = x.clone();
        double[] tmp = new double[n];

        for (int len = n; len > 1; len >>= 1) {
            int half = len >> 1;
            for (int i = 0; i < len; i += 2) {
                tmp[i >> 1] = (a[i] + a[i + 1]) * S;
                tmp[half + (i >> 1)] = (a[i] - a[i + 1]) * S;
            }
            System.arraycopy(tmp, 0, a, 0, len);
        }

        return a;
    }

    public static double[] inverse(double[] coefficients) {
        int n = coefficients.length;
        checkLength(n);

        double[] a = coefficients.clone();
        double[] tmp = new double[n];

        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            for (int i = 0; i < half; i++) {
                double avg = a[i];
                double detail = a[half + i];
                tmp[2 * i] = (avg + detail) * S;
                tmp[2 * i + 1] = (avg - detail) * S;
            }
            System.arraycopy(tmp, 0, a, 0, len);
        }

        return a;
    }

    private static void checkLength(int n) {
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Длина должна быть степенью двойки и >= 2");
        }
    }
}