package netinfo

import "testing"

func TestDefaultRouteIface(t *testing.T) {
	tbl := "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\n" +
		"docker0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\n" +
		"enp3s0\t00000000\t0101A8C0\t0003\t0\t0\t100\t00000000\n"
	if got := DefaultRouteIface(tbl); got != "enp3s0" {
		t.Fatal(got)
	}
}
