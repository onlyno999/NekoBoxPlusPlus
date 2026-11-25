package device

import (
	"runtime"
	"runtime/debug"
)

// ConfigureBackgroundRuntime tunes memory use for the VPN process:
// a soft cap on the Go heap (the runtime GCs before passing it)
// with a lazier GC percent below the cap, so the core does not
// grow unbounded in the background. Ported from Nekobox-Next.
func ConfigureBackgroundRuntime() {
	debug.SetMemoryLimit(128 << 20)
	debug.SetGCPercent(150)
}

func NumUDPWorkers() int {
	numUDPWorkers := 4
	if num := runtime.GOMAXPROCS(0); num > numUDPWorkers {
		numUDPWorkers = num
	}
	return numUDPWorkers
}
