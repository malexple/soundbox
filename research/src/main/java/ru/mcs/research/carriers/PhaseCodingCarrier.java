package ru.mcs.research.carriers;

import ru.mcs.research.core.BlockCarrier;

/**
 * Phase coding: бит = фаза пилотного тона (0 или PI) внутри блока.
 *
 * Перед встраиванием из блока проекцируется собственная компонента
 * на частоте пилота, чтобы исходный сигнал не мешал декодированию.
 *
 * Требование: целое число циклов пилота в блоке.
 */
public final class PhaseCodingCarrier implements BlockCarrier {

    private final int blockSize;
    private final double amplitude;
    private final double[] cos;
    private final double[] sin;

    public PhaseCodingCarrier(int blockSize, double sampleRate,
                              double frequency, double amplitude) {
        if (blockSize < 2 || sampleRate <= 0 || frequency <= 0 || amplitude <= 0) {
            throw new IllegalArgumentException("Некорректные параметры пилота");
        }

        double cycles = frequency * blockSize / sampleRate;
        if (Math.abs(cycles - Math.rint(cycles)) > 1e-6) {
            throw new IllegalArgumentException(
                    "В блоке должно быть целое число циклов пилота"
            );
        }

        this.blockSize = blockSize;
        this.amplitude = amplitude;
        this.cos = new double[blockSize];
        this.sin = new double[blockSize];

        for (int i = 0; i < blockSize; i++) {
            double angle = 2.0 * Math.PI * frequency * i / sampleRate;
            cos[i] = Math.cos(angle);
            sin[i] = Math.sin(angle);
        }
    }

    @Override
    public String name() {
        return "phase-coding";
    }

    @Override
    public int blockSize() {
        return blockSize;
    }

    @Override
    public void writeBit(double[] signal, int offset, boolean bit) {
        double c = 0.0;
        double s = 0.0;

        for (int i = 0; i < blockSize; i++) {
            c += signal[offset + i] * cos[i];
            s += signal[offset + i] * sin[i];
        }

        c *= 2.0 / blockSize;
        s *= 2.0 / blockSize;

        double sign = bit ? 1.0 : -1.0;

        for (int i = 0; i < blockSize; i++) {
            signal[offset + i] += amplitude * sign * cos[i]
                    - c * cos[i]
                    - s * sin[i];
        }
    }

    @Override
    public boolean readBit(double[] signal, int offset) {
        double c = 0.0;
        for (int i = 0; i < blockSize; i++) {
            c += signal[offset + i] * cos[i];
        }
        return c > 0.0;
    }
}