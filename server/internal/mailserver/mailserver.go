// Package mailserver deletes messages from a person's own mailbox over IMAP, so mail deleted in the app goes
// from their mail provider too and not only from the archive on the NAS.
package mailserver

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"slices"
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
	trash, folders, err := trashFolder(c)
	if err != nil {
		// Not knowing whether there's a Trash must not turn "move to Trash" into deleting for good.
		return nil, fmt.Errorf("listing the folders on the mail server: %w", err)
	}
	results := make([]error, len(msgs))
	byFolder := map[string][]int{}
	for i, m := range msgs {
		// Bichon makes up "…@bichon" ids for messages that have none.
		if normID(m.MessageID) == "" || strings.HasSuffix(normID(m.MessageID), "@bichon") {
			results[i] = ErrNoMessageID // none in the message (the archive made one up): it can't be found on the server
			continue
		}
		byFolder[m.Folder] = append(byFolder[m.Folder], i)
	}
	for folder, idx := range byFolder {
		if _, err := c.Select(folder, false); err != nil {
			for _, i := range idx {
				results[i] = errNotFound // renamed or gone: look for it in the other folders below
			}
			continue
		}
		for _, i := range idx {
			if ctx.Err() != nil {
				results[i] = ctx.Err() // what was done so far still counts
				continue
			}
			results[i] = deleteOne(c, msgs[i].MessageID, folder, trash)
		}
	}
	findMoved(ctx, c, msgs, results, folders, trash)
	return results, nil
}

// findMoved looks through the other folders for messages that weren't where they were archived (moved since).
// Each folder is opened once. Only a message that's in none of them counts as already deleted: one in a folder
// that couldn't be opened, or not looked for before time ran out, stays an error.
func findMoved(ctx context.Context, c *client.Client, msgs []Message, results []error, folders []string, trash string) {
	var missing []int
	for i := range msgs {
		if errors.Is(results[i], errNotFound) {
			missing = append(missing, i)
		}
	}
	var unreadable []string
	for _, f := range folders {
		if len(missing) == 0 {
			break
		}
		if ctx.Err() != nil {
			break
		}
		if strings.EqualFold(f, trash) {
			continue
		}
		if _, err := c.Select(f, false); err != nil {
			unreadable = append(unreadable, f)
			continue
		}
		still := missing[:0:0]
		for _, i := range missing {
			if ctx.Err() != nil {
				still = append(still, i)
				continue
			}
			err := deleteOne(c, msgs[i].MessageID, f, trash)
			if errors.Is(err, errNotFound) {
				still = append(still, i)
			} else {
				results[i] = err // found here: deleted, or the error doing it
			}
		}
		missing = still
	}
	for _, i := range missing {
		switch {
		case ctx.Err() != nil:
			results[i] = fmt.Errorf("not every folder could be checked in time: %w", ctx.Err())
		case len(unreadable) > 0:
			results[i] = fmt.Errorf("couldn't check the folder %q on the mail server", unreadable[0])
		default:
			results[i] = nil // in no folder: already deleted on the server
		}
	}
}

// errNotFound: the message isn't in the selected folder.
var errNotFound = errors.New("not in this folder")

// normID is a Message-ID without its angle brackets and spaces, for comparing.
func normID(id string) string {
	return strings.Trim(strings.TrimSpace(id), "<>")
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
// else a top-level folder with a usual name. "" when there's none; an error when the folders couldn't be listed.
func trashFolder(c *client.Client) (string, []string, error) {
	ch := make(chan *imap.MailboxInfo, 50)
	done := make(chan error, 1)
	go func() { done <- c.List("", "*", ch) }()
	var named, marked string
	var all []string
	for m := range ch {
		if !slices.ContainsFunc(m.Attributes, func(a string) bool { return strings.EqualFold(a, imap.NoSelectAttr) }) {
			all = append(all, m.Name)
		}
		for _, attr := range m.Attributes {
			if strings.EqualFold(attr, imap.TrashAttr) && marked == "" {
				marked = m.Name
			}
		}
		n := strings.ToLower(m.Name)
		if p := strings.ToLower(m.Delimiter); p != "" && strings.HasPrefix(n, "inbox"+p) {
			n = strings.TrimPrefix(n, "inbox"+p) // INBOX.Trash counts as top level
		}
		if named == "" && (n == "trash" || n == "deleted items" || n == "deleted messages" || n == "bin") {
			named = m.Name
		}
	}
	if err := <-done; err != nil {
		return "", nil, err
	}
	if marked != "" {
		return marked, all, nil
	}
	return named, all, nil
}

// find is the UIDs in the selected folder whose Message-ID is exactly [messageID]. (SEARCH HEADER matches
// parts of words, so its answer is checked against each message's own Message-ID.)
func find(c *client.Client, messageID string) ([]uint32, error) {
	want := normID(messageID)
	crit := imap.NewSearchCriteria()
	crit.Header.Add("Message-ID", "<"+want+">")
	uids, err := c.UidSearch(crit)
	if err != nil || len(uids) == 0 {
		return nil, err
	}
	set := new(imap.SeqSet)
	set.AddNum(uids...)
	ch := make(chan *imap.Message, len(uids))
	done := make(chan error, 1)
	go func() { done <- c.UidFetch(set, []imap.FetchItem{imap.FetchUid, imap.FetchEnvelope}, ch) }()
	var exact []uint32
	for m := range ch {
		if m.Envelope != nil && normID(m.Envelope.MessageId) == want {
			exact = append(exact, m.Uid)
		}
	}
	return exact, <-done
}

func deleteOne(c *client.Client, messageID, folder, trash string) error {
	uids, err := find(c, messageID)
	if err != nil {
		return fmt.Errorf("finding it on the mail server: %w", err)
	}
	if len(uids) == 0 {
		return errNotFound
	}
	set := new(imap.SeqSet)
	set.AddNum(uids...)
	move, _ := c.Support("MOVE")
	uidplus, _ := c.Support("UIDPLUS")
	if trash != "" && !strings.EqualFold(folder, trash) {
		if move {
			if err := c.UidMove(set, trash); err != nil {
				return fmt.Errorf("moving it to %s: %w", trash, err)
			}
			return nil
		}
		if !uidplus {
			return errors.New("this mail server can't move a single message to Trash safely: delete it in your webmail")
		}
		if err := c.UidCopy(set, trash); err != nil {
			return fmt.Errorf("copying it to %s: %w", trash, err)
		}
	} else if !uidplus {
		// Plain EXPUNGE would also remove every other message marked deleted in this folder.
		return errors.New("this mail server can't delete a single message safely: delete it in your webmail")
	}
	// Delete for good (after the copy to Trash, or when there's no Trash), touching only these messages.
	if err := c.UidStore(set, imap.FormatFlagsOp(imap.AddFlags, true), []any{imap.DeletedFlag}, nil); err != nil {
		return fmt.Errorf("deleting it: %w", err)
	}
	status, err := c.Execute(&uidExpunge{set}, nil)
	if err == nil {
		err = status.Err()
	}
	return err
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
