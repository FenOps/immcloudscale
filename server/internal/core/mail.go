package core

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/mail"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/bichon"
	"github.com/FenOps/immcloudscale/server/internal/mailserver"
	"github.com/FenOps/immcloudscale/server/internal/state"
)

// Email archives live in Bichon (installed next to this server). The server holds a Bichon API token and
// shows each person only the mailboxes that are theirs; the app never talks to Bichon itself.

var (
	ErrMailNotSetUp = errors.New("email archive isn't set up on the server yet: run `sudo ics-server mail connect` there")
	ErrNotYourMail  = errors.New("that mailbox isn't yours")
	// A mailbox added in Bichon's own page: the server doesn't have its password to delete there too.
	ErrNeedMailPassword = errors.New("to delete from the mail server too, enter this mailbox's password once (Mail, Mailboxes)")
)

func (s *Server) checkMailbox(ctx context.Context, a mailserver.Account) error {
	if s.MailCheck != nil {
		return s.MailCheck(ctx, a)
	}
	return mailserver.Check(ctx, a)
}

func (s *Server) deleteOnServer(ctx context.Context, a mailserver.Account, msgs []mailserver.Message) ([]error, error) {
	if s.MailDelete != nil {
		return s.MailDelete(ctx, a, msgs)
	}
	return mailserver.Delete(ctx, a, msgs)
}

func imapOf(m state.MailAccount) mailserver.Account {
	return mailserver.Account{Host: m.IMAPHost, Port: m.IMAPPort, Security: m.IMAPSecurity, Login: m.IMAPLogin, Password: m.IMAPPassword}
}

const DefaultMailURL = "http://127.0.0.1:15630"

func (s *Server) bichon(c state.Config) *bichon.Client { return bichon.New(c.MailURL, c.MailToken) }

// ConnectMail saves how to reach Bichon. With a user and password it signs in and makes an API token for
// the server (the password itself isn't kept); with a token it uses that. If the password was Bichon's
// default, it's replaced by a random one, returned so it can be shown once.
func (s *Server) ConnectMail(ctx context.Context, url, token, user, password string) (newPassword string, err error) {
	url = strings.TrimRight(strings.TrimSpace(url), "/")
	if url == "" {
		url = DefaultMailURL
	}
	b := bichon.New(url, "")
	if token == "" {
		sess, err := b.Login(ctx, user, password)
		if err != nil {
			return "", err
		}
		b.Token = sess
		if password == bichon.DefaultPassword {
			// Anyone who can reach Bichon knows the default: replace it first, then sign in again with the new one
			// (so the token below can't be one the change cancels).
			pw := state.Secret(18)
			if err := b.ChangeMyPassword(ctx, pw); err != nil {
				// The change may have gone through even so: show the new password in case it did.
				return pw, fmt.Errorf("changing Bichon's default password (it may have changed to the one shown; if not, the old one still works): %w", err)
			}
			newPassword = pw
			if b.Token, err = b.Login(ctx, user, pw); err != nil {
				return newPassword, err
			}
		}
		if token, err = b.CreateToken(ctx, "ImmCloudScale server"); err != nil {
			return newPassword, fmt.Errorf("creating a Bichon API token: %w", err)
		}
	}
	b.Token = token
	if err := b.Check(ctx); err != nil {
		return newPassword, fmt.Errorf("Bichon at %s doesn't accept the token: %w", url, err)
	}
	return newPassword, s.Store.Update(func(st *state.State) error {
		st.Config.MailURL, st.Config.MailToken = url, token
		return nil
	})
}

// MailAccountStatus is one archived mailbox as its owner sees it.
type MailAccountStatus struct {
	ID       uint64    `json:"id,string"`
	Email    string    `json:"email"`
	Person   string    `json:"person"`
	Count    int       `json:"count"`
	Bytes    int64     `json:"bytes"`
	Syncing  bool      `json:"syncing"`
	Paused   bool      `json:"paused"`
	LastSync time.Time `json:"lastSync,omitzero"`
	Error    string    `json:"error,omitempty"`
	// Whether the server can delete from the mailbox itself (it has its password).
	DeletesOnServer bool `json:"deletesOnServer"`
}

