/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.sandbox.facet.recorders;

import static org.apache.lucene.sandbox.facet.iterators.OrdinalIterator.NO_MORE_ORDS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.internal.hppc.IntCursor;
import org.apache.lucene.internal.hppc.IntObjectHashMap;
import org.apache.lucene.sandbox.facet.FacetRollup;
import org.apache.lucene.sandbox.facet.iterators.OrdinalIterator;
import org.apache.lucene.search.LongValues;
import org.apache.lucene.search.LongValuesSource;

/**
 * {@link FacetRecorder} that computes multiple long aggregations per facet.
 *
 * <p>TODO: [premature optimization idea] if instead of one array we keep aggregations in two
 * LongVector (one for MAX aggregation and one for SUM) we can benefit from SIMD?
 *
 * @lucene.experimental
 */
public final class LongAggregationsFacetRecorder implements FacetRecorder {

  private IntObjectHashMap<long[]> values;
  private final List<IntObjectHashMap<long[]>> leafValues;

  private final LongValuesSource[] longValuesSources;
  private final Reducer[] reducers;

  /** Constructor. */
  public LongAggregationsFacetRecorder(LongValuesSource[] longValuesSources, Reducer[] reducers) {
    assert longValuesSources.length == reducers.length;
    this.longValuesSources = longValuesSources;
    this.reducers = reducers;
    leafValues = Collections.synchronizedList(new ArrayList<>());
  }

  @Override
  public LeafFacetRecorder getLeafRecorder(LeafReaderContext context) throws IOException {
    LongValues[] longValues = new LongValues[longValuesSources.length];
    for (int i = 0; i < longValuesSources.length; i++) {
      longValues[i] = longValuesSources[i].getValues(context, null);
    }
    IntObjectHashMap<long[]> valuesRecorder = new IntObjectHashMap<>();
    leafValues.add(valuesRecorder);
    return new LongAggregationsLeafFacetRecorder(longValues, reducers, valuesRecorder);
  }

  @Override
  public OrdinalIterator recordedOrds() {
    Iterator<IntCursor> ordIterator = values.keys().iterator();
    return new OrdinalIterator() {
      @Override
      public int nextOrd() throws IOException {
        if (ordIterator.hasNext()) {
          return ordIterator.next().value;
        } else {
          return NO_MORE_ORDS;
        }
      }
    };
  }

  @Override
  public boolean isEmpty() {
    return values.isEmpty();
  }

  @Override
  public void reduce(FacetRollup facetRollup) throws IOException {
    boolean firstElement = true;
    for (IntObjectHashMap<long[]> leafValue : leafValues) {
      if (firstElement) {
        values = leafValue;
        firstElement = false;
      } else {
        for (IntObjectHashMap.IntObjectCursor<long[]> elem : leafValue) {
          long[] vals = values.get(elem.key);
          if (vals == null) {
            values.put(elem.key, elem.value);
          } else {
            for (int i = 0; i < longValuesSources.length; i++) {
              vals[i] = reducers[i].reduce(vals[i], elem.value[i]);
            }
          }
        }
      }
    }
    if (firstElement) {
      // TODO: do we need empty map by default?
      values = new IntObjectHashMap<>();
    }
    if (facetRollup != null) {
      OrdinalIterator dimOrds = facetRollup.getDimOrdsToRollup();
      for (int dimOrd = dimOrds.nextOrd(); dimOrd != NO_MORE_ORDS; dimOrd = dimOrds.nextOrd()) {
        rollup(values.get(dimOrd), dimOrd, facetRollup);
      }
    }
  }

  @Override
  public boolean contains(int ordinal) {
    return values.containsKey(ordinal);
  }

  /**
   * Rollup all child values of ord to accum, and return accum. Accum param can be null. In this
   * case, if recursive rollup for every child returns null, this method returns null. Otherwise,
   * accum is initialized.
   */
  private long[] rollup(long[] accum, int ord, FacetRollup facetRollup) throws IOException {
    OrdinalIterator childOrds = facetRollup.getChildrenOrds(ord);
    for (int nextChild = childOrds.nextOrd();
        nextChild != NO_MORE_ORDS;
        nextChild = childOrds.nextOrd()) {
      long[] current = rollup(values.get(nextChild), nextChild, facetRollup);
      if (current != null) {
        if (accum == null) {
          accum = new long[longValuesSources.length];
          values.put(ord, accum);
        }
        for (int i = 0; i < longValuesSources.length; i++) {
          accum[i] = reducers[i].reduce(accum[i], current[i]);
        }
      }
    }
    return accum;
  }

  public long getRecordedValue(int ord, int valuesId) {
    if (valuesId < 0 || valuesId >= longValuesSources.length) {
      throw new IllegalArgumentException("Invalid request for ordinal values");
    }
    long[] valuesForOrd = values.get(ord);
    if (valuesForOrd != null) {
      return valuesForOrd[valuesId];
    }
    return -1; // TODO: missing value, what do we want to return? Zero might be a better option.
  }

  private static class LongAggregationsLeafFacetRecorder implements LeafFacetRecorder {

    private final LongValues[] longValues;

    private final Reducer[] reducers;
    private final IntObjectHashMap<long[]> perOrdinalValues;

    LongAggregationsLeafFacetRecorder(
        LongValues[] longValues, Reducer[] reducers, IntObjectHashMap<long[]> perOrdinalValues) {
      this.longValues = longValues;
      this.reducers = reducers;
      this.perOrdinalValues = perOrdinalValues;
    }

    @Override
    public void record(int docId, int facetOrd) throws IOException {
      long[] valuesForOrd = perOrdinalValues.get(facetOrd);
      if (valuesForOrd == null) {
        valuesForOrd = new long[longValues.length];
        perOrdinalValues.put(facetOrd, valuesForOrd);
      }

      LongValues values;
      for (int i = 0; i < longValues.length; i++) {
        // TODO: cache advance/longValue results for current doc? Skipped for now as LongValues
        // themselves can keep the cache.
        values = longValues[i];
        if (values.advanceExact(docId)) {
          valuesForOrd[i] = reducers[i].reduce(valuesForOrd[i], values.longValue());
        }
      }
    }
  }
}
