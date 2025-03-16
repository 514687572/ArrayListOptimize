/*
 * Copyright (c) 1997, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package com.stip.net.optimized;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A buffered array list implementation using the tiered buffer strategy with dynamic 
 * block sizing for optimized insertions and removals.
 * 
 * This implementation uses a chunked storage structure where chunks can dynamically
 * resize when insertions occur, only splitting when reaching a threshold size.
 * This significantly reduces element movement during middle insertions.
 *
 * @param <E> type of elements stored in the list
 */
public class BufferedArrayList<E> extends AbstractList<E> implements RandomAccess, Cloneable, java.io.Serializable {
    private static final long serialVersionUID = -3483448492418448862L;

    /**
     * Default chunk size (number of elements per chunk)
     */
    private static final int CHUNK_SIZE = 4096;

    /**
     * Threshold for when to split a chunk into two
     */
    private static final int SPLIT_THRESHOLD = 8192;

    /**
     * Default initial capacity for the array list
     */
    private static final int DEFAULT_CAPACITY = 10;

    /**
     * Step size for fast index mapping table
     * Used to create direct O(1) lookups for chunk positions
     */
    private static final int INDEX_MAP_STEP = 1024;

    /**
     * The number of times this list has been structurally modified
     */
    protected transient int modCount = 0;

    /**
     * The size of the ArrayList (the number of elements it contains)
     */
    private int size;

    /**
     * Chunk class to store elements with dynamic capacity
     */
    private class Chunk {
        Object[] elements;      // Array to store elements
        int used;               // Number of used elements
        int capacity;           // Current capacity of the chunk

        Chunk(int initialCapacity) {
            this.capacity = Math.max(initialCapacity, CHUNK_SIZE);
            elements = new Object[capacity];
            used = 0;
        }
        
        /**
         * Resizes this chunk to accommodate additional elements
         */
        void resize(int additionalCapacity) {
            int newCapacity = capacity + additionalCapacity;
            Object[] newElements = new Object[newCapacity];
            System.arraycopy(elements, 0, newElements, 0, capacity);
            elements = newElements;
            capacity = newCapacity;
        }
    }

    /**
     * Array to store chunks
     */
    private Object[] chunks;
    
    /**
     * Number of chunks actually in use
     */
    private int chunkCount;
    
    /**
     * Array to store the starting index of each chunk in the logical sequence
     */
    private int[] chunkStartIndices;
    
    /**
     * Array to store the capacity of each chunk
     */
    private int[] chunkCapacities;
    
    /**
     * Initial capacity for chunks array
     */
    private static final int INITIAL_CHUNKS_CAPACITY = 16;

    /**
     * Fast index mapping table for O(1) chunk lookups
     * Maps logical index / INDEX_MAP_STEP to the chunk that contains
     * the index or the chunk just before it
     */
    private int[] fastIndexMap;

    /**
     * Size of the fast index map
     */
    private int fastIndexMapSize;

    /**
     * Last accessed chunk index (for sequential access optimization)
     */
    private int lastAccessedChunkIndex = -1;

    /**
     * Last accessed chunk starting index
     */
    private int lastAccessedStartIndex = 0;

    /**
     * Constructs an empty list with the specified initial capacity.
     *
     * @param initialCapacity the initial capacity of the list
     * @throws IllegalArgumentException if the specified initial capacity is negative
     */
    public BufferedArrayList(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Illegal Capacity: " + initialCapacity);
        }

        // Calculate how many chunks we'll need initially
        int neededChunks = Math.max(1, (initialCapacity + CHUNK_SIZE - 1) / CHUNK_SIZE);
        int initialChunksCapacity = Math.max(INITIAL_CHUNKS_CAPACITY, neededChunks);
        
        // Initialize the chunks array and metadata arrays
        chunks = new Object[initialChunksCapacity];
        chunkStartIndices = new int[initialChunksCapacity];
        chunkCapacities = new int[initialChunksCapacity];
        chunkCount = 0;
        
        // Initialize fast index mapping table
        int initialFastMapSize = Math.max(1, (initialCapacity + INDEX_MAP_STEP - 1) / INDEX_MAP_STEP);
        fastIndexMap = new int[initialFastMapSize];
        fastIndexMapSize = initialFastMapSize;
        