type MailStatus struct {
	Configured bool                `json:"configured"`
	Reachable  bool                `json:"reachable"`
	Accounts   []MailAccountStatus `json:"accounts"`
	// Admins only: mailboxes in Bichon that belong to nobody here yet (added in Bichon's own web page).
	Unassigned []MailAccountStatus `json:"unassigned,omitempty"`
}

// MailStatus lists [person]'s mailboxes; admins also see the ones not given to anyone.
func (s *Server) MailStatus(ctx context.Context, person string, admin bool) MailStatus {
	st := s.Store.Read()
	out := MailStatus{Configured: st.Config.MailToken != "", Accounts: []MailAccountStatus{}}
	if !out.Configured {
		return out
	}
	b := s.bichon(st.Config)
	all, err := b.Accounts(ctx)
	if err != nil {
		s.Log.Printf("mail: listing Bichon accounts: %v", err)
		return out
	}
	out.Reachable = true
	owner := map[uint64]string{}
	creds := map[uint64]bool{}
	for _, m := range st.Mail {
		owner[m.ID] = m.Person
		creds[m.ID] = m.IMAPPassword != ""
	}
	type job struct {
		a     bichon.Account
		owner string
		mine  bool
	}
	var jobs []job
	for _, a := range all {
		o, known := owner[a.ID]
		switch {
		case known && o == person:
			jobs = append(jobs, job{a, o, true})
		case !known && admin:
			jobs = append(jobs, job{a, "", false})
		}
	}
	results := make([]MailAccountStatus, len(jobs))
	var wg sync.WaitGroup
	for i, j := range jobs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r := MailAccountStatus{ID: j.a.ID, Email: j.a.Email, Person: j.owner, Paused: !j.a.Enabled, DeletesOnServer: creds[j.a.ID]}
			if stats, err := b.Stats(ctx, j.a.ID); err == nil {
				r.Count, r.Bytes, r.Syncing, r.LastSync, r.Error = stats.Count, stats.Bytes, stats.Syncing, stats.LastSync, stats.LastError
			}
			results[i] = r
		}()
	}
	wg.Wait()
	for i, j := range jobs {
		if j.mine {
			out.Accounts = append(out.Accounts, results[i])
		} else {
			out.Unassigned = append(out.Unassigned, results[i])
		}
	}
	return out
}

// NewMailbox is a mailbox to start archiving. Host may be left out for Gmail.
type NewMailbox struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Security string `json:"security"` // "ssl" (default), "starttls" or "none"
	Login    string `json:"login"`    // when it isn't the email address
	For      string `json:"for"`      // admins: whose mailbox it is
}

// mailServer fills in well-known providers' IMAP settings.
func mailServer(email string) (host string, port int) {
	_, domain, _ := strings.Cut(strings.ToLower(email), "@")
	switch domain {
	case "gmail.com", "googlemail.com":
		return "imap.gmail.com", 993
	case "yahoo.com", "ymail.com":
		return "imap.mail.yahoo.com", 993
	case "icloud.com", "me.com", "mac.com":
		return "imap.mail.me.com", 993
	}
	return "", 0
}

