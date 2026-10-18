package ru.mcs.research.core;

import ru.mcs.research.dsp.SampleSyncPreamble;

/**
 * Канал с sample-level преамбулой.
 *
 * Раскладка:
 *   [preabmle: P отсчётов][data: framed payload × repetition, блоки]
 *
 * Данные начинаются с блока ceil(P / blockSize) — преамбула
 * съедает максимум один «лишний» блок.
 *
 * Точный сдвиг в отсчётах восстанавливается по преамбуле,
 * поэтому канал устойчив к crop/pad на произвольное число отсчётов.
 */
public final class SampleSyncStegoChannel {

    private static final int LENGTH_BITS = 16;

    private final BlockCarrier carrier;
    private final int repetition;
    private final SampleSyncPreamble preamble;
    private final int dataStartBlock;

    public SampleSyncStegoChannel(BlockCarrier carrier, int repetition,
                                  SampleSyncPreamble preamble) {
        if (repetition < 1 || repetition % 2 == 0) {
            throw new IllegalArgumentException(
                    "repetition должен быть положительным и нечётным");
        }
        this.carrier = carrier;
        this.repetition = repetition;
        this.preamble = preamble;
        this.dataStartBlock = (preamble.length() + carrier.blockSize() - 1)
                / carrier.blockSize();
    }

    public BlockCarrier carrier() { return carrier; }
    public int repetition() { return repetition; }
    public SampleSyncPreamble preamble() { return preamble; }

    public int capacityBytes(int signalLength) {
        int blocks = signalLength / carrier.blockSize();
        int dataBlocks = Math.max(0, blocks - dataStartBlock);
        int votedBits = dataBlocks / repetition;
        return Math.max(0, (votedBits - LENGTH_BITS) / 8);
    }

    public double[] embed(double[] signal, byte[] payload) {
        boolean[] frame = StegoChannel.frame(payload);

        int blockSize = carrier.blockSize();
        int availableBlocks = signal.length / blockSize;
        int neededBlocks = dataStartBlock + frame.length * repetition;

        if (neededBlocks > availableBlocks) {
            throw new IllegalArgumentException(String.format(
                    "Недостаточно места: нужно %d блоков, доступно %d",
                    neededBlocks, availableBlocks));
        }

        double[] out = signal.clone();
        preamble.write(out, 0);

        for (int i = 0; i < frame.length; i++) {
            for (int r = 0; r < repetition; r++) {
                int blockIndex = dataStartBlock + i * repetition + r;
                carrier.writeBit(out, blockIndex * blockSize, frame[i]);
            }
        }

        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(-1.0, Math.min(1.0, out[i]));
        }
        return out;
    }

    public boolean[] readFramedBits(double[] signal) {
        int preambleOffset = preamble.find(signal);
        int blockSize = carrier.blockSize();

        int firstDataSample = dataStartBlock * blockSize + preambleOffset;

        if (firstDataSample < 0 || firstDataSample >= signal.length) {
            throw new IllegalStateException(
                    "Старт данных вне сигнала: " + firstDataSample);
        }

        int availableBlocks = (signal.length - firstDataSample) / blockSize;
        int votedBits = availableBlocks / repetition;

        boolean[] bits = new boolean[votedBits];
        for (int i = 0; i < votedBits; i++) {
            int votes = 0;
            for (int r = 0; r < repetition; r++) {
                int blockOffset = firstDataSample
                        + (i * repetition + r) * blockSize;
                if (carrier.readBit(signal, blockOffset)) {
                    votes++;
                }
            }
            bits[i] = votes * 2 > repetition;
        }
        return bits;
    }

    public byte[] extract(double[] signal) {
        boolean[] bits = readFramedBits(signal);

        if (bits.length < LENGTH_BITS) {
            throw new IllegalStateException("Мало данных после синхры");
        }

        int length = 0;
        for (int i = 0; i < LENGTH_BITS; i++) {
            length = (length << 1) | (bits[i] ? 1 : 0);
        }
        if (length <= 0 || LENGTH_BITS + length * 8L > bits.length) {
            throw new IllegalStateException(
                    "Скрытые данные не найдены или повреждены");
        }

        byte[] out = new byte[length];
        for (int b = 0; b < length; b++) {
            int value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 1)
                        | (bits[LENGTH_BITS + b * 8 + i] ? 1 : 0);
            }
            out[b] = (byte) value;
        }
        return out;
    }
}