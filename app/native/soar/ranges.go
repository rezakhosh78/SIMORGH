package soar

// span is the half-open interval [lo, hi).
type span struct{ lo, hi uint64 }

// rangeSet is a sorted set of disjoint, non-adjacent spans.
type rangeSet []span

func (s *rangeSet) add(lo, hi uint64) {
	if lo >= hi {
		return
	}
	rs := *s
	// First span that could touch [lo, hi).
	i := 0
	for i < len(rs) && rs[i].hi < lo {
		i++
	}
	j := i
	for j < len(rs) && rs[j].lo <= hi {
		lo = min(lo, rs[j].lo)
		hi = max(hi, rs[j].hi)
		j++
	}
	if i == j {
		rs = append(rs, span{})
		copy(rs[i+1:], rs[i:])
		rs[i] = span{lo, hi}
	} else {
		rs[i] = span{lo, hi}
		rs = append(rs[:i+1], rs[j:]...)
	}
	*s = rs
}

func (s *rangeSet) remove(lo, hi uint64) {
	if lo >= hi {
		return
	}
	var out rangeSet
	for _, r := range *s {
		if r.hi <= lo || r.lo >= hi {
			out = append(out, r)
			continue
		}
		if r.lo < lo {
			out = append(out, span{r.lo, lo})
		}
		if r.hi > hi {
			out = append(out, span{hi, r.hi})
		}
	}
	*s = out
}

func (s rangeSet) contains(v uint64) bool {
	for _, r := range s {
		if v < r.lo {
			return false
		}
		if v < r.hi {
			return true
		}
	}
	return false
}

// coveredFrom returns the end of the span containing v, or v if none does.
func (s rangeSet) coveredFrom(v uint64) uint64 {
	for _, r := range s {
		if r.lo <= v && v < r.hi {
			return r.hi
		}
	}
	return v
}

func (s rangeSet) empty() bool { return len(s) == 0 }

// trimBelow drops everything below v.
func (s *rangeSet) trimBelow(v uint64) {
	if v > 0 {
		s.remove(0, v)
	}
}