// AddMail starts archiving a mailbox for [owner].
func (s *Server) AddMail(ctx context.Context, owner string, in NewMailbox) (MailAccountStatus, error) {
	st := s.Store.Read()
	if st.Config.MailToken == "" {
		return MailAccountStatus{}, ErrMailNotSetUp
	}
	if st.FindUser(owner) == nil {
		return MailAccountStatus{}, fmt.Errorf("%w: %q", ErrNoPerson, owner)
	}
	addr, err := mail.ParseAddress(strings.TrimSpace(in.Email))
	if err != nil || !strings.Contains(addr.Address, "@") {
		return MailAccountStatus{}, fmt.Errorf("%q isn't an email address", in.Email)
	}
	email := strings.ToLower(addr.Address)
	if in.Password == "" {
		return MailAccountStatus{}, errors.New("the password (or app password) is missing")
	}
	m := bichon.IMAP{Host: strings.TrimSpace(in.Host), Port: in.Port, Login: strings.TrimSpace(in.Login), Password: in.Password}
	if _, d, _ := strings.Cut(email, "@"); d == "msn.com" || strings.HasPrefix(d, "outlook.") || strings.HasPrefix(d, "hotmail.") || strings.HasPrefix(d, "live.") {
		return MailAccountStatus{}, errors.New("Outlook.com and Hotmail no longer let other apps sign in with a password, so they can't be archived yet")
	}
	if m.Host == "" {
		if m.Host, m.Port = mailServer(email); m.Host == "" {
			return MailAccountStatus{}, errors.New("enter the mail server (IMAP) address: your email provider's settings page lists it")
		}
	}
	if m.Port == 0 {
		m.Port = 993
	}
	if m.Login == "" {
		m.Login = email
	}
	switch strings.ToLower(in.Security) {
	case "", "ssl", "tls":
		m.Security = "Ssl"
	case "starttls":
		m.Security = "StartTls"
	case "none":
		m.Security = "None"
	default:
		return MailAccountStatus{}, fmt.Errorf("unknown security %q (use ssl, starttls or none)", in.Security)
	}
	for _, x := range st.Mail {
		if strings.EqualFold(x.Email, email) {
			return MailAccountStatus{}, fmt.Errorf("%s is already archived (for %s)", email, x.Person)
		}
	}
	if all, err := s.bichon(st.Config).Accounts(ctx); err == nil {
		for _, x := range all {
			if strings.EqualFold(x.Email, email) {
				return MailAccountStatus{}, fmt.Errorf("%s is already in the archive: an admin can give it to you (Mail, Mailboxes)", email)
			}
		}
	}
	// Signing in first gives a clear answer now, rather than an archive that silently stays empty.
	acct := mailserver.Account{Host: m.Host, Port: m.Port, Security: m.Security, Login: m.Login, Password: m.Password}
	if err := s.checkMailbox(ctx, acct); err != nil {
		return MailAccountStatus{}, fmt.Errorf("%w (for Gmail, Yahoo and iCloud, use an app password)", err)
	}
	a, err := s.bichon(st.Config).AddAccount(ctx, email, email, m)
	if err != nil {
		return MailAccountStatus{}, fmt.Errorf("adding %s to the archive: %w", email, err)
	}
	if err := s.Store.Update(func(st *state.State) error {
		for _, x := range st.Mail {
			if strings.EqualFold(x.Email, email) {
				return fmt.Errorf("%s is already archived (for %s)", email, x.Person) // added twice at once
			}
		}
		st.Mail = append(st.Mail, state.MailAccount{ID: a.ID, Person: owner, Email: email, Added: s.Now(),
			IMAPHost: m.Host, IMAPPort: m.Port, IMAPSecurity: m.Security, IMAPLogin: m.Login, IMAPPassword: m.Password})
		return nil
	}); err != nil {
		_ = s.bichon(st.Config).DeleteAccount(context.WithoutCancel(ctx), a.ID)
		return MailAccountStatus{}, err
	}
	s.Log.Printf("mail: archiving %s for %s", email, owner)
	s.bg.Add(1)
	go func() {
		defer s.bg.Done()
		s.chooseFolders(context.WithoutCancel(ctx), a.ID)
	}()
	return MailAccountStatus{ID: a.ID, Email: email, Person: owner, Syncing: true, DeletesOnServer: true}, nil
}

