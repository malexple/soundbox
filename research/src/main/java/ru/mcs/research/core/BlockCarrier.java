package ru.mcs.research.core;

/**
 * Физический уровень: один бит в одном блоке отсчётов.
 *
 * Реализации обязаны работать только в пределах
 * [offset, offset + blockSize()) и не читать соседние блоки.
 */
public interface BlockCarrier {

    String name();

    int blockSize();

    void writeBit(double[] signal, int offset, boolean bit);

    boolean readBit(double[] signal, int offset);
}