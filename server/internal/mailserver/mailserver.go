// Package mailserver deletes messages from a person's own mailbox over IMAP, so mail deleted in the app goes
// from their mail provider too and not only from the archive on the NAS.
package mailserver

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"strings"
	"time"

	"github.com/emersion/go-imap"
	"github.com/emersion/go-imap/client"
)

// Account is how to sign in to a mailbox.
type Account struct {
	Host     string
	Port     int
	Security string // "Ssl", "StartTls" or "None" (Bichon's names)
	Login    string
	Password string
}

// Message is one message to delete: the folder it was archived from and its Message-ID header.
type Message struct {
	Folder    string
	MessageID string
}

// ErrNoMessageID is a message that can't be found again on the server (it has no Message-ID header).
var ErrNoMessageID = errors.New("this message has no Message-ID, so it can't be found on the mail server: delete it in your webmail")

// Delete removes messages from the mailbox. They go to the provider's Trash folder when it has one (Gmail
// empties it after 30 days; until then they can be restored there), otherwise they're deleted at once.
// A message that's no longer on the server counts as deleted. The result has one entry per message:
// nil when it's gone from the server. The error is for the whole mailbox (wrong password, no connection).
func Delete(ctx context.Context, a Account, msgs []Message) ([]error, error) {
	c, err := connect(ctx, a)
	if err != nil {
		return nil, err
	}
	defer c.Logout()
	trash := trashFolder(c)
	results := make([]error, len(msgs))
	byFolder := map[string][]int{}
	for i, m := range msgs {
		if strings.TrimSpace(m.MessageID) == "" {
			results[i] = ErrNoMessageID
			continue
		}
		byFolder[m.Folder] = append(byFolder[m.Folder], i)
	}
	for folder, idx := range byFolder {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if _, err := c.Select(folder, false); err != nil {
			for _, i := range idx {
				results[i] = fmt.Errorf("opening folder %q on the mail server: %w", folder, err)
			}
			continue
		}
		for _, i := range idx {
			results[i] = deleteOne(c, msgs[i].MessageID, folder, trash)
		}
	}
	return results, nil
}

func connect(ctx context.Context, a Account) (*client.Client, error) {
	addr := net.JoinHostPort(a.Host, fmt.Sprint(a.Port))
	d := &net.Dialer{Timeout: 20 * time.Second}
	if dl, ok := ctx.Deadline(); ok {
		d.Deadline = dl
	}
	var c *client.Client
	var err error
	switch a.Security {
	case "Ssl":
		c, err = client.DialWithDialerTLS(d, addr, &tls.Config{ServerName: a.Host})
	default:
		c, err = client.DialWithDialer(d, addr)
		if err == nil && a.Security == "StartTls" {
			err = c.StartTLS(&tls.Config{ServerName: a.Host})
		}
	}
	if err != nil {
		return nil, fmt.Errorf("connecting to %s: %w", addr, err)
	}
	c.Timeout = time.Minute
	if err := c.Login(a.Login, a.Password); err != nil {
		c.Logout()
		return nil, fmt.Errorf("signing in to %s: %w", a.Host, err)
	}
	return c, nil
}

// trashFolder is the mailbox's Trash: the one marked \Trash (Gmail's "[Gmail]/Trash" in any language),
// else one with a usual name. "" when there's none.
func trashFolder(c *client.Client) string {
	ch := make(chan *imap.MailboxInfo, 50)
	done := make(chan error, 1)
	go func() { done <- c.List("", "*", ch) }()
	var named string
	var marked string
	for m := range ch {
		for _, attr := range m.Attributes {
			if strings.EqualFold(attr, imap.TrashAttr) && marked == "" {
				marked = m.Name
			}
		}
		n := strings.ToLower(m.Name)
		if m.Delimiter != "" {
			if i := strings.LastIndex(n, strings.ToLower(m.Delimiter)); i >= 0 {
				n = n[i+len(m.Delimiter):]
			}
		}
		if named == "" && (n == "trash" || n == "deleted items" || n == "deleted messages" || n == "bin") {
			named = m.Name
		}
	}
	if <-done != nil {
		return ""
	}
	if marked != "" {
		return marked
	}
	return named
}

func deleteOne(c *client.Client, messageID, folder, trash string) error {
	crit := imap.NewSearchCriteria()
	crit.Header.Add("Message-ID", messageID)
	uids, err := c.UidSearch(crit)
	if err != nil {
		return fmt.Errorf("finding it on the mail server: %w", err)
	}
	if len(uids) == 0 {
		return nil // already gone from the server
	}
	set := new(imap.SeqSet)
	set.AddNum(uids...)
	if trash != "" && !strings.EqualFold(folder, trash) {
		if err := c.UidMove(set, trash); err != nil {
			return fmt.Errorf("moving it to %s: %w", trash, err)
		}
		return nil
	}
	// No Trash folder (or it's already in it): delete for good, touching only this message.
	if err := c.UidStore(set, imap.FormatFlagsOp(imap.AddFlags, true), []any{imap.DeletedFlag}, nil); err != nil {
		return fmt.Errorf("deleting it: %w", err)
	}
	if ok, _ := c.Support("UIDPLUS"); ok {
		status, err := c.Execute(&uidExpunge{set}, nil)
		if err == nil {
			err = status.Err()
		}
		return err
	}
	return c.Expunge(nil)
}

// uidExpunge is UID EXPUNGE (RFC 4315): removes only the given messages, not others marked deleted elsewhere.
type uidExpunge struct{ set *imap.SeqSet }

func (u *uidExpunge) Command() *imap.Command {
	return &imap.Command{Name: "UID", Arguments: []any{imap.RawString("EXPUNGE"), u.set}}
}

// Check signs in and out: nil when the address, port and password work.
func Check(ctx context.Context, a Account) error {
	c, err := connect(ctx, a)
	if err != nil {
		return err
	}
	return c.Logout()
}