// chooseFolders picks which folders of a new mailbox to keep: Gmail's "All Mail" (every label is in it,
// so nothing is stored twice), else every folder but spam, trash and drafts.
func (s *Server) chooseFolders(ctx context.Context, id uint64) {
	b := s.bichon(s.Store.Read().Config)
	for range 20 {
		boxes, err := b.Mailboxes(ctx, id)
		if err == nil && len(boxes) > 0 {
			if folders := keepFolders(boxes); len(folders) > 0 {
				if err := b.SetFolders(ctx, id, folders); err != nil {
					s.Log.Printf("mail: choosing folders for %d: %v", id, err)
				}
			}
			s.markFoldersChosen(id)
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(15 * time.Second):
		}
	}
	s.markFoldersChosen(id) // don't keep trying: it keeps Bichon's default (INBOX), which Bichon's page can change
	s.Log.Printf("mail: account %d didn't list its folders; it keeps Bichon's default folders (INBOX)", id)
}

func (s *Server) markFoldersChosen(id uint64) {
	_ = s.Store.Update(func(st *state.State) error {
		for i := range st.Mail {
			if st.Mail[i].ID == id {
				st.Mail[i].FoldersChosen = true
			}
		}
		return nil
	})
}

func keepFolders(boxes []bichon.Mailbox) []string {
	attr := func(b bichon.Mailbox, want string) bool {
		for _, a := range b.Attributes {
			if strings.Contains(strings.ToLower(fmt.Sprint(a)), want) {
				return true
			}
		}
		return false
	}
	for _, b := range boxes {
		if attr(b, "all") || strings.EqualFold(b.Name, "[Gmail]/All Mail") || strings.EqualFold(b.Name, "[Google Mail]/All Mail") {
			return []string{b.Name}
		}
	}
	var out []string
	for _, b := range boxes {
		n := strings.ToLower(b.Name)
		skip := attr(b, "junk") || attr(b, "trash") || attr(b, "drafts") || attr(b, "noselect")
		for _, w := range []string{"spam", "junk", "trash", "deleted", "bin", "drafts"} {
			if n == w || strings.HasSuffix(n, "/"+w) || strings.HasSuffix(n, "."+w) {
				skip = true
			}
		}
		if !skip {
			out = append(out, b.Name)
		}
	}
	return out
}

// ownsMail is nil when mailbox [id] is [person]'s, else ErrNotYourMail.
func (s *Server) ownsMail(person string, id uint64) error {
	for _, m := range s.Store.Read().Mail {
		if m.ID == id && m.Person == person {
			return nil
		}
	}
	return ErrNotYourMail
}

func (s *Server) mailIDs(person string) []uint64 {
	var ids []uint64
	for _, m := range s.Store.Read().Mail {
		if m.Person == person {
			ids = append(ids, m.ID)
		}
	}
	return ids
}

func (s *Server) mailClient() (*bichon.Client, error) {
	c := s.Store.Read().Config
	if c.MailToken == "" {
		return nil, ErrMailNotSetUp
	}
	return s.bichon(c), nil
}

// AssignMail gives a mailbox to [person] (admins: for mailboxes added in Bichon's own page, or to move one).
func (s *Server) AssignMail(ctx context.Context, id uint64, person string) error {
	b, err := s.mailClient()
	if err != nil {
		return err
	}
	all, err := b.Accounts(ctx)
	if err != nil {
		return err
	}
	i := slices.IndexFunc(all, func(a bichon.Account) bool { return a.ID == id })
	if i < 0 {
		return fmt.Errorf("no mailbox %d in the archive", id)
	}
	return s.Store.Update(func(st *state.State) error {
		if st.FindUser(person) == nil {
			return fmt.Errorf("%w: %q", ErrNoPerson, person)
		}
		for j := range st.Mail {
			if st.Mail[j].ID == id {
				st.Mail[j].Person = person
				return nil
			}
		}
		st.Mail = append(st.Mail, state.MailAccount{ID: id, Person: person, Email: all[i].Email, Added: s.Now(), FoldersChosen: true})
		return nil
	})
}

// PauseMail stops or restarts downloading [person]'s mailbox; what's archived stays.
func (s *Server) PauseMail(ctx context.Context, person string, id uint64, paused bool) error {
	if err := s.ownsMail(person, id); err != nil {
		return err
	}
	b, err := s.mailClient()
	if err != nil {
		return err
	}
	return b.SetEnabled(ctx, id, !paused)
}

