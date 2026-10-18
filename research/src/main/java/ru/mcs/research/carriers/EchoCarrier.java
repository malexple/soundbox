package ru.mcs.research.carriers;

import ru.mcs.research.core.BlockCarrier;

import java.util.Arrays;

/**
 * Echo hiding: бит кодируется выбором одной из двух задержек эха.
 *
 * Это классический наивный прототип: детектор сравнивает
 * нормализованную автокорреляцию на двух лагах.
 *
 * Ожидаемое ограничение: на тональном/периодическом материале
 * собственная периодичность сигнала маскирует эхо.
 */
public final class EchoCarrier implements BlockCarrier {

    private final int blockSize;
    private final int delayZero;
    private final int delayOne;
    private final double gain;

    public EchoCarrier(int blockSize, int delayZero, int delayOne, double gain) {
        if (blockSize < 4 || delayZero <= 0 || delayOne <= delayZero
                || delayOne >= blockSize || gain <= 0) {
            throw new IllegalArgumentException("Некорректные параметры эха");
        }

        this.blockSize = blockSize;
        this.delayZero = delayZero;
        this.delayOne = delayOne;
        this.gain = gain;
    }

    @Override
    public String name() {
        return "echo-hiding";
    }

    @Override
    public int blockSize() {
        return blockSize;
    }

    @Override
    public void writeBit(double[] signal, int offset, boolean bit) {
        double[] source = Arrays.copyOfRange(signal, offset, offset + blockSize);
        int delay = bit ? delayOne : delayZero;

        for (int i = delay; i < blockSize; i++) {
            signal[offset + i] += gain * source[i - delay];
        }
    }

    @Override
    public boolean readBit(double[] signal, int offset) {
        int from = delayOne;

        double r0 = correlation(signal, offset, delayZero, from);
        double r1 = correlation(signal, offset, delayOne, from);

        return r1 > r0;
    }

    private double correlation(double[] signal, int offset, int delay, int from) {
        double num = 0.0;
        double ea = 0.0;
        double eb = 0.0;

        for (int i = from; i < blockSize; i++) {
            double a = signal[offset + i - delay];
            double b = signal[offset + i];
            num += a * b;
            ea += a * a;
            eb += b * b;
        }

        double den = Math.sqrt(ea * eb);
        return den < 1e-12 ? 0.0 : num / den;
    }
}