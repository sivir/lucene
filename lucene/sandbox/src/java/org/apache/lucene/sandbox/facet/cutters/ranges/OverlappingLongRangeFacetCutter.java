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
package org.apache.lucene.sandbox.facet.cutters.ranges;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.facet.MultiLongValues;
import org.apache.lucene.facet.MultiLongValuesSource;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.sandbox.facet.cutters.LeafFacetCutter;
import org.apache.lucene.search.LongValues;
import org.apache.lucene.search.LongValuesSource;

/**
 * {@link RangeFacetCutter} for ranges of long value that overlap. Uses segment tree optimisation to
 * find all matching ranges for a given value <a
 * href="https://blog.mikemccandless.com/2013/12/fast-range-faceting-using-segment-trees.html">fast-range-faceting-
 * using-segment-trees.html</a>
 */
class OverlappingLongRangeFacetCutter extends LongRangeFacetCutter {
  private final LongRangeNode root;

  OverlappingLongRangeFacetCutter(
      String field,
      MultiLongValuesSource longValuesSource,
      LongValuesSource singleLongValuesSource,
      LongRange[] longRanges) {
    super(field, longValuesSource, singleLongValuesSource, longRanges);

    // Build binary tree on top of intervals:
    root = split(0, elementaryIntervals.size(), elementaryIntervals);

    // Set outputs, so we know which range to output for each node in the tree:
    for (int i = 0; i < sortedRanges.length; i++) {
      root.addOutputs(i, sortedRanges[i]);
    }
  }

  @Override
  List<InclusiveRange> buildElementaryIntervals() {
    // Maps all range inclusive endpoints to int flags; 1
    // = start of interval, 2 = end of interval.  We need to
    // track the start vs end case separately because if a
    // given point is both, then it must be its own
    // elementary interval:
    Map<Long, Integer> endsMap = new HashMap<>();

    endsMap.put(Long.MIN_VALUE, 1);
    endsMap.put(Long.MAX_VALUE, 2);

    for (LongRangeAndPos rangeAndPos : sortedRanges) {
      Integer cur = endsMap.get(rangeAndPos.range().min);
      if (cur == null) {
        endsMap.put(rangeAndPos.range().min, 1);
      } else {
        endsMap.put(rangeAndPos.range().min, cur | 1);
      }
      cur = endsMap.get(rangeAndPos.range().max);
      if (cur == null) {
        endsMap.put(rangeAndPos.range().max, 2);
      } else {
        endsMap.put(rangeAndPos.range().max, cur | 2);
      }
    }

    List<Long> endsList = new ArrayList<>(endsMap.keySet());
    Collections.sort(endsList);

    // Build elementaryIntervals (a 1D Venn diagram):
    List<InclusiveRange> elementaryIntervals = new ArrayList<>();
    int upto = 1;
    long v = endsList.get(0);
    long prev;
    if (endsMap.get(v) == 3) {
      elementaryIntervals.add(new InclusiveRange(v, v));
      prev = v + 1;
    } else {
      prev = v;
    }

    while (upto < endsList.size()) {
      v = endsList.get(upto);
      int flags = endsMap.get(v);
      if (flags == 3) {
        // This point is both an end and a start; we need to
        // separate it:
        if (v > prev) {
          elementaryIntervals.add(new InclusiveRange(prev, v - 1));
        }
        elementaryIntervals.add(new InclusiveRange(v, v));
        prev = v + 1;
      } else if (flags == 1) {
        // This point is only the start of an interval;
        // attach it to next interval:
        if (v > prev) {
          elementaryIntervals.add(new InclusiveRange(prev, v - 1));
        }
        prev = v;
      } else {
        assert flags == 2;
        // This point is only the end of an interval; attach
        // it to last interval:
        elementaryIntervals.add(new InclusiveRange(prev, v));
        prev = v + 1;
      }
      upto++;
    }

    return elementaryIntervals;
  }

  private static LongRangeNode split(int start, int end, List<InclusiveRange> elementaryIntervals) {
    if (start == end - 1) {
      // leaf
      InclusiveRange range = elementaryIntervals.get(start);
      return new LongRangeNode(range.start(), range.end(), null, null, start);
    } else {
      int mid = (start + end) >>> 1;
      LongRangeNode left = split(start, mid, elementaryIntervals);
      LongRangeNode right = split(mid, end, elementaryIntervals);
      return new LongRangeNode(left.start(), right.end(), left, right, -1);
    }
  }