        // Create initial chunks if needed
        if (initialCapacity > 0) {
            for (int i = 0; i < neededChunks; i++) {
                addChunk(new Chunk(CHUNK_SIZE));
            }
        }
    }

    /**
     * Constructs an empty list with a default initial capacity (10).
     */
    public BufferedArrayList() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Locates the chunk and position within chunk for the element
     * using a fast O(1) lookup with a fallback to binary search
     */
    private int[] getChunkPosition(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }

        // 检查是否有有效的chunk
        if (chunkCount <= 0 || chunks == null) {
            throw new IllegalStateException("No chunks available in the list");
        }

        // Fast path: check last accessed chunk (helps with sequential access)
        if (lastAccessedChunkIndex >= 0 && lastAccessedChunkIndex < chunkCount) {
            int startIndex = lastAccessedStartIndex;
            Chunk chunk = (Chunk) chunks[lastAccessedChunkIndex];
            if (index >= startIndex && index < startIndex + chunk.used) {
                return new int[]{lastAccessedChunkIndex, index - startIndex};
            }
            
            // Check the next chunk too (common for sequential access)
            if (lastAccessedChunkIndex + 1 < chunkCount) {
                int nextStartIndex = chunkStartIndices[lastAccessedChunkIndex + 1];
                if (index >= nextStartIndex && index < nextStartIndex + ((Chunk)chunks[lastAccessedChunkIndex + 1]).used) {
                    lastAccessedChunkIndex++;
                    lastAccessedStartIndex = nextStartIndex;
                    return new int[]{lastAccessedChunkIndex, index - nextStartIndex};
                }
            }
        }

        // Using fast index map for O(1) lookup
        int mapIndex = index / INDEX_MAP_STEP;
        if (mapIndex < fastIndexMapSize) {
            int chunkIndex = fastIndexMap[mapIndex];
            
            // 确保获取到的chunkIndex在有效范围内
            if (chunkIndex < 0 || chunkIndex >= chunkCount) {
                // 修复无效索引：重置为0并从头开始搜索
                chunkIndex = 0;
            }
            
            // Make sure we don't start searching past the point we need
            while (chunkIndex > 0 && chunkStartIndices[chunkIndex] > index) {
                chunkIndex--;
            }
            
            // Find the exact chunk by searching forward from the mapped position
            // This is typically just a few steps (often 0 or 1)
            while (chunkIndex < chunkCount - 1 && 
                   chunkStartIndices[chunkIndex + 1] <= index) {
                chunkIndex++;
            }
            
            // 最终再次验证索引有效性
            if (chunkIndex < 0 || chunkIndex >= chunkCount) {
                throw new IllegalStateException("Internal error: computed invalid chunk index " + chunkIndex + " (chunks count: " + chunkCount + ")");
            }
            
            // Update cache for next access
            lastAccessedChunkIndex = chunkIndex;
            lastAccessedStartIndex = chunkStartIndices[chunkIndex];
            
            return new int[]{chunkIndex, index - chunkStartIndices[chunkIndex]};
        }
        
        // Fall back to binary search for indices beyond our map
        int chunkIndex = findChunkIndexByValue(index);
        
        // 验证二分查找返回的索引
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalStateException("Binary search returned invalid chunk index " + chunkIndex + " for index " + index);
        }
        
        // Update cache for next access
        lastAccessedChunkIndex = chunkIndex;
        lastAccessedStartIndex = chunkStartIndices[chunkIndex];
        
        return new int[]{chunkIndex, index - chunkStartIndices[chunkIndex]};
    }

    /**
     * Binary search to find the chunk containing the given logical index
     */
    private int findChunkIndexByValue(int index) {
        if (chunkCount == 0) {
            // If no chunks exist, create one
            addChunk(new Chunk(CHUNK_SIZE));
            return 0;
        }
        
        int low = 0;
        int high = chunkCount - 1;
        
        // Binary search for the chunk containing our index
        while (low <= high) {
            int mid = (low + high) >>> 1;
            
            // If index is less than this chunk's start index, search lower half
            if (mid > 0 && index < chunkStartIndices[mid]) {
                high = mid - 1;
            }
            // If index is greater than or equal to next chunk's start index (if exists), search upper half
            else if (mid < chunkCount - 1 && index >= chunkStartIndices[mid + 1]) {
                low = mid + 1;
            }
            // Found the right chunk
            else {
                return mid;
            }
        }
        
        // 确保返回值在有效范围内
        if (low < 0) low = 0;
        if (low >= chunkCount) low = chunkCount - 1;
        
        return low;
    }

    /**
     * Ensures that the list can hold at least the number of elements specified
     */
    public void ensureCapacity(int minCapacity) {
        if (minCapacity > 0) {
            // Calculate total capacity including existing chunks
            int totalCapacity = 0;
            
            // If we have no chunks yet, initialize with one
            if (chunkCount == 0 && minCapacity > 0) {
                addChunk(new Chunk(CHUNK_SIZE));
            }
            
            // Calculate how many more elements we can accommodate in existing chunks
            for (int i = 0; i < chunkCount; i++) {
                totalCapacity += chunkCapacities[i];
            }
            
            // If we need more capacity, add chunks
            if (minCapacity > totalCapacity) {
                int additionalCapacity = minCapacity - totalCapacity;
                int chunksNeeded = (additionalCapacity + CHUNK_SIZE - 1) / CHUNK_SIZE;
                
                for (int i = 0; i < chunksNeeded; i++) {
                    addChunk(new Chunk(CHUNK_SIZE));
                }
            }
        }
    }

    /**
     * Gets an element from the specified position in the chunk
     */
    @SuppressWarnings("unchecked")
    private E elementAt(Chunk chunk, int positionInChunk) {
        if (positionInChunk < 0 || positionInChunk >= chunk.used) {
            throw new IllegalArgumentException(
                "Position in chunk out of bounds: " + positionInChunk + 
                ", used=" + chunk.used);
        }
        
        return (E) chunk.elements[positionInChunk];
    }

    /**
     * Updates chunk boundaries from a specific chunk index
     */
    private void updateChunkBoundaries(int fromChunkIndex) {
        if (fromChunkIndex < 0 || fromChunkIndex >= chunkCount) {
            return;
        }
        
        int currentIndex = fromChunkIndex == 0 ? 0 : chunkStartIndices[fromChunkIndex - 1] + ((Chunk)chunks[fromChunkIndex - 1]).used;
        
        for (int i = fromChunkIndex; i < chunkCount; i++) {
            chunkStartIndices[i] = currentIndex;
            Chunk chunk = (Chunk) chunks[i];
            currentIndex += chunk.used;
            chunkCapacities[i] = chunk.capacity;
        }
        
        // Update fast index map incrementally after boundary changes
        incrementalUpdateFastIndexMap(fromChunkIndex);
    }

    /**
     * Updates the fast index mapping table incrementally from a specific chunk index
     * This ensures O(1) access to chunks while minimizing update costs
     */
    private void incrementalUpdateFastIndexMap(int fromChunkIndex) {
        // If no chunks, nothing to update
        if (chunkCount == 0) return;
        
        // 确保fromChunkIndex在有效范围内
        if (fromChunkIndex < 0) fromChunkIndex = 0;
        if (fromChunkIndex >= chunkCount) fromChunkIndex = chunkCount - 1;
        
        // Calculate first affected map index (the first index that maps to fromChunkIndex or beyond)
        int firstAffectedIndex = fromChunkIndex == 0 ? 0 : chunkStartIndices[fromChunkIndex] / INDEX_MAP_STEP;
        
        // Calculate needed size for the map
        int neededSize = (size + INDEX_MAP_STEP - 1) / INDEX_MAP_STEP;
        
        // Resize if necessary
        if (neededSize > fastIndexMapSize) {
            int newSize = Math.max(fastIndexMapSize * 2, neededSize);
            int[] newMap = new int[newSize];
            
            // Copy existing mappings
            if (fastIndexMapSize > 0) {
                System.arraycopy(fastIndexMap, 0, newMap, 0, fastIndexMapSize);
            }
            
            fastIndexMap = newMap;
            fastIndexMapSize = newSize;
        }
        
        // Only update the affected portion of the index map
        if (chunkCount > 0 && firstAffectedIndex < neededSize) {
            // Start with the chunk from previous map entry if possible
            int currentChunk = 0;
            if (firstAffectedIndex > 0 && firstAffectedIndex - 1 < fastIndexMapSize) {
                int prevMapChunk = fastIndexMap[firstAffectedIndex - 1];
                // 确保previousChunk也是有效的
                if (prevMapChunk >= 0 && prevMapChunk < chunkCount) {
                    currentChunk = prevMapChunk;
                }
            }
            
            // Update each affected map entry
            for (int i = firstAffectedIndex; i < neededSize; i++) {
                int logicalIndex = i * INDEX_MAP_STEP;
                
                // Skip if we're past the end of the list
                if (logicalIndex >= size) break;
                
                // Advance to the correct chunk (likely just a few iterations)
                while (currentChunk < chunkCount - 1 && 
                       chunkStartIndices[currentChunk + 1] <= logicalIndex) {
                    currentChunk++;
                }
                
                // Ensure currentChunk is valid
                if (currentChunk < 0 || currentChunk >= chunkCount) {
                    currentChunk = chunkCount - 1;
                }
                
                // If the current entry was already pointing to the right chunk, we can stop
                // This optimization works because subsequent entries wouldn't change either
                // However, we only apply this optimization if we didn't resize the map
                if (i < fastIndexMapSize && fastIndexMap[i] == currentChunk && 
                    i > firstAffectedIndex && i - 1 < fastIndexMapSize && 
                    fastIndexMap[i-1] == fastIndexMap[i-1]) {
                    break;
                }
                
                fastIndexMap[i] = currentChunk;
            }
        }
    }

    /**
     * Updates the fast index mapping table completely
     * This is only used when a complete rebuild is needed
     */
    private void fullUpdateFastIndexMap() {
        // 如果没有chunk，直接返回
        if (chunkCount == 0) return;
        
        // Calculate needed size for the map
        int neededSize = (size + INDEX_MAP_STEP - 1) / INDEX_MAP_STEP;
        if (neededSize <= 0) neededSize = 1; // 确保至少有一个元素
        
        // Resize if necessary
        if (neededSize > fastIndexMapSize) {
            int newSize = Math.max(fastIndexMapSize * 2, neededSize);
            int[] newMap = new int[newSize];
            
            // Copy existing mappings
            if (fastIndexMapSize > 0 && fastIndexMap != null) {
                System.arraycopy(fastIndexMap, 0, newMap, 0, Math.min(fastIndexMapSize, fastIndexMap.length));
            }
            
            fastIndexMap = newMap;
            fastIndexMapSize = newSize;
        }
        
        // Update the map entries
        if (chunkCount > 0) {
            int currentChunk = 0;
            
            for (int i = 0; i < neededSize; i++) {
                int logicalIndex = i * INDEX_MAP_STEP;
                
                // Skip if we're past the end of the list
                if (logicalIndex >= size) break;
                
                // Find the appropriate chunk for this index
                while (currentChunk < chunkCount - 1 && 
                       chunkStartIndices[currentChunk + 1] <= logicalIndex) {
                    currentChunk++;
                }
                
                // 确保索引有效
                if (currentChunk < 0) currentChunk = 0;
                if (currentChunk >= chunkCount) currentChunk = chunkCount - 1;
                
                fastIndexMap[i] = currentChunk;
            }
        }
    }

    /**
     * Adds an element to the end of the list
     */
    @Override
    public boolean add(E e) {
        ensureCapacity(size + 1);
        
        // Get position for appending
        int lastChunkIndex = chunkCount - 1;
        Chunk lastChunk = (Chunk) chunks[lastChunkIndex];
        
        // If last chunk is full, resize it
        if (lastChunk.used >= lastChunk.capacity) {
            // 直接创建新的chunk
            Chunk newChunk = new Chunk(CHUNK_SIZE);
            addChunk(newChunk);
            lastChunkIndex = chunkCount - 1;
            lastChunk = (Chunk) chunks[lastChunkIndex];
        }
        
        // Add element to last chunk
        lastChunk.elements[lastChunk.used++] = e;
        size++;
        modCount++;
        
        return true;
    }

    /**
     * Inserts an element at the specified position in this list
     */
    @Override
    public void add(int index, E element) {
        rangeCheckForAdd(index);
        
        // If adding at the end, use the simpler add method
        if (index == size) {
            add(element);
            return;
        }
        
        // Find the chunk and position for insertion
        int[] pos = getChunkPosition(index);
        int chunkIndex = pos[0];
        int positionInChunk = pos[1];
        Chunk chunk = (Chunk) chunks[chunkIndex];
        
        // If inserting at the exact end of a chunk, consider inserting at the beginning of next chunk
        if (positionInChunk == chunk.used && chunkIndex < chunkCount - 1) {
            chunkIndex++;
            positionInChunk = 0;
            chunk = (Chunk) chunks[chunkIndex];
        }
        
        // If the chunk is full, resize it
        if (chunk.used >= chunk.capacity) {
            // Resize with some extra space for future insertions
            chunk.resize(Math.max(CHUNK_SIZE / 4, 1));
            chunkCapacities[chunkIndex] = chunk.capacity;
            
            // Check if we need to split after resize
            if (chunk.capacity >= SPLIT_THRESHOLD) {
                // When splitting, the logical index remains the same,
                // but the chunk and position might change
                splitChunk(chunkIndex);
                
                // Recalculate the position after splitting
                pos = getChunkPosition(index);
                chunkIndex = pos[0];
                positionInChunk = pos[1];
                chunk = (Chunk) chunks[chunkIndex];
            }
        }
        
        // Make room for the new element by shifting existing elements
        if (positionInChunk < chunk.used) {
            System.arraycopy(chunk.elements, positionInChunk, chunk.elements, positionInChunk + 1, 
                            chunk.used - positionInChunk);
        }
        
        // Insert the element
        chunk.elements[positionInChunk] = element;
        chunk.used++;
        
        // Update size and modification count
        size++;
        modCount++;
        
        // Update chunk boundaries from this chunk onwards
        updateChunkBoundaries(chunkIndex);
    }

    /**
     * Removes the element at the specified position in this list
     */
    @Override
    public E remove(int index) {
        rangeCheck(index);
        
        // 检查列表是否为空或者chunks数组是否为null
        if (size == 0 || chunks == null || chunkCount == 0) {
            throw new IllegalStateException("Cannot remove from an empty list");
        }
        
        int[] pos = getChunkPosition(index);
        if (pos == null || pos.length < 2) {
            throw new IllegalStateException("Invalid chunk position");
        }
        
        int chunkIndex = pos[0];
        int positionInChunk = pos[1];
        
        // 检查chunkIndex是否有效
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalStateException("Invalid chunk index: " + chunkIndex);
        }
        
        // 检查chunk是否为null
        if (chunks[chunkIndex] == null) {
            throw new IllegalStateException("Chunk at index " + chunkIndex + " is null");
        }
        
        Chunk chunk = (Chunk) chunks[chunkIndex];
        
        // 检查position是否有效
        if (positionInChunk < 0 || positionInChunk >= chunk.used) {
            throw new IllegalStateException("Invalid position in chunk: " + positionInChunk);
        }
        
        // Get the element to be removed
        @SuppressWarnings("unchecked")
        E oldValue = (E) chunk.elements[positionInChunk];
        
        // Shift elements to close the gap
        if (positionInChunk < chunk.used - 1) {
            System.arraycopy(chunk.elements, positionInChunk + 1, chunk.elements, positionInChunk, 
                            chunk.used - positionInChunk - 1);
        }
        
        // Clear the last element position to help GC
        chunk.elements[--chunk.used] = null;
        
        // Update size and modification count
        size--;
        modCount++;
        
        // Check if chunk is very empty and consider merging
        if (chunk.used == 0 && chunkCount > 1) {
            // Remove empty chunk
            removeChunkAt(chunkIndex);
        } else if (chunk.used < chunk.capacity / 4 && chunk.capacity > CHUNK_SIZE && chunkCount > 1) {
            // Shrink oversized chunks that are mostly empty
            normalizeChunk(chunkIndex);
        }
        
        // Update chunk boundaries from this chunk onwards
        updateChunkBoundaries(chunkIndex);
        
        return oldValue;
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        
        boolean removed = false;
        final int expectedModCount = modCount;
        
        // Create a BitSet to mark elements that should be removed
        BitSet removeSet = new BitSet(size);
        
        // First pass: identify elements to remove
        for (int index = 0; index < size; index++) {
            @SuppressWarnings("unchecked")
            E element = get(index);
            if (filter.test(element)) {
                removeSet.set(index);
                removed = true;
            }
        }
        
        if (modCount != expectedModCount) {
            throw new ConcurrentModificationException();
        }
        
        if (!removed) {
            return false;
        }
        
        // Second pass: compact the list by removing marked elements
        int writeIndex = 0;
        for (int readIndex = 0; readIndex < size; readIndex++) {
            if (!removeSet.get(readIndex)) {
                if (writeIndex != readIndex) {
                    // Move element to its new position
                    set(writeIndex, get(readIndex));
                }
                writeIndex++;
            }
        }
        
        // Truncate the list by removing trailing elements
        int newSize = writeIndex;
        while (size > newSize) {
            remove(size - 1);
        }
        
        return true;
    }

    /**
     * Gets the element at the specified position in this list
     */
    @Override
    public E get(int index) {
        rangeCheck(index);
        
        int[] pos = getChunkPosition(index);
        Chunk chunk = (Chunk) chunks[pos[0]];
        
        return elementAt(chunk, pos[1]);
    }

    /**
     * Replaces the element at the specified position in this list with the specified element
     */
    @Override
    public E set(int index, E element) {
        rangeCheck(index);
        
        int[] pos = getChunkPosition(index);
        Chunk chunk = (Chunk) chunks[pos[0]];
        
        @SuppressWarnings("unchecked")
        E oldValue = (E) chunk.elements[pos[1]];
        chunk.elements[pos[1]] = element;
        
        return oldValue;
    }

    /**
     * Returns the number of elements in this list
     */
    @Override
    public int size() {
        return size;
    }

    /**
     * Removes all elements from this list.
     */
    @Override
    public void clear() {
        // Clear all chunks
        for (int i = 0; i < chunkCount; i++) {
            chunks[i] = null;
            chunkStartIndices[i] = 0;
            chunkCapacities[i] = 0;
        }
        chunkCount = 0;
        size = 0;
        modCount++;
    }

    /**
     * Adds a chunk to the chunks array, expanding if necessary
     */
    private void addChunk(Chunk chunk) {
        ensureChunkCapacity(chunkCount + 1);
        
        // Set the starting index for the new chunk
        if (chunkCount > 0) {
            Chunk lastChunk = (Chunk) chunks[chunkCount - 1];
            chunkStartIndices[chunkCount] = chunkStartIndices[chunkCount - 1] + lastChunk.used;
        } else {
            chunkStartIndices[0] = 0;
        }
        
        chunks[chunkCount] = chunk;
        chunkCapacities[chunkCount] = chunk.capacity;
        chunkCount++;
        
        // Update the fast index map for the new chunk
        incrementalUpdateFastIndexMap(chunkCount - 1);
    }
    
    /**
     * Adds a chunk at the specified index, shifting existing chunks as needed
     */
    private void addChunkAtIndex(int index, Chunk chunk) {
        ensureChunkCapacity(chunkCount + 1);
        
        // Shift chunks to make room
        if (index < chunkCount) {
            System.arraycopy(chunks, index, chunks, index + 1, chunkCount - index);
            System.arraycopy(chunkStartIndices, index, chunkStartIndices, index + 1, chunkCount - index);
            System.arraycopy(chunkCapacities, index, chunkCapacities, index + 1, chunkCount - index);
        }
        
        chunks[index] = chunk;
        chunkCapacities[index] = chunk.capacity;
        chunkCount++;
        
        // Update chunk boundaries
        updateChunkBoundaries(index);
    }
    
    /**
     * Ensures that the chunks array has enough capacity
     */
    private void ensureChunkCapacity(int minCapacity) {
        // If current array is too small, resize it
        if (chunks.length < minCapacity) {
            // Calculate new size (double current size, or use minCapacity if larger)
            int newCapacity = Math.max(chunks.length * 2, minCapacity);
            
            // Create new arrays and copy elements
            Object[] newChunks = new Object[newCapacity];
            int[] newBoundaries = new int[newCapacity];
            int[] newCapacities = new int[newCapacity];
            
            System.arraycopy(chunks, 0, newChunks, 0, chunkCount);
            System.arraycopy(chunkStartIndices, 0, newBoundaries, 0, chunkCount);
            System.arraycopy(chunkCapacities, 0, newCapacities, 0, chunkCount);
            
            chunks = newChunks;
            chunkStartIndices = newBoundaries;
            chunkCapacities = newCapacities;
        }
    }
    
    /**
     * Removes a chunk at the specified index
     */
    private void removeChunkAt(int index) {
        if (index < 0 || index >= chunkCount) {
            return;
        }
        
        // Shift remaining chunks to fill the gap
        int numToMove = chunkCount - index - 1;
        if (numToMove > 0) {
            System.arraycopy(chunks, index + 1, chunks, index, numToMove);
            System.arraycopy(chunkCapacities, index + 1, chunkCapacities, index, numToMove);
        }
        
        // Clear the last element to help GC
        chunks[--chunkCount] = null;
        
        // Update chunk boundaries
        updateChunkBoundaries(index);
    }

    /**
     * Splits a chunk into two when it exceeds the threshold size
     */
    private void splitChunk(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            return;
        }
        
        Chunk originalChunk = (Chunk) chunks[chunkIndex];
        
        // Only split if the chunk is over the threshold
        if (originalChunk.capacity < SPLIT_THRESHOLD || originalChunk.used < CHUNK_SIZE) {
            return;
        }
        
        // Determine split point - half the used elements
        int halfPoint = originalChunk.used / 2;
        
        // Create a new chunk for the second half
        Chunk newChunk = new Chunk(CHUNK_SIZE);
        
        // Copy second half elements to new chunk
        for (int i = 0; i < originalChunk.used - halfPoint; i++) {
            newChunk.elements[i] = originalChunk.elements[halfPoint + i];
            originalChunk.elements[halfPoint + i] = null; // Help GC
        }
        
        // Update usage counts
        newChunk.used = originalChunk.used - halfPoint;
        originalChunk.used = halfPoint;
        
        // Normalize original chunk size if it's much larger than needed
        if (originalChunk.capacity > CHUNK_SIZE * 1.5) {
            normalizeChunk(chunkIndex);
        }
        
        // Insert new chunk after original one
        addChunkAtIndex(chunkIndex + 1, newChunk);
    }
    
    /**
     * Normalizes an oversized chunk back to standard size
     */
    private void normalizeChunk(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            return;
        }
        
        Chunk chunk = (Chunk) chunks[chunkIndex];
        
        // Only normalize if the chunk is significantly larger than standard size
        // or if it's very empty relative to its capacity
        if (chunk.capacity <= CHUNK_SIZE || 
            (chunk.used > CHUNK_SIZE / 2 && chunk.capacity < CHUNK_SIZE * 1.5)) {
            return;
        }
        
        // Create a new chunk with standard size
        Chunk normalizedChunk = new Chunk(CHUNK_SIZE);
        
        // Copy elements to the new chunk
        System.arraycopy(chunk.elements, 0, normalizedChunk.elements, 0, chunk.used);
        normalizedChunk.used = chunk.used;
        
        // Replace the old chunk
        chunks[chunkIndex] = normalizedChunk;
        chunkCapacities[chunkIndex] = CHUNK_SIZE;
    }

    /**
     * Range check for add operations
     */
    private void rangeCheckForAdd(int index) {
        if (index < 0 || index > size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    /**
     * Range check for get/set operations
     */
    private void rangeCheck(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        final int expectedModCount = modCount;
        
        for (int i = 0; i < chunkCount; i++) {
            Chunk chunk = (Chunk) chunks[i];
            for (int j = 0; j < chunk.used; j++) {
                @SuppressWarnings("unchecked")
                E element = (E) chunk.elements[j];
                action.accept(element);
                
                if (modCount != expectedModCount) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        Objects.requireNonNull(operator);
        final int expectedModCount = modCount;
        
        for (int i = 0; i < chunkCount; i++) {
            Chunk chunk = (Chunk) chunks[i];
            for (int j = 0; j < chunk.used; j++) {
                @SuppressWarnings("unchecked")
                E oldValue = (E) chunk.elements[j];
                E newValue = operator.apply(oldValue);
                chunk.elements[j] = newValue;
                
                if (modCount != expectedModCount) {
                    throw new ConcurrentModificationException();
                }
            }
        }
        
        modCount++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void sort(Comparator<? super E> c) {
        // Create a temporary array and copy all elements
        Object[] tempArray = new Object[size];
        int index = 0;
        
        for (int i = 0; i < chunkCount; i++) {
            Chunk chunk = (Chunk) chunks[i];
            System.arraycopy(chunk.elements, 0, tempArray, index, chunk.used);
            index += chunk.used;
        }
        
        // Sort the temporary array
        Arrays.sort((E[]) tempArray, 0, size, c);
        
        // Copy back to chunks
        index = 0;
        for (int i = 0; i < chunkCount; i++) {
            Chunk chunk = (Chunk) chunks[i];
            System.arraycopy(tempArray, index, chunk.elements, 0, chunk.used);
            index += chunk.used;
        }
        
        modCount++;
    }

    /**
     * clone method
     */
    @Override
    public Object clone() {
        try {
            @SuppressWarnings("unchecked")
            BufferedArrayList<E> clone = (BufferedArrayList<E>) super.clone();
            
            // Create fresh arrays for the clone
            clone.chunks = new Object[chunkCount];
            clone.chunkStartIndices = new int[chunkCount];
            clone.chunkCapacities = new int[chunkCount];
            clone.chunkCount = 0;
            clone.size = 0;
            
            // Copy all elements
            for (int i = 0; i < size; i++) {
                clone.add(get(i));
            }
            
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    /**
     * Returns an iterator over the elements in this list in proper sequence.
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }
    
    /**
     * Returns a list iterator over the elements in this list in proper sequence.
     */
    @Override
    public ListIterator<E> listIterator() {
        return new ListItr(0);
    }
    
    /**
     * Returns a list iterator over the elements in this list in proper sequence,
     * starting at the specified position.
     */
    @Override
    public ListIterator<E> listIterator(int index) {
        rangeCheckForAdd(index);
        return new ListItr(index);
    }
    
    private class Itr implements Iterator<E> {
        int cursor = 0;
        int lastRet = -1;
        int expectedModCount = modCount;
        
        Itr() {}
        
        @Override
        public boolean hasNext() {
            return cursor < size;
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public E next() {
            checkForComodification();
            try {
                int i = cursor;
                E next = get(i);
                lastRet = i;
                cursor = i + 1;
                return next;
            } catch (IndexOutOfBoundsException e) {
                checkForComodification();
                throw new NoSuchElementException();
            }
        }
        
        @Override
        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();
            
            try {
                BufferedArrayList.this.remove(lastRet);
                if (lastRet < cursor)
                    cursor--;
                lastRet = -1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException e) {
                throw new ConcurrentModificationException();
            }
        }
        
        final void checkForComodification() {
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
        }
    }
    
    private class ListItr extends Itr implements ListIterator<E> {
        ListItr(int index) {
            cursor = index;
        }
        
        @Override
        public boolean hasPrevious() {
            return cursor > 0;
        }
        
        @Override
        public E previous() {
            checkForComodification();
            try {
                int i = cursor - 1;
                E previous = get(i);
                lastRet = cursor = i;
                return previous;
            } catch (IndexOutOfBoundsException e) {
                checkForComodification();
                throw new NoSuchElementException();
            }
        }
        
        @Override
        public int nextIndex() {
            return cursor;
        }
        
        @Override
        public int previousIndex() {
            return cursor - 1;
        }
        
        @Override
        public void set(E e) {
            if (lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();
            
            try {
                BufferedArrayList.this.set(lastRet, e);
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException();
            }
        }
        
        @Override
        public void add(E e) {
            checkForComodification();
            
            try {
                int i = cursor;
                BufferedArrayList.this.add(i, e);
                cursor = i + 1;
                lastRet = -1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException();
            }
        }
    }
    
    /**
     * Returns a view of the portion of this list between the specified
     * fromIndex, inclusive, and toIndex, exclusive.
     */
    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        subListRangeCheck(fromIndex, toIndex, size);
        return new SubList<>(this, fromIndex, toIndex);
    }
    
    private static class SubList<E> extends AbstractList<E> implements RandomAccess {
        private final BufferedArrayList<E> parent;
        private final int offset;
        private int size;
        
        SubList(BufferedArrayList<E> parent, int fromIndex, int toIndex) {
            this.parent = parent;
            offset = fromIndex;
            size = toIndex - fromIndex;
        }
        
        @Override
        public E get(int index) {
            rangeCheck(index, size);
            return parent.get(index + offset);
        }
        
        @Override
        public E set(int index, E e) {
            rangeCheck(index, size);
            return parent.set(index + offset, e);
        }
        
        @Override
        public void add(int index, E e) {
            rangeCheckForAdd(index, size);
            parent.add(index + offset, e);
            size++;
        }
        
        @Override
        public E remove(int index) {
            rangeCheck(index, size);
            E result = parent.remove(index + offset);
            size--;
            return result;
        }
        
        @Override
        public int size() {
            return size;
        }
    }
    
    private static void rangeCheck(int index, int size) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }
    
    private static void rangeCheckForAdd(int index, int size) {
        if (index < 0 || index > size)
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }
    
    private static void subListRangeCheck(int fromIndex, int toIndex, int size) {
        if (fromIndex < 0)
            throw new IndexOutOfBoundsException("fromIndex = " + fromIndex);
        if (toIndex > size)
            throw new IndexOutOfBoundsException("toIndex = " + toIndex);
        if (fromIndex > toIndex)
            throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
    }

} 