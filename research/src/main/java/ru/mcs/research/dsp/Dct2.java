package ru.mcs.research.dsp;

/**
 * Ортонормированный DCT-II.
 *
 * Прямая реализация O(N^2): для исследовательского стенда это сознательный
 * компромисс в пользу простоты и проверяемости.
 * В продуктовой версии здесь будет FFT-based DCT.
 */
public final class Dct2 {

    private final int n;
    private final double[][] kernel;

    public Dct2(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n должен быть положительным");
        }
        this.n = n;
        this.kernel = new double[n][n];

        double scale = Math.sqrt(2.0 / n);

        for (int k = 0; k < n; k++) {
            double ck = (k == 0) ? scale / Math.sqrt(2.0) : scale;
            for (int i = 0; i < n; i++) {
                kernel[k][i] = ck * Math.cos(Math.PI * (2 * i + 1) * k / (2.0 * n));
            }
        }
    }

    public double[] forward(double[] x) {
        if (x.length != n) {
            throw new IllegalArgumentException("Ожидается блок длины " + n);
        }

        double[] out = new double[n];

        for (int k = 0; k < n; k++) {
            double sum = 0.0;
            double[] row = kernel[k];
            for (int i = 0; i < n; i++) {
                sum += row[i] * x[i];
            }
            out[k] = sum;
        }

        return out;
    }

    public double[] inverse(double[] coefficients) {
        if (coefficients.length != n) {
            throw new IllegalArgumentException("Ожидается спектр длины " + n);
        }

        double[] out = new double[n];

        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            for (int k = 0; k < n; k++) {
                sum += kernel[k][i] * coefficients[k];
            }
            out[i] = sum;
        }

        return out;
    }
}