package com.qsteam.toml_config.util.range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable union of numeric intervals.
 * <p>
 * This is used to check satisfiability for numeric Range-DSL expressions and to implement
 * boolean algebra (union/intersection/complement) on numeric constraints.
 */
final class RangeSet {
    private final List<Interval> intervals;

    private RangeSet(List<Interval> intervals) {
        this.intervals = intervals;
    }

    static RangeSet empty() {
        return new RangeSet(Collections.emptyList());
    }

    static RangeSet fromInterval(Double lower, boolean includeLower, Double upper, boolean includeUpper) {
        if (lower != null && upper != null) {
            int cmp = Double.compare(lower, upper);
            if (cmp > 0) {
                return empty();
            }
            if (cmp == 0 && (!includeLower || !includeUpper)) {
                return empty();
            }
        }
        List<Interval> list = new ArrayList<>(1);
        list.add(new Interval(lower, includeLower, upper, includeUpper));
        return normalize(list);
    }

    boolean isNonEmpty() {
        return !intervals.isEmpty();
    }

    RangeSet union(RangeSet other) {
        if (this.intervals.isEmpty()) return other;
        if (other.intervals.isEmpty()) return this;
        List<Interval> combined = new ArrayList<>(this.intervals.size() + other.intervals.size());
        combined.addAll(this.intervals);
        combined.addAll(other.intervals);
        return normalize(combined);
    }

    RangeSet intersect(RangeSet other) {
        if (this.intervals.isEmpty() || other.intervals.isEmpty()) return empty();
        List<Interval> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < this.intervals.size() && j < other.intervals.size()) {
            Interval a = this.intervals.get(i);
            Interval b = other.intervals.get(j);
            Interval x = a.intersect(b);
            if (x != null) out.add(x);

            if (a.upper == null) {
                j++;
            } else if (b.upper == null) {
                i++;
            } else if (Double.compare(a.upper, b.upper) < 0 || (Double.compare(a.upper, b.upper) == 0 && a.includeUpper && !b.includeUpper)) {
                i++;
            } else {
                j++;
            }
        }
        return normalize(out);
    }

    RangeSet complement() {
        if (intervals.isEmpty()) {
            return fromInterval(null, false, null, false);
        }
        List<Interval> out = new ArrayList<>();
        Interval first = intervals.get(0);
        if (first.lower != null) {
            out.add(new Interval(null, false, first.lower, !first.includeLower));
        }
        for (int i = 0; i < intervals.size() - 1; i++) {
            Interval a = intervals.get(i);
            Interval b = intervals.get(i + 1);
            out.add(new Interval(a.upper, !a.includeUpper, b.lower, !b.includeLower));
        }
        Interval last = intervals.get(intervals.size() - 1);
        if (last.upper != null) {
            out.add(new Interval(last.upper, !last.includeUpper, null, false));
        }
        return normalize(out);
    }

    private static RangeSet normalize(List<Interval> list) {
        if (list.isEmpty()) return empty();
        list.sort(Comparator.comparing((Interval i) -> i.lower, (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            return Double.compare(a, b);
        }).thenComparing(i -> !i.includeLower));

        List<Interval> merged = new ArrayList<>();
        Interval cur = list.get(0);
        for (int idx = 1; idx < list.size(); idx++) {
            Interval next = list.get(idx);
            Interval joined = cur.tryMerge(next);
            if (joined != null) {
                cur = joined;
            } else {
                if (!cur.isEmpty()) merged.add(cur);
                cur = next;
            }
        }
        if (!cur.isEmpty()) merged.add(cur);

        return new RangeSet(merged);
    }

    private static final class Interval {
        private final Double lower;
        private final boolean includeLower;
        private final Double upper;
        private final boolean includeUpper;

        private Interval(Double lower, boolean includeLower, Double upper, boolean includeUpper) {
            this.lower = lower;
            this.includeLower = includeLower;
            this.upper = upper;
            this.includeUpper = includeUpper;
        }

        private boolean isEmpty() {
            if (lower != null && upper != null) {
                int cmp = Double.compare(lower, upper);
                if (cmp > 0) return true;
                if (cmp == 0 && (!includeLower || !includeUpper)) return true;
            }
            return false;
        }

        private Interval tryMerge(Interval other) {
            // if cur and other overlap or touch (inclusive), merge
            if (this.upper == null) return this;
            if (other.lower == null) return new Interval(null, other.includeLower, this.upper, this.includeUpper);

            int cmp = Double.compare(this.upper, other.lower);
            if (cmp < 0) {
                return null;
            }
            if (cmp == 0 && !(this.includeUpper || other.includeLower)) {
                return null;
            }

            Double newUpper;
            boolean newIncludeUpper;
            if (other.upper == null) {
                newUpper = null;
                newIncludeUpper = false;
            } else {
                int ucmp = Double.compare(this.upper, other.upper);
                if (ucmp > 0) {
                    newUpper = this.upper;
                    newIncludeUpper = this.includeUpper;
                } else if (ucmp < 0) {
                    newUpper = other.upper;
                    newIncludeUpper = other.includeUpper;
                } else {
                    newUpper = this.upper;
                    newIncludeUpper = this.includeUpper || other.includeUpper;
                }
            }

            return new Interval(this.lower, this.includeLower, newUpper, newIncludeUpper);
        }

        private Interval intersect(Interval other) {
            Double nl;
            boolean nli;
            if (this.lower == null) {
                nl = other.lower;
                nli = other.includeLower;
            } else if (other.lower == null) {
                nl = this.lower;
                nli = this.includeLower;
            } else {
                int cmp = Double.compare(this.lower, other.lower);
                if (cmp > 0) {
                    nl = this.lower;
                    nli = this.includeLower;
                } else if (cmp < 0) {
                    nl = other.lower;
                    nli = other.includeLower;
                } else {
                    nl = this.lower;
                    nli = this.includeLower && other.includeLower;
                }
            }

            Double nu;
            boolean nui;
            if (this.upper == null) {
                nu = other.upper;
                nui = other.includeUpper;
            } else if (other.upper == null) {
                nu = this.upper;
                nui = this.includeUpper;
            } else {
                int cmp = Double.compare(this.upper, other.upper);
                if (cmp < 0) {
                    nu = this.upper;
                    nui = this.includeUpper;
                } else if (cmp > 0) {
                    nu = other.upper;
                    nui = other.includeUpper;
                } else {
                    nu = this.upper;
                    nui = this.includeUpper && other.includeUpper;
                }
            }

            Interval out = new Interval(nl, nli, nu, nui);
            return out.isEmpty() ? null : out;
        }
    }
}

