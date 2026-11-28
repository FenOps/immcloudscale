package bichon

import (
	"context"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
)

// Runs against a real Bichon with one IMAP account that has a message with an attachment:
// ICS_BICHON_TEST=http://127.0.0.1:15630 ICS_BICHON_USER=admin ICS_BICHON_PASS=... go test ./internal/bichon/
func TestAgainstARealBichon(t *testing.T) {
	url := os.Getenv("ICS_BICHON_TEST")
	if url == "" {
		t.Skip("set ICS_BICHON_TEST to run against a real Bichon")
	}
	ctx := context.Background()
	c := New(url, "")
	sess, err := c.Login(ctx, os.Getenv("ICS_BICHON_USER"), os.Getenv("ICS_BICHON_PASS"))
	if err != nil {
		t.Fatal(err)
	}
	c.Token = sess
	tok, err := c.CreateToken(ctx, "test")
	if err != nil || tok == "" {
		t.Fatal(tok, err)
	}
	c.Token = tok
	if err := c.Check(ctx); err != nil {
		t.Fatal(err)
	}
	accts, err := c.Accounts(ctx)
	if err != nil || len(accts) == 0 {
		t.Fatal(accts, err)
	}
	a, err := c.Account(ctx, accts[0].ID)
	if err != nil || a.Imap == nil || a.Imap.Host == "" || a.Imap.Encryption == "" {
		t.Fatalf("%+v %v", a, err)
	}
	boxes, err := c.Mailboxes(ctx, a.ID)
	if err != nil || len(boxes) == 0 {
		t.Fatal(boxes, err)
	}
	st, err := c.Stats(ctx, a.ID)
	if err != nil || st.Count == 0 || st.LastSync.IsZero() {
		t.Fatalf("%+v %v", st, err)
	}
	p, err := c.Search(ctx, Search{Accounts: []uint64{a.ID}, HasAttachment: true, Size: 10})
	if err != nil || len(p.Items) == 0 {
		t.Fatal(p, err)
	}
	e, err := c.Envelope(ctx, a.ID, p.Items[0].ID)
	if err != nil || e.MessageID == "" || e.MailboxName == "" {
		t.Fatalf("%+v %v", e, err)
	}
	m, err := c.Content(ctx, a.ID, e.ID, true)
	if err != nil || len(m.Attachments) == 0 {
		t.Fatalf("%+v %v", m, err)
	}
	body, _, err := c.Attachment(ctx, a.ID, e.ID, m.Attachments[0].Hash)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(body)
	body.Close()
	if int64(len(b)) != m.Attachments[0].Size {
		t.Fatalf("attachment %d bytes, expected %d", len(b), m.Attachments[0].Size)
	}
	if p, err := c.Search(ctx, Search{Text: "zzzz-nothing-matches", Accounts: []uint64{a.ID}}); err != nil || p.Total != 0 {
		t.Fatal(p, err)
	}
	if _, err := c.Envelope(ctx, a.ID, "no-such-id"); err == nil {
		t.Fatal("a missing message was found")
	}
}

func TestSyncProblemsAreShort(t *testing.T) {
	gmail := `Failed to connect to or list the IMAP server's mailboxes: Generic { message: "No(\n \"code: None, info: Some(\\\"[AUTHENTICATIONFAILED] Invalid credentials (Failure)\\\")\",\n)", code: ImapAuthenticationFailed }`
	if got := syncProblem(gmail); got != "the mail server refused the password (Gmail, Yahoo and iCloud need an app password)" {
		t.Fatal(got)
	}
	for in, want := range map[string]string{
		"plain": "plain",
		"dns error: failed to lookup address information": "couldn't find the mail server: check its address",
		"Connection refused (os error 111)":               "the mail server refused the connection: check its address and port",
		"index lookup failed":                             "index lookup failed",
		strings.Repeat("é", 200):                          strings.Repeat("é", 160) + "…",
	} {
		if got := syncProblem(in); got != want {
			t.Errorf("%q: got %q", in, got)
		}
	}
}

func TestBichonsErrorsAreShortened(t *testing.T) {
	for in, want := range map[string]string{
		`Internal Server Error: Generic { message: "Valid access token not found", location: Location { file: "x.rs" } }`:                                                        "Valid access token not found",
		`Internal Server Error: Generic { message: "Generic {\n    message: \"Invalid access token provided. Please check your credentials.\",\n    location: Location {` + "\n": "Invalid access token provided. Please check your credentials.",
		"plain message\nmore": "plain message",
	} {
		if got := readable(in); got != want {
			t.Errorf("%q: got %q", in, got)
		}
	}
}

// Adds, changes and removes a mailbox on a real Bichon, with a test IMAP server:
// also set ICS_BICHON_IMAP=host:port ICS_BICHON_IMAP_USER=... ICS_BICHON_IMAP_PASS=...
func TestAddingAMailboxOnARealBichon(t *testing.T) {
	url, imapAddr := os.Getenv("ICS_BICHON_TEST"), os.Getenv("ICS_BICHON_IMAP")
	if url == "" || imapAddr == "" {
		t.Skip("set ICS_BICHON_TEST and ICS_BICHON_IMAP to run against a real Bichon")
	}
	ctx := context.Background()
	c := New(url, "")
	sess, err := c.Login(ctx, os.Getenv("ICS_BICHON_USER"), os.Getenv("ICS_BICHON_PASS"))
	if err != nil {
		t.Fatal(err)
	}
	c.Token = sess
	host, port, _ := net.SplitHostPort(imapAddr)
	p, _ := strconv.Atoi(port)
	m := IMAP{Host: host, Port: p, Security: "None", Login: os.Getenv("ICS_BICHON_IMAP_USER"), Password: os.Getenv("ICS_BICHON_IMAP_PASS")}
	a, err := c.AddAccount(ctx, "added-by-test@example.org", "test", m)
	if err != nil {
		t.Fatal(err)
	}
	defer c.DeleteAccount(context.Background(), a.ID)
	m.Password += "-changed"
	if err := c.SetPassword(ctx, a.ID, m); err != nil {
		t.Fatal(err)
	}
	if err := c.SetFolders(ctx, a.ID, []string{"INBOX"}); err != nil {
		t.Fatal(err)
	}
	if err := c.SyncNow(ctx, a.ID); err != nil {
		t.Fatal(err)
	}
}