  @Override
  public LeafFacetCutter createLeafCutter(LeafReaderContext context) throws IOException {
    if (singleValues != null) {
      LongValues values = singleValues.getValues(context, null);
      return new OverlappingSingleValuedRangeLeafFacetCutter(
          values, boundaries, pos, requestedRangeCount, root);
    } else {
      MultiLongValues values = valuesSource.getValues(context);
      return new OverlappingMultiValuedRangeLeafFacetCutter(
          values, boundaries, pos, requestedRangeCount, root);
    }
  }

  /**
   * TODO: dedup OverlappingMultiValuedRangeLeafFacetCutter and
   * OverlappingSingleValuedRangeLeafFacetCutter code - they are similar but they extend different
   * base classes.
   */
  static class OverlappingMultiValuedRangeLeafFacetCutter
      extends LongRangeMultivaluedLeafFacetCutter {

    LongRangeNode elementaryIntervalRoot;

    private int elementaryIntervalUpto;

    OverlappingMultiValuedRangeLeafFacetCutter(
        MultiLongValues longValues,
        long[] boundaries,
        int[] pos,
        int requestedRangeCount,
        LongRangeNode elementaryIntervalRoot) {
      super(longValues, boundaries, pos, requestedRangeCount);
      requestedIntervalTracker = new IntervalTracker.MultiIntervalTracker(requestedRangeCount);
      this.elementaryIntervalRoot = elementaryIntervalRoot;
    }

    @Override
    void maybeRollUp(IntervalTracker rollUpInto) {
      elementaryIntervalUpto = 0;
      rollupMultiValued(elementaryIntervalRoot);
    }

    // Note: combined rollUpSingleValued and rollUpMultiValued from OverlappingLongRangeCounter into
    // 1 rollUp method
    private boolean rollupMultiValued(LongRangeNode node) {
      boolean containedHit;
      if (node.left != null) {
        containedHit = rollupMultiValued(node.left);
        containedHit |= rollupMultiValued(node.right);
      } else {
        // Leaf:
        containedHit = elementaryIntervalTracker.get(elementaryIntervalUpto);
        elementaryIntervalUpto++;
      }
      if (containedHit && node.outputs != null) {
        for (int rangeIndex : node.outputs) {
          requestedIntervalTracker.set(rangeIndex);
        }
      }

      return containedHit;
    }

    @Override
    public int nextOrd() throws IOException {
      if (requestedIntervalTracker == null) {
        return NO_MORE_ORDS;
      }
      return requestedIntervalTracker.nextOrd();
    }
  }

  static class OverlappingSingleValuedRangeLeafFacetCutter
      extends LongRangeSingleValuedLeafFacetCutter {

    LongRangeNode elementaryIntervalRoot;

    private int elementaryIntervalUpto;

    OverlappingSingleValuedRangeLeafFacetCutter(
        LongValues longValues,
        long[] boundaries,
        int[] pos,
        int requestedRangeCount,
        LongRangeNode elementaryIntervalRoot) {
      super(longValues, boundaries, pos, requestedRangeCount);
      requestedIntervalTracker = new IntervalTracker.MultiIntervalTracker(requestedRangeCount);
      this.elementaryIntervalRoot = elementaryIntervalRoot;
    }

    @Override
    void maybeRollUp(IntervalTracker rollUpInto) {
      // TODO: for single valued we can do rollup after we collect for all documents,
      //  e.g. in reduce method. Maybe we can extend FacetRollup interface to handle this
      //  case too?
      elementaryIntervalUpto = 0;
      rollupMultiValued(elementaryIntervalRoot);
    }

    // Note: combined rollUpSingleValued and rollUpMultiValued from OverlappingLongRangeCounter into
    // 1 rollUp method
    private boolean rollupMultiValued(LongRangeNode node) {
      boolean containedHit;
      if (node.left != null) {
        containedHit = rollupMultiValued(node.left);
        containedHit |= rollupMultiValued(node.right);
      } else {
        // Leaf:
        containedHit = elementaryIntervalTracker.get(elementaryIntervalUpto);
        elementaryIntervalUpto++;
      }
      if (containedHit && node.outputs != null) {
        for (int rangeIndex : node.outputs) {
          requestedIntervalTracker.set(rangeIndex);
        }
      }

      return containedHit;
    }

    @Override
    public int nextOrd() throws IOException {
      if (requestedIntervalTracker == null) {
        return NO_MORE_ORDS;
      }
      return requestedIntervalTracker.nextOrd();
    }
  }
}