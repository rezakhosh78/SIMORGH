package contenders

import (
	"context"
	"io"
	"testing"
	"time"

	"github.com/getlantern/soar/bench/bench"
)

func TestSoarCleanRun(t *testing.T) {
	r := bench.Run(context.Background(), &Soar{}, bench.Scenarios[0], 1, 30*time.Second, io.Discard)
	if r.ColdErr != "" || r.SmallFail+r.MediumFail > 0 || len(r.Medium) != 1 {
		t.Fatalf("clean run: cold=%q fails=%d/%d medium=%d", r.ColdErr, r.SmallFail, r.MediumFail, len(r.Medium))
	}
}
