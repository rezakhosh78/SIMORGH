package main

import (
	"net/netip"
	"testing"
)

func TestSplitCoversExactly(t *testing.T) {
	for _, tc := range []struct {
		start string
		count uint32
		want  []string
	}{
		{"10.0.0.0", 256, []string{"10.0.0.0/24"}},
		{"10.0.0.0", 768, []string{"10.0.0.0/23", "10.0.2.0/24"}},
		{"10.0.1.0", 512, []string{"10.0.1.0/24", "10.0.2.0/24"}},
	} {
		var got []string
		for _, p := range split(netip.MustParseAddr(tc.start), tc.count) {
			got = append(got, p.String())
		}
		if len(got) != len(tc.want) {
			t.Fatalf("%s+%d: got %v want %v", tc.start, tc.count, got, tc.want)
		}
		for i := range got {
			if got[i] != tc.want[i] {
				t.Fatalf("%s+%d: got %v want %v", tc.start, tc.count, got, tc.want)
			}
		}
	}
}
