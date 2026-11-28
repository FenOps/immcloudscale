package mailserver

import (
	"context"
	"net"
	"os"
	"strconv"
	"testing"

	"github.com/emersion/go-imap/client"
)

// Runs against a real IMAP server with a test mailbox (for example GreenMail):
// ICS_IMAP_TEST=host:port ICS_IMAP_USER=... ICS_IMAP_PASS=... go test ./internal/mailserver/
// It deletes every message whose Message-ID is in ICS_IMAP_DELETE (comma-free, one ID).
func TestDeleteOnARealServer(t *testing.T) {
	addr := os.Getenv("ICS_IMAP_TEST")
	if addr == "" {
		t.Skip("set ICS_IMAP_TEST to run against a real IMAP server")
	}
	host, p, _ := net.SplitHostPort(addr)
	port, _ := strconv.Atoi(p)
	a := Account{Host: host, Port: port, Security: "None", Login: os.Getenv("ICS_IMAP_USER"), Password: os.Getenv("ICS_IMAP_PASS")}
	ctx := context.Background()
	if err := Check(ctx, Account{Host: host, Port: port, Security: "None", Login: a.Login, Password: "wrong"}); err == nil {
		t.Fatal("a wrong password signed in")
	}
	if err := Check(ctx, a); err != nil {
		t.Fatal(err)
	}
	// ICS_IMAP_DELETE is a Message-ID in INBOX; ICS_IMAP_KEEP one that contains it (it must survive).
	id := os.Getenv("ICS_IMAP_DELETE")
	before := count(t, a)
	res, err := Delete(ctx, a, []Message{{Folder: "INBOX", MessageID: id}, {Folder: "INBOX", MessageID: "<not-there@example>"}, {Folder: "INBOX"},
		{Folder: "INBOX", MessageID: "<0008d1c156142de4.1795847262099.7@bichon>"}})
	if err != nil {
		t.Fatal(err)
	}
	if res[0] != nil || res[1] != nil || res[2] != ErrNoMessageID || res[3] != ErrNoMessageID {
		t.Fatalf("%v", res)
	}
	if after := count(t, a); after != before-1 {
		t.Fatalf("INBOX had %d, now %d", before, after)
	}
}

func count(t *testing.T, a Account) uint32 {
	c, err := client.Dial(net.JoinHostPort(a.Host, strconv.Itoa(a.Port)))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Logout()
	if err := c.Login(a.Login, a.Password); err != nil {
		t.Fatal(err)
	}
	st, err := c.Select("INBOX", true)
	if err != nil {
		t.Fatal(err)
	}
	return st.Messages
}
