package ru.mcs.research.core;

import java.util.Random;

/**
 * Исследовательский канал с блочной синхронизацией.
 *
 * Кадр:
 *   [sync header][repeated framed payload]
 *
 * framed payload:
 *   [16 бит длина][текст]
 *
 * Синхронизация ищет начало кадра по известной последовательности,
 * включая случай, когда начало сигнала было слегка обрезано
 * (но вырезано меньше, чем длина синхрозаголовка).
 */
public final class SyncStegoChannel {

    private static final int LENGTH_BITS = 16;

    private static final int SYNC_LENGTH = 64;
    private static final double SYNC_THRESHOLD = 0.75;
    private static final long SYNC_SEED = 0x5EEDL;

    private final BlockCarrier carrier;
    private final int repetition;
    private final boolean[] sync;
    private final int minOverlap;

    public SyncStegoChannel(BlockCarrier carrier, int repetition) {
        if (repetition < 1 || repetition % 2 == 0) {
            throw new IllegalArgumentException(
                    "repetition должен быть положительным и нечётным"
            );
        }

        this.carrier = carrier;
        this.repetition = repetition;
        this.sync = generateSync(SYNC_LENGTH, SYNC_SEED);
        this.minOverlap = Math.max(32, SYNC_LENGTH * 3 / 4);
    }

    public BlockCarrier carrier() {
        return carrier;
    }

    public int repetition() {
        return repetition;
    }

    public int capacityBytes(int signalLength) {
        int blocks = signalLength / carrier.blockSize();
        int dataBlocks = Math.max(0, blocks - SYNC_LENGTH);
        int votedBits = dataBlocks / repetition;
        return Math.max(0, (votedBits - LENGTH_BITS) / 8);
    }

    public double[] embed(double[] signal, byte[] payload) {
        boolean[] frame = StegoChannel.frame(payload);

        int blockSize = carrier.blockSize();
        int availableBlocks = signal.length / blockSize;
        int neededBlocks = SYNC_LENGTH + frame.length * repetition;

        if (neededBlocks > availableBlocks) {
            throw new IllegalArgumentException(String.format(
                    "Недостаточно места: нужно %d блоков, доступно %d",
                    neededBlocks, availableBlocks
            ));
        }

        double[] out = signal.clone();

        for (int i = 0; i < SYNC_LENGTH; i++) {
            carrier.writeBit(out, i * blockSize, sync[i]);
        }

        for (int i = 0; i < frame.length; i++) {
            for (int r = 0; r < repetition; r++) {
                int blockIndex = SYNC_LENGTH + i * repetition + r;
                carrier.writeBit(out, blockIndex * blockSize, frame[i]);
            }
        }

        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(-1.0, Math.min(1.0, out[i]));
        }

        return out;
    }

    /**
     * Возвращает прочитанные кадр-биты после синхронизации.
     * Лишний хвост сохраняется, как и в старом StegoChannel.readBits().
     */
    public boolean[] readFramedBits(double[] signal) {
        boolean[] raw = readRawBlocks(signal);
        int syncOffset = findSyncOffset(raw);

        int payloadStartBlock = syncOffset + SYNC_LENGTH;

        if (payloadStartBlock < 0 || payloadStartBlock > raw.length) {
            throw new IllegalStateException("Синхронизация найдена, но полезная часть недоступна");
        }

        int remainingBlocks = raw.length - payloadStartBlock;
        int votedBits = remainingBlocks / repetition;

        boolean[] bits = new boolean[votedBits];

        for (int i = 0; i < votedBits; i++) {
            int votes = 0;

            for (int r = 0; r < repetition; r++) {
                int index = payloadStartBlock + i * repetition + r;
                if (raw[index]) {
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
            throw new IllegalStateException("Слишком мало данных после синхронизации");
        }

        int length = 0;
        for (int i = 0; i < LENGTH_BITS; i++) {
            length = (length << 1) | (bits[i] ? 1 : 0);
        }

        if (length <= 0 || LENGTH_BITS + length * 8L > bits.length) {
            throw new IllegalStateException("Скрытые данные не найдены или повреждены");
        }

        byte[] out = new byte[length];

        for (int b = 0; b < length; b++) {
            int value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 1) | (bits[LENGTH_BITS + b * 8 + i] ? 1 : 0);
            }
            out[b] = (byte) value;
        }

        return out;
    }

    private boolean[] readRawBlocks(double[] signal) {
        int blockSize = carrier.blockSize();
        int blocks = signal.length / blockSize;

        boolean[] raw = new boolean[blocks];

        for (int i = 0; i < blocks; i++) {
            raw[i] = carrier.readBit(signal, i * blockSize);
        }

        return raw;
    }

    /**
     * Ищет начало синхропоследовательности.
     *
     * Возвращает смещение в блоках.
     * Может быть отрицательным, если начало синхропоследовательности
     * было вырезано, но остаток синхры ещё присутствует.
     */
    private int findSyncOffset(boolean[] raw) {
        int minAfter = LENGTH_BITS * repetition;

        int minOffset = -(SYNC_LENGTH - 1);
        int maxOffset = raw.length - minAfter - SYNC_LENGTH;

        if (maxOffset < minOffset) {
            throw new IllegalStateException("Сигнал слишком короткий для поиска синхронизации");
        }

        int bestOffset = Integer.MIN_VALUE;
        double bestScore = -1.0;
        int bestOverlap = -1;

        for (int offset = minOffset; offset <= maxOffset; offset++) {
            int rawStart = Math.max(0, offset);
            int syncStart = Math.max(0, -offset);

            int overlap = Math.min(SYNC_LENGTH - syncStart, raw.length - rawStart);

            if (overlap < minOverlap) {
                continue;
            }

            int matches = 0;

            for (int j = 0; j < overlap; j++) {
                if (raw[rawStart + j] == sync[syncStart + j]) {
                    matches++;
                }
            }

            double score = (double) matches / overlap;

            if (score > bestScore + 1e-12
                    || (Math.abs(score - bestScore) <= 1e-12 && overlap > bestOverlap)) {
                bestScore = score;
                bestOverlap = overlap;
                bestOffset = offset;
            }
        }

        if (bestScore < SYNC_THRESHOLD) {
            throw new IllegalStateException(String.format(
                    "Синхронизация не найдена: лучший результат %.2f < %.2f",
                    bestScore, SYNC_THRESHOLD
            ));
        }

        return bestOffset;
    }

    private static boolean[] generateSync(int length, long seed) {
        if (length < 8 || length % 2 != 0) {
            throw new IllegalArgumentException("SYNC_LENGTH должен быть чётным и >= 8");
        }

        boolean[] s = new boolean[length];

        for (int i = 0; i < length / 2; i++) {
            s[i] = true;
        }

        Random rnd = new Random(seed);

        for (int i = length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            boolean tmp = s[i];
            s[i] = s[j];
            s[j] = tmp;
        }

        return s;
    }
}