// RemoveMail stops archiving [person]'s mailbox and deletes everything archived from it (the mailbox
// itself, at Gmail or elsewhere, is untouched).
func (s *Server) RemoveMail(ctx context.Context, person string, id uint64) error {
	if err := s.ownsMail(person, id); err != nil {
		return err
	}
	b, err := s.mailClient()
	if err != nil {
		return err
	}
	var ae *bichon.APIError
	if err := b.DeleteAccount(ctx, id); err != nil && !(errors.As(err, &ae) && ae.Status == 404) {
		return err
	}
	s.Log.Printf("mail: %s removed mailbox %d and its archive", person, id)
	return s.Store.Update(func(st *state.State) error {
		st.Mail = slices.DeleteFunc(st.Mail, func(m state.MailAccount) bool { return m.ID == id })
		return nil
	})
}

// MailQuery is a search through [person]'s own mail.
type MailQuery struct {
	Text          string `json:"text"`
	From          string `json:"from"`
	Account       uint64 `json:"account,string"` // 0: all their mailboxes
	HasAttachment bool   `json:"hasAttachment"`
	Since         int64  `json:"since"` // epoch millis
	Before        int64  `json:"before"`
	Page          int    `json:"page"`
}

// MailItem is one message in a list.
type MailItem struct {
	ID          string    `json:"id"`
	Account     uint64    `json:"account,string"`
	Mailbox     string    `json:"mailbox"`
	Folder      string    `json:"folder"`
	Subject     string    `json:"subject"`
	Preview     string    `json:"preview"`
	From        string    `json:"from"`
	FromName    string    `json:"fromName,omitempty"`
	To          []string  `json:"to"`
	Cc          []string  `json:"cc,omitempty"`
	Date        time.Time `json:"date"`
	Size        int64     `json:"size"`
	Attachments int       `json:"attachments"`
}

func mailItem(e bichon.Envelope) MailItem {
	d := e.Date
	if d <= 0 {
		d = e.InternalDate // no Date header: when the mail server got it
	}
	return MailItem{
		ID: e.ID, Account: e.AccountID, Mailbox: e.AccountEmail, Folder: e.MailboxName, Subject: e.Subject, Preview: e.Preview,
		From: e.From, FromName: e.FromName, To: e.To, Cc: e.Cc, Date: time.UnixMilli(d), Size: e.Size, Attachments: e.Attachments,
	}
}

type MailPage struct {
	Items []MailItem `json:"items"`
	Total int        `json:"total"`
	Page  int        `json:"page"`
	More  bool       `json:"more"`
}

const mailPageSize = 50

// SearchMail finds messages in [person]'s mailboxes only.
func (s *Server) SearchMail(ctx context.Context, person string, q MailQuery) (MailPage, error) {
	b, err := s.mailClient()
	if err != nil {
		return MailPage{}, err
	}
	ids := s.mailIDs(person)
	if q.Account != 0 {
		if err := s.ownsMail(person, q.Account); err != nil {
			return MailPage{}, err
		}
		ids = []uint64{q.Account}
	}
	page := max(q.Page, 1)
	out := MailPage{Items: []MailItem{}, Page: page}
	if len(ids) == 0 {
		return out, nil
	}
	sq := bichon.Search{Text: q.Text, From: q.From, Accounts: ids, HasAttachment: q.HasAttachment, Page: page, Size: mailPageSize}
	if q.Since > 0 {
		sq.Since = time.UnixMilli(q.Since)
	}
	if q.Before > 0 {
		sq.Before = time.UnixMilli(q.Before)
	}
	p, err := b.Search(ctx, sq)
	if err != nil {
		return MailPage{}, err
	}
	for _, e := range p.Items {
		out.Items = append(out.Items, mailItem(e))
	}
	out.Total, out.More = p.Total, page*mailPageSize < p.Total
	return out, nil
}

// MailMessage is a message to read: by default with anything from the web (tracking images) taken out.
type MailMessage struct {
	MailItem
	Text        string              `json:"text,omitempty"`
	HTML        string              `json:"html,omitempty"`
	HasRemote   bool                `json:"hasRemote"`
	Attachments []bichon.Attachment `json:"attachmentList"`
}

