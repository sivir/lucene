package org.apache.lucene.sandbox.facet.ranges;

import org.apache.lucene.facet.range.DoubleRange;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.facet.range.Range;
import org.apache.lucene.sandbox.facet.abstracts.FacetCutter;
import org.apache.lucene.util.NumericUtils;

/** {@link FacetCutter} for ranges **/
public abstract class RangeFacetCutter implements FacetCutter {
    // TODO: we don't always have field, e.g. for custom DoubleValuesSources - let's remove it from here?
    String field;

    // TODO: make the constructor also take in requested value sources and ranges
    // Ranges can be done now, we need to make a common interface for ValueSources
    RangeFacetCutter(String field) {
        this.field = field;
    }

    LongRange[] mapDoubleRangesToLongWithPrecision(DoubleRange[] doubleRanges) {
        LongRange[] longRanges = new LongRange[doubleRanges.length];
        for (int i =0; i < longRanges.length; i++) {
            DoubleRange dr = doubleRanges[i];
            longRanges[i] = new LongRange(dr.label,
                    NumericUtils.doubleToSortableLong(dr.min), true,
                    NumericUtils.doubleToSortableLong(dr.max), true);
        }
        return longRanges;
    }

}
