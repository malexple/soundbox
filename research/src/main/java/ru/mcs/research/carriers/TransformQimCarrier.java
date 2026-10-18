package ru.mcs.research.carriers;

import ru.mcs.research.core.BlockCarrier;
import ru.mcs.research.dsp.Dct2;
import ru.mcs.research.dsp.HaarWavelet;

import java.util.Arrays;

/**
 * QIM в одном коэффициенте преобразования.
 *
 * DCT:  ортонормированный DCT-II.
 * HAAR: ортонормированное разложение Хаара.
 *
 * Индекс 0 сознательно запрещён: DC-коэффициент слишком заметен
 * и слишком хрупкий.
 */
public final class TransformQimCarrier implements BlockCarrier {

    public enum Domain { DCT, HAAR }

    private final Domain domain;
    private final int blockSize;
    private final int index;
    private final double step;
    private final Dct2 dct;

    public TransformQimCarrier(Domain domain, int blockSize, int index, double step) {
        if (blockSize < 2 || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException("blockSize должен быть степенью двойки");
        }
        if (index < 1 || index >= blockSize) {
            throw new IllegalArgumentException("index должен быть в диапазоне [1, blockSize)");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step должен быть положительным");
        }

        this.domain = domain;
        this.blockSize = blockSize;
        this.index = index;
        this.step = step;
        this.dct = (domain == Domain.DCT) ? new Dct2(blockSize) : null;
    }

    @Override
    public String name() {
        return domain == Domain.DCT ? "dct-qim" : "haar-qim";
    }

    @Override
    public int blockSize() {
        return blockSize;
    }

    @Override
    public void writeBit(double[] signal, int offset, boolean bit) {
        double[] block = Arrays.copyOfRange(signal, offset, offset + blockSize);
        double[] spectrum = forward(block);

        double value = spectrum[index];
        double r = value / step;
        int q = (int) Math.rint(r);

        int wanted = bit ? 1 : 0;

        if ((q & 1) != wanted) {
            double up = Math.abs((q + 1) - r);
            double down = Math.abs((q - 1) - r);
            q += (up <= down) ? 1 : -1;
        }

        spectrum[index] = q * step;

        double[] rebuilt = inverse(spectrum);
        System.arraycopy(rebuilt, 0, signal, offset, blockSize);
    }

    @Override
    public boolean readBit(double[] signal, int offset) {
        double[] block = Arrays.copyOfRange(signal, offset, offset + blockSize);
        double[] spectrum = forward(block);

        int q = (int) Math.rint(spectrum[index] / step);
        return (q & 1) == 1;
    }

    private double[] forward(double[] block) {
        return domain == Domain.DCT ? dct.forward(block) : HaarWavelet.forward(block);
    }

    private double[] inverse(double[] spectrum) {
        return domain == Domain.DCT ? dct.inverse(spectrum) : HaarWavelet.inverse(spectrum);
    }
}