func (s *Server) ReadMail(ctx context.Context, person string, account uint64, id string, remote bool) (MailMessage, error) {
	if err := s.ownsMail(person, account); err != nil {
		return MailMessage{}, err
	}
	b, err := s.mailClient()
	if err != nil {
		return MailMessage{}, err
	}
	e, err := b.Envelope(ctx, account, id)
	if err != nil {
		return MailMessage{}, err
	}
	if e.AccountID != account {
		return MailMessage{}, ErrNotYourMail // a message id from someone else's mailbox
	}
	c, err := b.Content(ctx, account, id, !remote)
	if err != nil {
		return MailMessage{}, err
	}
	if c.Attachments == nil {
		c.Attachments = []bichon.Attachment{}
	}
	return MailMessage{MailItem: mailItem(e), Text: c.Text, HTML: c.HTML, HasRemote: c.HasRemote, Attachments: c.Attachments}, nil
}

// MailAttachment streams one attachment of [person]'s message; the caller closes it.
func (s *Server) MailAttachment(ctx context.Context, person string, account uint64, id, hash string) (io.ReadCloser, int64, error) {
	if err := s.ownsMail(person, account); err != nil {
		return nil, 0, err
	}
	b, err := s.mailClient()
	if err != nil {
		return nil, 0, err
	}
	if e, err := b.Envelope(ctx, account, id); err != nil {
		return nil, 0, err
	} else if e.AccountID != account {
		return nil, 0, ErrNotYourMail
	}
	c, err := b.Content(ctx, account, id, true)
	if err != nil {
		return nil, 0, err
	}
	i := slices.IndexFunc(c.Attachments, func(a bichon.Attachment) bool { return a.Hash == hash })
	if i < 0 {
		return nil, 0, &bichon.APIError{Status: 404, Message: "no such attachment in this message"}
	}
	body, n, err := b.Attachment(ctx, account, id, hash)
	if n <= 0 {
		// Bichon usually sends no length; with the real one, a download cut short shows as an error, not a shorter file.
		n = c.Attachments[i].Size
	}
	return body, n, err
}

// SetMailPassword saves a new password for [person]'s mailbox, both for copying (in Bichon) and for deleting on
// the mail server (here). The rest of the settings come from Bichon.
func (s *Server) SetMailPassword(ctx context.Context, person string, id uint64, password string) error {
	if err := s.ownsMail(person, id); err != nil {
		return err
	}
	if password == "" {
		return errors.New("the password is missing")
	}
	b, err := s.mailClient()
	if err != nil {
		return err
	}
	a, err := b.Account(ctx, id)
	if err != nil {
		return err
	}
	if a.Imap == nil || a.Imap.Host == "" {
		return errors.New("this mailbox has no IMAP server in the archive")
	}
	m := mailserver.Account{Host: a.Imap.Host, Port: a.Imap.Port, Security: a.Imap.Encryption, Login: a.Login, Password: password}
	if m.Login == "" {
		m.Login = a.Email
	}
	if err := s.checkMailbox(ctx, m); err != nil {
		return err
	}
	// Bichon copies the mail with it too: a new app password fixes copying as well as deleting.
	if err := b.SetPassword(ctx, id, bichon.IMAP{Host: m.Host, Port: m.Port, Security: m.Security, Login: m.Login, Password: m.Password}); err != nil {
		return fmt.Errorf("saving the password in the archive: %w", err)
	}
	return s.Store.Update(func(st *state.State) error {
		for i := range st.Mail {
			if st.Mail[i].ID == id {
				x := &st.Mail[i]
				x.IMAPHost, x.IMAPPort, x.IMAPSecurity, x.IMAPLogin, x.IMAPPassword = m.Host, m.Port, m.Security, m.Login, m.Password
			}
		}
		return nil
	})
}

// MaxMailDelete is how many emails one request may delete (the app sends bigger selections in parts).
const MaxMailDelete = 100

