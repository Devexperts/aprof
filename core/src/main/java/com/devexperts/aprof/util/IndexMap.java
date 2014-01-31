/*
 * Aprof - Java Memory Allocation Profiler
 * Copyright (C) 2002-2014  Devexperts LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.devexperts.aprof.util;

/**
 * @author Roman Elizarov
 */
public final class IndexMap {
	private static final long COUNT_OFFSET;
	private static final long SIZE_OFFSET;
	private static final int INT_ARRAY_BASE_OFFSET;
	private static final int INT_ARRAY_INDEX_SCALE;

	static {
		try {
			COUNT_OFFSET = UnsafeHolder.UNSAFE.objectFieldOffset(IndexMap.class.getDeclaredField("count"));
			SIZE_OFFSET = UnsafeHolder.UNSAFE.objectFieldOffset(IndexMap.class.getDeclaredField("size"));
			INT_ARRAY_BASE_OFFSET = UnsafeHolder.UNSAFE.arrayBaseOffset(int[].class);
			INT_ARRAY_INDEX_SCALE = UnsafeHolder.UNSAFE.arrayIndexScale(int[].class);
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	private static final IntIterator EMPTY_ITERATOR = new FastIntObjMap<IndexMap>().iterator();

	static {
		// advance internal state of iterator to the end, so that it can be concurrently used
		EMPTY_ITERATOR.hasNext();
	}

	/**
	 * Location id in AProfRegistry.locations
	 */
	private final int location;

	/**
	 * Root index in AProfRegistry.rootIndexes
	 */
	private final int index;

	/**
	 * Class histogram configuration.
	 * One object per datatype.
	 * <code>null</code> for non-arrays.
	 */
	private final int[] histogram;

	/**
	 * For non-arrays acts as an ordinal instance counter.
	 * For arrays counts instances created via {@link #increment(int size)}.
	 */
	private int count;

	/**
	 * Total size of all instances.
	 */
	private int size;

	/**
	 * Instance counter for arrays of specific lengths (as specified in {@link #histogram}).
	 * <code>null</code> for non-arrays.
	 */
	private final int[] histogramCounts;

	/**
	 * Children map. <code>null</code> when there are no children.
	 */
	private FastIntObjMap<IndexMap> items;

	public IndexMap(int location, int index, int[] histogram) {
		this.location = location;
		this.index = index;
		this.histogram = histogram;
		this.histogramCounts = histogram != null ? new int[histogram.length + 1] : null;
	}

	public int getLocation() {
		return location;
	}

	public int getIndex() {
		return index;
	}

	public int[] getHistogram() {
		return histogram;
	}

	public IndexMap get(int key) {
		FastIntObjMap<IndexMap> items = this.items; // atomic read
		return items == null ? null : items.get(key);
	}

	/* needs external synchronization */
	public void put(int key, IndexMap value) {
		if (items == null)
			items = new FastIntObjMap<IndexMap>();
		items.put(key, value);
	}

	public int size() {
		FastIntObjMap<IndexMap> items = this.items; // atomic read
		return items == null ? 0 : items.size();
	}

	public IntIterator iterator() {
		FastIntObjMap<IndexMap> items = this.items; // atomic read
		// note -- result is always of the same class that is retruned by FastIntObjMap.iterator() method
		return items == null ? EMPTY_ITERATOR : items.iterator();
	}

	public int getCount() {
		return count;
	}

	public int getSize() {
		return size;
	}

	/**
	 * Returns {@code true} for array data types.
	 */
	public boolean hasHistogram() {
		return histogramCounts != null;
	}

	public int getHistogramLength() {
		return histogramCounts.length;
	}

	public int[] getHistogramCounts() {
		return histogramCounts;
	}

	public int takeCount() {
		int val;
		do {
			val = count;
		} while (!UnsafeHolder.UNSAFE.compareAndSwapInt(this, COUNT_OFFSET, val, 0));
		return val;
	}

	public int takeSize() {
		int val;
		do {
			val = size;
		} while (!UnsafeHolder.UNSAFE.compareAndSwapInt(this, SIZE_OFFSET, val, 0));
		return val;
	}

	public int takeHistogramCount(int i) {
		int val;
		do {
			val = histogramCounts[i];
		} while (!UnsafeHolder.UNSAFE.compareAndSwapInt(histogramCounts,
				INT_ARRAY_BASE_OFFSET + i * INT_ARRAY_INDEX_SCALE, val, 0));
		return val;
	}

	public void increment() {
		int val;
		do {
			val = count;
		} while (!UnsafeHolder.UNSAFE.compareAndSwapInt(this, COUNT_OFFSET, val, val + 1));
	}

	public void increment(int size) {
		increment();
		incrementSize(size);
	}

	public void increment(int length, int size) {
		int i = getHistogramIndex(length);
		int val;
		do {
			val = histogramCounts[i];
		} while (!UnsafeHolder.UNSAFE.compareAndSwapInt(histogramCounts,
				INT_ARRAY_BASE_OFFSET + i * INT_ARRAY_INDEX_SCALE, val, val + 1));
		increment(size);
	}

	private void incrementSize(int size) {
		int val;
		do {
			val = this.size;
		} while (!UnsafeHolder.UNSAFE.compareAndSwapInt(this, SIZE_OFFSET, val, val + size));
	}

	private int getHistogramIndex(int length) {
		for (int i = 0; i < histogram.length; i++) {
			if (length <= histogram[i])
				return i;
		}
		return histogram.length;
	}
}
