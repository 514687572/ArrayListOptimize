package com.stip.net.optimized;

import java.util.*;

public class SimpleGapBuffer<E> extends AbstractList<E> implements RandomAccess, java.io.Serializable {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_GAP_SIZE = 1024;
    private static final double GROWTH_FACTOR = 1.5;

    private Object[] buffer;
    private int gapStart;
    private int gapEnd;
    private int size;

    public SimpleGapBuffer(int initialCapacity) {
        int cap = Math.max(initialCapacity, 10);
        this.buffer = new Object[cap + DEFAULT_GAP_SIZE];
        this.gapStart = 0;
        this.gapEnd = buffer.length;
        this.size = 0;
    }

    public SimpleGapBuffer() {
        this(10);
    }

    private void moveGapTo(int index) {
        if (index == gapStart) return;
        if (index < gapStart) {
            int count = gapStart - index;
            System.arraycopy(buffer, index, buffer, gapEnd - count, count);
            gapEnd -= count;
            gapStart = index;
        } else {
            int count = index - gapStart;
            System.arraycopy(buffer, gapEnd, buffer, gapStart, count);
            gapStart += count;
            gapEnd += count;
        }
    }

    private void expandGap() {
        int gapSize = Math.max(buffer.length >> 2, DEFAULT_GAP_SIZE);
        Object[] newBuffer = new Object[buffer.length + gapSize];
        System.arraycopy(buffer, 0, newBuffer, 0, gapStart);
        System.arraycopy(buffer, gapEnd, newBuffer, gapStart + gapSize, buffer.length - gapEnd);
        gapEnd = gapStart + gapSize;
        buffer = newBuffer;
    }

    private void ensureCapacity(int minCapacity) {
        int available = buffer.length - size;
        if (available < minCapacity - size + 1) {
            int newCap = Math.max((int)(buffer.length * GROWTH_FACTOR), minCapacity + DEFAULT_GAP_SIZE);
            Object[] newBuffer = new Object[newCap];
            System.arraycopy(buffer, 0, newBuffer, 0, gapStart);
            int afterGap = buffer.length - gapEnd;
            int newGapEnd = newCap - afterGap;
            System.arraycopy(buffer, gapEnd, newBuffer, newGapEnd, afterGap);
            gapEnd = newGapEnd;
            buffer = newBuffer;
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public E get(int index) {
        rangeCheck(index);
        if (index < gapStart) {
            return (E) buffer[index];
        } else {
            return (E) buffer[index + (gapEnd - gapStart)];
        }
    }

    @Override
    public E set(int index, E element) {
        rangeCheck(index);
        E old;
        if (index < gapStart) {
            old = (E) buffer[index];
            buffer[index] = element;
        } else {
            int actualIndex = index + (gapEnd - gapStart);
            old = (E) buffer[actualIndex];
            buffer[actualIndex] = element;
        }
        return old;
    }

    @Override
    public boolean add(E e) {
        ensureCapacity(size + 1);
        moveGapTo(size);
        buffer[gapStart] = e;
        gapStart++;
        size++;
        if (gapStart >= gapEnd) {
            expandGap();
        }
        return true;
    }

    @Override
    public void add(int index, E element) {
        rangeCheckForAdd(index);
        ensureCapacity(size + 1);
        moveGapTo(index);
        buffer[gapStart] = element;
        gapStart++;
        size++;
        if (gapStart >= gapEnd) {
            expandGap();
        }
    }

    @Override
    public E remove(int index) {
        rangeCheck(index);
        moveGapTo(index);
        E old = (E) buffer[gapEnd];
        gapEnd++;
        size--;
        return old;
    }

    @Override
    public void clear() {
        gapStart = 0;
        gapEnd = buffer.length;
        size = 0;
        Arrays.fill(buffer, null);
    }

    private void rangeCheck(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    private void rangeCheckForAdd(int index) {
        if (index < 0 || index > size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private int cursor = 0;
            @Override
            public boolean hasNext() { return cursor < size; }
            @Override
            public E next() {
                if (!hasNext()) throw new NoSuchElementException();
                return get(cursor++);
            }
        };
    }
}