// MailDeleteResult says what was deleted, and what couldn't be (still in both places).
type MailDeleteResult struct {
	Deleted int              `json:"deleted"`
	Failed  []MailDeleteFail `json:"failed"`
}

type MailDeleteFail struct {
	Account uint64 `json:"account,string"`
	ID      string `json:"id"`
	Error   string `json:"error"`
}

// DeleteMail deletes messages from the mail server they came from (to its Trash where it has one) and then
// from the archive on this NAS. A message is only taken out of the archive once the mail server no longer
// has it, so nothing is half-deleted.
func (s *Server) DeleteMail(ctx context.Context, person string, ids map[uint64][]string) (MailDeleteResult, error) {
	out := MailDeleteResult{Failed: []MailDeleteFail{}}
	total := 0
	for _, list := range ids {
		total += len(list)
	}
	if total > MaxMailDelete {
		return out, fmt.Errorf("delete at most %d emails at a time", MaxMailDelete)
	}
	// Finish even if the phone stops waiting: stopping half way would leave mail gone from the server but archived.
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Minute)
	defer cancel()
	accounts := map[uint64]state.MailAccount{}
	for _, m := range s.Store.Read().Mail {
		accounts[m.ID] = m
	}
	for account := range ids {
		if err := s.ownsMail(person, account); err != nil {
			return out, err
		}
		if accounts[account].IMAPPassword == "" {
			return out, ErrNeedMailPassword
		}
	}
	b, err := s.mailClient()
	if err != nil {
		return out, err
	}
	// First every message's archive entry (checking it's in that mailbox), so nothing is deleted anywhere
	// before the whole request is known to be the person's own.
	type entry struct {
		id  string
		msg mailserver.Message
	}
	entries := map[uint64][]entry{}
	for account, list := range ids {
		for _, id := range list {
			e, err := b.Envelope(ctx, account, id)
			var ae *bichon.APIError
			switch {
			case errors.As(err, &ae) && ae.Status == 404:
				continue // not in the archive any more
			case err != nil:
				out.Failed = append(out.Failed, MailDeleteFail{account, id, err.Error()})
				continue
			case e.AccountID != account:
				return out, ErrNotYourMail // a message id from someone else's mailbox
			}
			entries[account] = append(entries[account], entry{id, mailserver.Message{Folder: e.MailboxName, MessageID: e.MessageID}})
		}
	}
	gone := map[uint64][]string{}
	for account, list := range entries {
		fail := func(id string, err error) {
			out.Failed = append(out.Failed, MailDeleteFail{account, id, err.Error()})
		}
		var msgs []mailserver.Message
		var found []string
		for _, x := range list {
			msgs = append(msgs, x.msg)
			found = append(found, x.id)
		}
		if len(msgs) == 0 {
			continue
		}
		results, err := s.deleteOnServer(ctx, imapOf(accounts[account]), msgs)
		if err != nil {
			for _, id := range found {
				fail(id, err)
			}
			continue
		}
		for i, id := range found {
			if results[i] != nil {
				fail(id, results[i])
			} else {
				gone[account] = append(gone[account], id)
			}
		}
	}
	if err := b.Delete(ctx, gone); err != nil {
		// Gone from the mail server but still archived: deleting again finishes the job.
		return out, fmt.Errorf("deleted from the mail server, but not from the archive on the NAS (try again): %w", err)
	}
	for _, list := range gone {
		out.Deleted += len(list)
	}
	if out.Deleted > 0 {
		s.Log.Printf("mail: %s deleted %d messages (mail server and archive)", person, out.Deleted)
	}
	return out, nil
}

// MailWorker finishes setting up mailboxes whose folders weren't chosen yet (the server restarted
// before the mailbox had connected).
func (s *Server) MailWorker(ctx context.Context) {
	for {
		for _, m := range s.Store.Read().Mail {
			if !m.FoldersChosen {
				s.chooseFolders(ctx, m.ID)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(10 * time.Minute):
		}
	}
}
