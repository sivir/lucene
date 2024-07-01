package org.apache.lucene.sandbox.facet;

import org.apache.lucene.sandbox.facet.abstracts.FacetCutter;
import org.apache.lucene.sandbox.facet.abstracts.FacetRecorder;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.ScoreMode;

import java.io.IOException;

/**
 * {@link Collector} that brings together {@link FacetCutter} and {@link FacetRecorder} to compute facets during
 *  collection phase.
 */
public class FacetFieldCollector implements Collector {
    private final FacetCutter facetCutter;
    private final FacetRecorder facetRecorder;

    /**
     * Collector for cutter+recorder pair.
     */
    public FacetFieldCollector(FacetCutter facetCutter, FacetRecorder facetRecorder) {
        this.facetCutter = facetCutter;
        this.facetRecorder = facetRecorder;
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
        return new FacetFieldLeafCollector(
                context,
                facetCutter,
                facetRecorder);
    }

    @Override
    public ScoreMode scoreMode() {
        // TODO: We don't need to ever keep scores, do we?
        // return keepScores ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
        return ScoreMode.COMPLETE_NO_SCORES;
    }
}
