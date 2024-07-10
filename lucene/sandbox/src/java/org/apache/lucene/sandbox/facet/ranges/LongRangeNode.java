package org.apache.lucene.sandbox.facet.ranges;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds one node of the segment tree.
 *
 * TODO: dedup existing LongRangeNode.
 * TODO: does it have to be public?
 * */
public final class LongRangeNode {
    final LongRangeNode left;
    final LongRangeNode right;

    // Our range, inclusive:
    final long start;
    final long end;

    // If we are a leaf, the index into elementary ranges that we point to:
    final int elementaryIntervalIndex;

    // Which range indices to output when a query goes
    // through this node:
    List<Integer> outputs;

    /** add doc **/
    public LongRangeNode(
            long start,
            long end,
            LongRangeNode left,
            LongRangeNode right,
            int elementaryIntervalIndex) {
        this.start = start;
        this.end = end;
        this.left = left;
        this.right = right;
        this.elementaryIntervalIndex = elementaryIntervalIndex;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(sb, 0);
        return sb.toString();
    }

    static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    /** Recursively assigns range outputs to each node. */
    public void addOutputs(int index, LongRangeFacetCutter.LongRangeAndPos range) {
        if (start >= range.range().min && end <= range.range().max) {
            // Our range is fully included in the incoming
            // range; add to our output list:
            if (outputs == null) {
                outputs = new ArrayList<>();
            }
            outputs.add(range.pos());
        } else if (left != null) {
            assert right != null;
            // Recurse:
            left.addOutputs(index, range);
            right.addOutputs(index, range);
        }
    }

    void toString(StringBuilder sb, int depth) {
        indent(sb, depth);
        if (left == null) {
            assert right == null;
            sb.append("leaf: ").append(start).append(" to ").append(end);
        } else {
            sb.append("node: ").append(start).append(" to ").append(end);
        }
        if (outputs != null) {
            sb.append(" outputs=");
            sb.append(outputs);
        }
        sb.append('\n');

        if (left != null) {
            assert right != null;
            left.toString(sb, depth + 1);
            right.toString(sb, depth + 1);
        }
    }

    /** returns the range start value **/
    public long start() {
        return start;
    }

    /** returns the range end value **/
    public long end() {
        return end;
    }

    /** returns left node of segment tree node **/
    public LongRangeNode left() {
        return left;
    }

    /** returns right node of segment tree node **/
    public LongRangeNode right() {
        return right;
    }

    /** returns range indices to output when a query goes through this node: **/
    public List<Integer> outputs() {
        return outputs;
    }
}

