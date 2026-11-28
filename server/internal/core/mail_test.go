package core

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"

	"github.com/FenOps/immcloudscale/server/internal/mailserver"
	"github.com/FenOps/immcloudscale/server/internal/state"
)

// fakeBichon mimics the parts of Bichon's API the server uses.
type fakeBichon struct {
	mu        sync.Mutex
	nextID    uint64
	accounts  map[uint64]map[string]any // id -> account as created
	folders   map[uint64][]string       // id -> sync_folders set
	enabled   map[uint64]bool
	messages  map[uint64][]string // account -> message ids
	searched  [][]uint64          // account_ids of each search
	deleted   map[uint64][]string
	token     string
	adminPass string
	// The mail servers: who signed in, what was deleted there, and which messages they refuse.
	signedIn   []mailserver.Account
	onServer   []mailserver.Message
	serverDown bool
	refuse     map[string]bool // Message-IDs
}

func withBichon(t *testing.T, s *Server) *fakeBichon {
	t.Helper()
	f := &fakeBichon{nextID: 1000, accounts: map[uint64]map[string]any{}, folders: map[uint64][]string{}, enabled: map[uint64]bool{},
		messages: map[uint64][]string{}, deleted: map[uint64][]string{}, token: "api-token", refuse: map[string]bool{}, adminPass: "bichon-pass"}
	s.MailCheck = func(_ context.Context, a mailserver.Account) error {
		f.mu.Lock()
		defer f.mu.Unlock()
		if a.Password == "wrong" {
			return errors.New("signing in: LOGIN failed")
		}
		f.signedIn = append(f.signedIn, a)
		return nil
	}
	s.MailDelete = func(_ context.Context, a mailserver.Account, msgs []mailserver.Message) ([]error, error) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.serverDown {
			return nil, errors.New("connecting: timeout")
		}
		out := make([]error, len(msgs))
		for i, m := range msgs {
			if f.refuse[m.MessageID] {
				out[i] = errors.New("moving it to Trash: NO")
				continue
			}
			f.onServer = append(f.onServer, m)
		}
		return out, nil
	}
	mux := http.NewServeMux()
	authed := func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("Authorization") != "Bearer "+f.token && r.Header.Get("Authorization") != "Bearer session" {
				http.Error(w, `{"code":70010,"message":"Valid access token not found"}`, 401)
				return
			}
			f.mu.Lock()
			defer f.mu.Unlock()
			next(w, r)
		}
	}
	mux.HandleFunc("POST /api/login", func(w http.ResponseWriter, r *http.Request) {
		var in map[string]string
		json.NewDecoder(r.Body).Decode(&in)
		f.mu.Lock()
		pass := f.adminPass
		f.mu.Unlock()
		if in["password"] != pass {
			io.WriteString(w, `{"success":false,"error_message":"Invalid username or password"}`)
			return
		}
		io.WriteString(w, `{"success":true,"access_token":"session","mfa_required":false}`)
	})
	mux.HandleFunc("POST /api/v1/access-token", authed(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, f.token) }))
	mux.HandleFunc("GET /api/v1/current-user", authed(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, `{"id":1}`) }))
	mux.HandleFunc("POST /api/v1/users/1", authed(func(w http.ResponseWriter, r *http.Request) {
		var in map[string]string
		json.NewDecoder(r.Body).Decode(&in)
		f.adminPass = in["password"]
	}))
	mux.HandleFunc("GET /api/v1/accounts", authed(func(w http.ResponseWriter, r *http.Request) {
		items := []map[string]any{}
		for id, a := range f.accounts {
			items = append(items, map[string]any{"id": id, "email": a["email"], "enabled": f.enabled[id]})
		}
		json.NewEncoder(w).Encode(map[string]any{"items": items, "total_items": len(items)})
	}))
	mux.HandleFunc("POST /api/v1/account", authed(func(w http.ResponseWriter, r *http.Request) {
		var a map[string]any
		json.NewDecoder(r.Body).Decode(&a)
		if a["account_type"] == "IMAP" && a["download_interval_min"] == nil && a["download_schedule"] == nil {
			http.Error(w, `{"code":10000,"message":"`+"`sync_interval_min` or `download_schedule` is required for IMAP account type"+`"}`, 400)
			return
		}
		f.nextID++
		f.accounts[f.nextID] = a
		f.enabled[f.nextID] = true
		json.NewEncoder(w).Encode(map[string]any{"id": f.nextID, "email": a["email"], "enabled": true})
	}))
	mux.HandleFunc("POST /api/v1/account/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.ParseUint(r.PathValue("id"), 10, 64)
		var a map[string]any
		json.NewDecoder(r.Body).Decode(&a)
		if v, ok := a["enabled"].(bool); ok {
			f.enabled[id] = v
		}
		if v, ok := a["imap"].(map[string]any); ok {
			f.accounts[id]["imap"] = v
		}
		if v, ok := a["sync_folders"].([]any); ok {
			f.folders[id] = nil
			for _, x := range v {
				f.folders[id] = append(f.folders[id], x.(string))
			}
		}
		io.WriteString(w, `{}`)
	}))
	mux.HandleFunc("GET /api/v1/account/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.ParseUint(r.PathValue("id"), 10, 64)
		json.NewEncoder(w).Encode(map[string]any{"id": id, "email": f.accounts[id]["email"], "login_name": "",
			"imap": map[string]any{"host": "mail.family.example", "port": 993, "encryption": "Ssl"}})
	}))
	mux.HandleFunc("DELETE /api/v1/account/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.ParseUint(r.PathValue("id"), 10, 64)
		delete(f.accounts, id)
		io.WriteString(w, `{}`)
	}))
	mux.HandleFunc("GET /api/v1/list-mailboxes/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.ParseUint(r.PathValue("id"), 10, 64)
		if strings.HasSuffix(f.accounts[id]["email"].(string), "@gmail.com") {
			io.WriteString(w, `{"mailboxes":[{"id":1,"name":"INBOX","attributes":[]},{"id":2,"name":"[Gmail]/All Mail","attributes":["All"]},{"id":3,"name":"[Gmail]/Spam","attributes":["Junk"]}]}`)
			return
		}
		io.WriteString(w, `{"mailboxes":[{"id":1,"name":"INBOX","attributes":[]},{"id":2,"name":"INBOX.Sent","attributes":[]},{"id":3,"name":"INBOX.Trash","attributes":[]},{"id":4,"name":"Spam","attributes":[]}]}`)
	}))
	mux.HandleFunc("GET /api/v1/accounts/{id}/stats", authed(func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.ParseUint(r.PathValue("id"), 10, 64)
		json.NewEncoder(w).Encode(map[string]any{"total_count": len(f.messages[id]), "total_size": 100})
	}))
	mux.HandleFunc("GET /api/v1/accounts/{id}/download-stats", authed(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"active_session":null,"history":[{"end_time":1795847262104,"status":"Success"}]}`)
	}))
	mux.HandleFunc("POST /api/v1/search-messages", authed(func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Filter struct {
				AccountIDs []uint64 `json:"account_ids"`
			} `json:"filter"`
		}
		json.NewDecoder(r.Body).Decode(&in)
		f.searched = append(f.searched, in.Filter.AccountIDs)
		items := []map[string]any{}
		for _, a := range in.Filter.AccountIDs {
			for _, m := range f.messages[a] {
				items = append(items, map[string]any{"id": m, "account_id": a, "subject": "About " + m, "date": 0, "internal_date": 1795847255000})
			}
		}
		json.NewEncoder(w).Encode(map[string]any{"items": items, "total_items": len(items)})
	}))
	mux.HandleFunc("GET /api/v1/envelope/{account}/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		a, _ := strconv.ParseUint(r.PathValue("account"), 10, 64)
		if strings.HasPrefix(r.PathValue("id"), "other-") {
			a = 999 // a message that belongs to another mailbox
		}
		json.NewEncoder(w).Encode(map[string]any{"id": r.PathValue("id"), "account_id": a, "subject": "About " + r.PathValue("id"), "date": 1795847255000,
			"message_id": "<" + r.PathValue("id") + "@mail.example>", "mailbox_name": "[Gmail]/All Mail"})
	}))
	mux.HandleFunc("GET /api/v1/message-content/{account}/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		html := `<p>Hi <img src="https://tracker.example/p.gif"></p>`
		if r.URL.Query().Get("block_remote_content") == "true" {
			html = `<p>Hi <img></p>`
		}
		json.NewEncoder(w).Encode(map[string]any{"html": html, "has_remote_content": true,
			"attachments": []map[string]any{{"filename": "tax.pdf", "file_type": "application/pdf", "size": 3, "content_hash": "h1"}}})
	}))
	mux.HandleFunc("GET /api/v1/download-attachment/{account}/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "PDF")
	}))
	mux.HandleFunc("POST /api/v1/delete-messages", authed(func(w http.ResponseWriter, r *http.Request) {
		var in map[string][]string
		json.NewDecoder(r.Body).Decode(&in)
		for k, v := range in {
			a, _ := strconv.ParseUint(k, 10, 64)
			f.deleted[a] = append(f.deleted[a], v...)
		}
	}))
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	t.Cleanup(s.bg.Wait) // background folder setup finishes before the fake goes away
	if _, err := s.ConnectMail(context.Background(), srv.URL, "", "admin", "bichon-pass"); err != nil {
		t.Fatal(err)
	}
	return f
}

func addPerson(t *testing.T, s *Server, name string) {
	t.Helper()
	if _, err := s.AddUser(context.Background(), name, name+"@example.com", false); err != nil {
		t.Fatal(err)
	}
}

func TestConnectingKeepsATokenNotThePassword(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	c := s.Store.Read().Config
	if c.MailToken != f.token || strings.Contains(fmtState(t, s), "bichon-pass") {
		t.Fatalf("token %q", c.MailToken)
	}
	if _, err := s.ConnectMail(context.Background(), c.MailURL, "", "admin", "wrong"); err == nil {
		t.Fatal("a wrong password connected")
	}
}

func fmtState(t *testing.T, s *Server) string {
	b, err := json.Marshal(s.Store.Read())
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestAGmailMailboxIsSetUpWithItsAllMailFolder(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	a, err := s.AddMail(context.Background(), "camille", NewMailbox{Email: "Camille <Camille.D@gmail.com>", Password: "abcd efgh ijkl mnop"})
	if err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	acc := f.accounts[a.ID]
	imap := acc["imap"].(map[string]any)
	f.mu.Unlock()
	if acc["email"] != "camille.d@gmail.com" || imap["host"] != "imap.gmail.com" || imap["port"] != float64(993) || imap["encryption"] != "Ssl" {
		t.Fatalf("%+v", acc)
	}
	s.chooseFolders(context.Background(), a.ID)
	f.mu.Lock()
	defer f.mu.Unlock()
	if strings.Join(f.folders[a.ID], ",") != "[Gmail]/All Mail" {
		t.Fatalf("folders %v", f.folders[a.ID])
	}
	if m := s.Store.Read().Mail; len(m) != 1 || m[0].Person != "camille" || !m[0].FoldersChosen {
		t.Fatalf("%+v", m)
	}
}

func TestOtherMailboxesSkipSpamAndTrash(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	a, err := s.AddMail(context.Background(), "camille", NewMailbox{Email: "me@family.example", Password: "pw", Host: "mail.awardspace.example", Port: 993})
	if err != nil {
		t.Fatal(err)
	}
	s.chooseFolders(context.Background(), a.ID)
	f.mu.Lock()
	defer f.mu.Unlock()
	if strings.Join(f.folders[a.ID], ",") != "INBOX,INBOX.Sent" {
		t.Fatalf("folders %v", f.folders[a.ID])
	}
	if _, err := s.AddMail(context.Background(), "camille", NewMailbox{Email: "x@unknown.example", Password: "pw"}); err == nil {
		t.Fatal("an unknown provider needs its server address")
	}
}

func TestEveryoneOnlySeesTheirOwnMail(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	addPerson(t, s, "nina")
	ctx := context.Background()
	mine, _ := s.AddMail(ctx, "camille", NewMailbox{Email: "camille@gmail.com", Password: "pw"})
	hers, _ := s.AddMail(ctx, "nina", NewMailbox{Email: "nina@gmail.com", Password: "pw"})
	f.mu.Lock()
	f.messages[mine.ID] = []string{"m1"}
	f.messages[hers.ID] = []string{"n1", "n2"}
	f.mu.Unlock()

	p, err := s.SearchMail(ctx, "nina", MailQuery{Text: "tax"})
	if err != nil || p.Total != 2 || p.Items[0].Account != hers.ID {
		t.Fatalf("%+v %v", p, err)
	}
	if p.Items[0].Date.IsZero() {
		t.Fatal("a message without a Date header shows when it arrived")
	}
	f.mu.Lock()
	last := f.searched[len(f.searched)-1]
	f.mu.Unlock()
	if len(last) != 1 || last[0] != hers.ID {
		t.Fatalf("searched %v", last)
	}
	if _, err := s.SearchMail(ctx, "nina", MailQuery{Account: mine.ID}); !errors.Is(err, ErrNotYourMail) {
		t.Fatalf("searched someone else's mailbox: %v", err)
	}
	if _, err := s.ReadMail(ctx, "nina", mine.ID, "m1", false); !errors.Is(err, ErrNotYourMail) {
		t.Fatalf("read someone else's mail: %v", err)
	}
	if _, _, err := s.MailAttachment(ctx, "nina", mine.ID, "m1", "h1"); !errors.Is(err, ErrNotYourMail) {
		t.Fatalf("got someone else's attachment: %v", err)
	}
	if _, err := s.DeleteMail(ctx, "nina", map[uint64][]string{hers.ID: {"n1"}, mine.ID: {"m1"}}); !errors.Is(err, ErrNotYourMail) {
		t.Fatalf("deleted someone else's mail: %v", err)
	}
	if err := s.RemoveMail(ctx, "nina", mine.ID); !errors.Is(err, ErrNotYourMail) {
		t.Fatalf("removed someone else's mailbox: %v", err)
	}
	if st := s.MailStatus(ctx, "nina", false); len(st.Accounts) != 1 || st.Accounts[0].Email != "nina@gmail.com" || len(st.Unassigned) != 0 {
		t.Fatalf("%+v", st)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.deleted) != 0 || f.accounts[mine.ID] == nil {
		t.Fatal("something of camille's was deleted")
	}
}

func TestReadingBlocksTrackingImagesUnlessAsked(t *testing.T) {
	s, _, _ := newTestServer(t)
	withBichon(t, s)
	ctx := context.Background()
	a, _ := s.AddMail(ctx, "camille", NewMailbox{Email: "camille@gmail.com", Password: "pw"})
	m, err := s.ReadMail(ctx, "camille", a.ID, "m1", false)
	if err != nil || strings.Contains(m.HTML, "tracker") || !m.HasRemote || len(m.Attachments) != 1 || m.Attachments[0].Name != "tax.pdf" {
		t.Fatalf("%+v %v", m, err)
	}
	if m, _ = s.ReadMail(ctx, "camille", a.ID, "m1", true); !strings.Contains(m.HTML, "tracker") {
		t.Fatal("asked for images but didn't get them")
	}
	body, _, err := s.MailAttachment(ctx, "camille", a.ID, "m1", "h1")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(body)
	body.Close()
	if string(b) != "PDF" {
		t.Fatalf("%q", b)
	}
}

func TestDeletingRemovesFromTheMailServerThenTheArchive(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	ctx := context.Background()
	a, _ := s.AddMail(ctx, "camille", NewMailbox{Email: "camille@gmail.com", Password: "app pass"})
	f.mu.Lock()
	f.refuse["<m3@mail.example>"] = true
	f.mu.Unlock()
	res, err := s.DeleteMail(ctx, "camille", map[uint64][]string{a.ID: {"m1", "m2", "m3"}})
	if err != nil || res.Deleted != 2 || len(res.Failed) != 1 || res.Failed[0].ID != "m3" {
		t.Fatalf("%+v %v", res, err)
	}
	f.mu.Lock()
	if strings.Join(f.deleted[a.ID], ",") != "m1,m2" || len(f.onServer) != 2 ||
		f.onServer[0].MessageID != "<m1@mail.example>" || f.onServer[0].Folder != "[Gmail]/All Mail" {
		t.Fatalf("archive %v, server %+v", f.deleted, f.onServer)
	}
	// The mail server can't be reached: nothing is taken out of the archive either.
	f.serverDown = true
	f.mu.Unlock()
	res, err = s.DeleteMail(ctx, "camille", map[uint64][]string{a.ID: {"m4"}})
	if err != nil || res.Deleted != 0 || len(res.Failed) != 1 || !strings.Contains(res.Failed[0].Error, "timeout") {
		t.Fatalf("%+v %v", res, err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.deleted[a.ID]) != 2 {
		t.Fatalf("deleted from the archive while the mail server still has it: %v", f.deleted)
	}
}

func TestAWrongPasswordIsCaughtWhenAdding(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	if _, err := s.AddMail(context.Background(), "camille", NewMailbox{Email: "camille@yahoo.com", Password: "wrong"}); err == nil || !strings.Contains(err.Error(), "app password") {
		t.Fatal(err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.accounts) != 0 {
		t.Fatal("added to the archive with a wrong password")
	}
	if m := s.Store.Read().Mail; len(m) != 0 {
		t.Fatal(m)
	}
}

func TestAnyMailServerWorks(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	_, err := s.AddMail(context.Background(), "camille", NewMailbox{Email: "me@own-domain.example", Password: "pw",
		Host: "imap.own-domain.example", Port: 143, Security: "starttls", Login: "me"})
	if err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	got := f.signedIn[len(f.signedIn)-1]
	if got.Host != "imap.own-domain.example" || got.Port != 143 || got.Security != "StartTls" || got.Login != "me" {
		t.Fatalf("%+v", got)
	}
}

func TestAdminsGiveOutMailboxesAddedInBichon(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	addPerson(t, s, "nina")
	ctx := context.Background()
	f.mu.Lock()
	f.accounts[77] = map[string]any{"email": "family@example.org"}
	f.mu.Unlock()
	if st := s.MailStatus(ctx, "nina", false); len(st.Unassigned) != 0 {
		t.Fatal("a member saw mailboxes that aren't theirs")
	}
	st := s.MailStatus(ctx, "camille", true)
	if len(st.Unassigned) != 1 || st.Unassigned[0].ID != 77 {
		t.Fatalf("%+v", st)
	}
	if err := s.AssignMail(ctx, 77, "nina"); err != nil {
		t.Fatal(err)
	}
	if st := s.MailStatus(ctx, "nina", false); len(st.Accounts) != 1 || st.Accounts[0].DeletesOnServer {
		t.Fatalf("%+v", st)
	}
	// Its password was given to Bichon only: deleting needs it once.
	if _, err := s.DeleteMail(ctx, "nina", map[uint64][]string{77: {"x"}}); !errors.Is(err, ErrNeedMailPassword) {
		t.Fatal(err)
	}
	if err := s.SetMailPassword(ctx, "nina", 77, "wrong"); err == nil {
		t.Fatal("a wrong password was saved")
	}
	if err := s.SetMailPassword(ctx, "nina", 77, "right"); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	gotPass := f.accounts[77]["imap"].(map[string]any)["auth"].(map[string]any)["password"]
	f.mu.Unlock()
	if gotPass != "right" {
		t.Fatalf("Bichon still copies with the old password: %v", gotPass)
	}
	if st := s.MailStatus(ctx, "nina", false); !st.Accounts[0].DeletesOnServer {
		t.Fatalf("%+v", st)
	}
	if m := s.Store.Read().Mail[0]; m.IMAPHost != "mail.family.example" || m.IMAPLogin != "family@example.org" || m.IMAPSecurity != "Ssl" {
		t.Fatalf("%+v", m)
	}
	// Removing a person leaves their archive for an admin to decide about.
	if err := s.RemoveUser(ctx, "nina"); err != nil {
		t.Fatal(err)
	}
	if st := s.MailStatus(ctx, "camille", true); len(st.Unassigned) != 1 || len(s.Store.Read().Mail) != 0 {
		t.Fatalf("%+v", st)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.accounts[77] == nil {
		t.Fatal("their archive was deleted with them")
	}
}

func TestMailIsOffUntilConnected(t *testing.T) {
	s, _, _ := newTestServer(t)
	if st := s.MailStatus(context.Background(), "camille", true); st.Configured {
		t.Fatal("configured without Bichon")
	}
	if _, err := s.SearchMail(context.Background(), "camille", MailQuery{}); !errors.Is(err, ErrMailNotSetUp) {
		t.Fatal(err)
	}
	_ = state.MailAccount{}
}

func TestAMessageFromSomeoneElsesMailboxIsRefused(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	ctx := context.Background()
	a, _ := s.AddMail(ctx, "camille", NewMailbox{Email: "camille@gmail.com", Password: "pw"})
	if _, err := s.ReadMail(ctx, "camille", a.ID, "other-1", false); !errors.Is(err, ErrNotYourMail) {
		t.Fatal(err)
	}
	if _, _, err := s.MailAttachment(ctx, "camille", a.ID, "other-1", "h1"); !errors.Is(err, ErrNotYourMail) {
		t.Fatal(err)
	}
	if _, _, err := s.MailAttachment(ctx, "camille", a.ID, "m1", "not-in-this-message"); err == nil {
		t.Fatal("an attachment hash from elsewhere was served")
	}
	if _, err := s.DeleteMail(ctx, "camille", map[uint64][]string{a.ID: {"other-1"}}); !errors.Is(err, ErrNotYourMail) {
		t.Fatal(err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.deleted) != 0 || len(f.onServer) != 0 {
		t.Fatal("deleted someone else's message")
	}
}

func TestBichonsDefaultPasswordIsReplaced(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withBichon(t, s)
	f.mu.Lock()
	f.adminPass = "admin@bichon"
	f.mu.Unlock()
	pw, err := s.ConnectMail(context.Background(), s.Store.Read().Config.MailURL, "", "admin", "admin@bichon")
	if err != nil || len(pw) < 16 {
		t.Fatal(pw, err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.adminPass != pw {
		t.Fatal("the new password wasn't set")
	}
}

func TestDeletingIsLimitedPerRequest(t *testing.T) {
	s, _, _ := newTestServer(t)
	withBichon(t, s)
	a, _ := s.AddMail(context.Background(), "camille", NewMailbox{Email: "camille@gmail.com", Password: "pw"})
	ids := make([]string, MaxMailDelete+1)
	for i := range ids {
		ids[i] = strconv.Itoa(i)
	}
	if _, err := s.DeleteMail(context.Background(), "camille", map[uint64][]string{a.ID: ids}); err == nil {
		t.Fatal("no limit")
	}
}
