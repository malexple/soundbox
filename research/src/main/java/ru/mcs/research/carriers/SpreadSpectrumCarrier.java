package ru.mcs.research.carriers;

import ru.mcs.research.core.BlockCarrier;

import java.util.Random;

/**
 * Spread spectrum: бит кодируется знаком добавленной псевдослучайной
 * последовательности, чтение — корреляцией.
 *
 * Последовательность сбалансирована (равно +1 и -1), чтобы не зависеть
 * от постоянной составляющей сигнала.
 */
public final class SpreadSpectrumCarrier implements BlockCarrier {

    private final double[] pn;
    private final double amplitude;

    public SpreadSpectrumCarrier(int blockSize, double amplitude, long seed) {
        if (blockSize < 2 || blockSize % 2 != 0) {
            throw new IllegalArgumentException("blockSize должен быть чётным и >= 2");
        }
        if (amplitude <= 0) {
            throw new IllegalArgumentException("amplitude должен быть положительным");
        }

        this.amplitude = amplitude;
        this.pn = new double[blockSize];

        Random rnd = new Random(seed);
        for (int i = 0; i < blockSize / 2; i++) {
            pn[i] = 1.0;
        }
        for (int i = blockSize / 2; i < blockSize; i++) {
            pn[i] = -1.0;
        }

        for (int i = blockSize - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            double t = pn[i];
            pn[i] = pn[j];
            pn[j] = t;
        }
    }

    @Override
    public String name() {
        return "spread-spectrum";
    }

    @Override
    public int blockSize() {
        return pn.length;
    }

    @Override
    public void writeBit(double[] signal, int offset, boolean bit) {
        double sign = bit ? 1.0 : -1.0;
        for (int i = 0; i < pn.length; i++) {
            signal[offset + i] += amplitude * sign * pn[i];
        }
    }

    @Override
    public boolean readBit(double[] signal, int offset) {
        double sum = 0.0;
        for (int i = 0; i < pn.length; i++) {
            sum += signal[offset + i] * pn[i];
        }
        return sum > 0.0;
    }
}