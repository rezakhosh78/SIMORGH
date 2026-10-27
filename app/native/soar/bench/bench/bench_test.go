package bench

import "testing"

func TestBestResolver(t *testing.T) {
	want := map[string]int{"truncating": 1, "deadmix": 1, "iranlike": 2, "russialike": 2, "clean": 0}
	for _, sc := range Scenarios {
		w, ok := want[sc.Name]
		if !ok {
			continue
		}
		if got := BestResolver(sc.Resolvers); got != w {
			t.Errorf("%s: best resolver %d, want %d", sc.Name, got, w)
		}
	}
}
