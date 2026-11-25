package wg

import (
	"strings"
	"testing"
	"time"
)

func TestPublicKeyMatchesWireGuardReference(t *testing.T) {
	// Example key pair from the WireGuard documentation / wg-quick(8).
	pub, err := PublicKey("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=")
	if err != nil || pub != "HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=" {
		t.Fatalf("got %q, %v", pub, err)
	}
	priv, pub2, err := NewKeyPair()
	if err != nil || !ValidKey(priv) || !ValidKey(pub2) {
		t.Fatal("bad generated keys")
	}
	if again, _ := PublicKey(priv); again != pub2 {
		t.Fatal("public key mismatch")
	}
}

func TestNextAddressSkipsServerAndUsed(t *testing.T) {
	a, err := NextAddress("10.8.0.0/24", []string{"10.8.0.2", "10.8.0.3"})
	if err != nil || a != "10.8.0.4" {
		t.Fatalf("got %s %v", a, err)
	}
	if _, err := NextAddress("10.9.0.0/30", []string{"10.9.0.2"}); err == nil {
		t.Fatal("expected a full /30 to fail")
	}
}

func TestRender(t *testing.T) {
	_, pub1, _ := NewKeyPair()
	out, err := ServerConfig{Interface: "ics0", PrivateKey: "PRIV", Subnet: "10.8.0.0/24", Port: 51820, Egress: "eth0",
		Peers: []Peer{{Name: "camille / Pixel", PublicKey: pub1, Address: "10.8.0.2"}}}.Render()
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"Address = 10.8.0.1/24", "ListenPort = 51820", "MASQUERADE", "-o eth0", "# camille / Pixel", "PublicKey = " + pub1, "AllowedIPs = 10.8.0.2/32"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in\n%s", want, out)
		}
	}
	if got := ClientAllowedIPs("10.8.0.0/24", "192.168.1.0/24"); got != "10.8.0.0/24, 192.168.1.0/24" {
		t.Fatal(got)
	}
}

func TestParseDump(t *testing.T) {
	dump := "PRIV\tPUB\t51820\toff\n" +
		"PEER1\t(none)\t203.0.113.5:40000\t10.8.0.2/32\t1764000000\t1000\t2000\t25\n" +
		"PEER2\t(none)\t(none)\t10.8.0.3/32\t0\t0\t0\toff\n"
	ps := ParseDump(dump)
	if len(ps) != 2 || ps[0].Endpoint != "203.0.113.5:40000" || ps[0].RxBytes != 1000 || ps[0].TxBytes != 2000 {
		t.Fatalf("%+v", ps)
	}
	if !ps[0].LastHandshake.Equal(time.Unix(1764000000, 0)) || !ps[1].LastHandshake.IsZero() || ps[1].Endpoint != "" {
		t.Fatalf("%+v", ps)
	}
}

func TestRenderCannotBeInjected(t *testing.T) {
	_, pub, _ := NewKeyPair()
	out, err := ServerConfig{Interface: "ics0", PrivateKey: "PRIV", Subnet: "10.8.0.0/24", Port: 51820,
		Peers: []Peer{{Name: "x\n[Interface]\nPostUp = touch /tmp/pwned", PublicKey: pub, Address: "10.8.0.2"}}}.Render()
	if err != nil {
		t.Fatal(err)
	}
	sections := 0
	for _, line := range strings.Split(out, "\n") {
		if strings.HasPrefix(line, "[Interface]") {
			sections++
		}
		if strings.HasPrefix(line, "PostUp = touch") {
			t.Fatalf("a peer name injected a command:\n%s", out)
		}
	}
	if sections != 1 {
		t.Fatalf("a peer name broke out of its comment:\n%s", out)
	}
	if _, err := (ServerConfig{Interface: "ics0", PrivateKey: "P", Subnet: "10.8.0.0/24", Port: 1,
		Peers: []Peer{{Name: "x", PublicKey: "k\nPostUp=x", Address: "10.8.0.2"}}}).Render(); err == nil {
		t.Fatal("invalid peer key accepted")
	}
}
