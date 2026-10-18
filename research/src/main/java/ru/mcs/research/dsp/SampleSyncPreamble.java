package ru.mcs.research.dsp;

import java.util.Random;

/**
 * Sample-level spread-spectrum preamble для синхронизации.
 *
 * Встраивается аддитивно в начало сигнала. При приёме позиция
 * восстанавливается нормированной взаимной корреляцией с той же ПСП.
 *
 * В отличие от блочной синхры, даёт точный сдвиг в отсчётах —
 * это то, что нужно для атак crop/pad на нецелое число блоков.
 *
 * Детект работает и при частичной потере начала: пока сохранилось
 * не менее MIN_OVERLAP_RATIO преамбулы, корреляция находит пик.
 */
public final class SampleSyncPreamble {

    /** Минимальная доля сохранившейся преамбулы для детекта. */
    private static final double MIN_OVERLAP_RATIO = 0.5;

    /** Шаг грубого поиска; затем локальное уточнение. */
    private static final int COARSE_STRIDE = 8;

    /** Минимальный нормированный пик для принятия решения. */
    private static final double MIN_SCORE = 0.25;

    private final double[] pn;
    private final double amplitude;
    private final int length;

    public SampleSyncPreamble(int length, double amplitude, long seed) {
        if (length < 128 || (length & (length - 1)) != 0) {
            throw new IllegalArgumentException(
                    "length должен быть степенью двойки, >= 128");
        }
        if (amplitude <= 0 || amplitude > 0.5) {
            throw new IllegalArgumentException(
                    "amplitude должна быть в (0, 0.5]");
        }
        this.length = length;
        this.amplitude = amplitude;
        this.pn = generateBalancedPn(length, seed);
    }

    public int length() { return length; }
    public double amplitude() { return amplitude; }

    public void write(double[] signal, int offset) {
        if (offset < 0 || offset + length > signal.length) {
            throw new IllegalArgumentException("преамбула не помещается");
        }
        for (int i = 0; i < length; i++) {
            signal[offset + i] += amplitude * pn[i];
        }
    }

    /**
     * Возвращает сдвиг начала преамбулы в отсчётах.
     * Может быть отрицательным, если начало обрезано.
     *
     * @throws IllegalStateException если надёжный пик не найден
     */
    public int find(double[] signal) {
        if (signal.length < length / 2) {
            throw new IllegalStateException("Сигнал слишком короткий");
        }

        int minOffset = -(length - 1);
        int maxOffset = signal.length - 1;
        int minOverlap = (int) Math.ceil(length * MIN_OVERLAP_RATIO);

        int bestOffset = Integer.MIN_VALUE;
        double bestScore = -1.0;

        for (int off = minOffset; off <= maxOffset; off++) {
            int start   = Math.max(0, off);
            int pnStart = Math.max(0, -off);
            int overlap = Math.min(length - pnStart, signal.length - start);
            if (overlap < minOverlap) continue;

            double s = score(signal, off);
            if (s > bestScore) {
                bestScore = s;
                bestOffset = off;
            }
        }

        if (bestScore < MIN_SCORE) {
            throw new IllegalStateException(String.format(
                    "Преамбула не найдена: пик %.3f < %.3f",
                    bestScore, MIN_SCORE));
        }
        return bestOffset;
    }

    private double score(double[] signal, int offset) {
        int start   = Math.max(0, offset);
        int pnStart = Math.max(0, -offset);
        int overlap = Math.min(length - pnStart, signal.length - start);

        double corr = 0.0, sigE = 0.0, pnE = 0.0;
        for (int i = 0; i < overlap; i++) {
            double s = signal[start + i];
            double p = pn[pnStart + i];
            corr += s * p;
            sigE += s * s;
            pnE  += p * p;
        }

        double den = Math.sqrt(sigE * pnE);
        return den < 1e-12 ? 0.0 : Math.abs(corr) / den;
    }

    private static double[] generateBalancedPn(int length, long seed) {
        double[] s = new double[length];
        for (int i = 0; i < length / 2; i++) s[i] = 1.0;
        for (int i = length / 2; i < length; i++) s[i] = -1.0;

        Random rnd = new Random(seed);
        for (int i = length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            double t = s[i]; s[i] = s[j]; s[j] = t;
        }
        return s;
    }
}