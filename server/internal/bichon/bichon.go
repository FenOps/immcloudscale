// Package bichon is the slice of the Bichon email archiver's API the server uses: it keeps everyone's mail
// archive (https://github.com/rustmailer/bichon), and the server shows each person only their own mailboxes.
package bichon

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type Client struct {
	Base  string // e.g. http://127.0.0.1:15630
	Token string // an API token (or a session token from Login)
	HTTP  *http.Client
}

func New(base, token string) *Client {
	return &Client{Base: strings.TrimRight(base, "/"), Token: token, HTTP: &http.Client{Timeout: 30 * time.Second}}
}

type APIError struct {
	Status  int
	Message string
}

func (e *APIError) Error() string { return fmt.Sprintf("bichon: HTTP %d: %s", e.Status, e.Message) }

func (c *Client) request(ctx context.Context, cl *http.Client, method, path string, body any) (*http.Response, error) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.Base+path, rd)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	resp, err := cl.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 300 {
		defer resp.Body.Close()
		data, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		var e struct {
			Message string `json:"message"`
		}
		if json.Unmarshal(data, &e) != nil || e.Message == "" {
			e.Message = strings.TrimSpace(string(data))
		}
		// Bichon's messages carry a Rust debug dump; the first line is the useful part.
		msg, _, _ := strings.Cut(e.Message, "\n")
		return nil, &APIError{resp.StatusCode, strings.TrimSpace(msg)}
	}
	return resp, nil
}

func (c *Client) do(ctx context.Context, method, path string, body, out any) error {
	resp, err := c.request(ctx, c.HTTP, method, path, body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if out == nil || len(data) == 0 {
		return nil
	}
	if s, ok := out.(*string); ok {
		*s = strings.TrimSpace(string(data))
		return nil
	}
	return json.Unmarshal(data, out)
}

// Login signs in with a Bichon user's name and password and returns a session token.
func (c *Client) Login(ctx context.Context, user, password string) (string, error) {
	var r struct {
		Success     bool   `json:"success"`
		Error       string `json:"error_message"`
		AccessToken string `json:"access_token"`
		MFARequired bool   `json:"mfa_required"`
	}
	if err := c.do(ctx, http.MethodPost, "/api/login", map[string]string{"username": user, "password": password}, &r); err != nil {
		return "", err
	}
	if r.MFARequired {
		return "", fmt.Errorf("this Bichon user has two-factor sign-in on: create an API token in Bichon's settings and use --token instead")
	}
	if !r.Success || r.AccessToken == "" {
		return "", fmt.Errorf("signing in to Bichon: %s", r.Error)
	}
	return r.AccessToken, nil
}

// CreateToken makes a long-lived API token for the signed-in user.
func (c *Client) CreateToken(ctx context.Context, name string) (string, error) {
	var tok string
	err := c.do(ctx, http.MethodPost, "/api/v1/access-token", map[string]any{"name": name}, &tok)
	return strings.Trim(tok, `"`), err
}

// Check is nil when the token works.
func (c *Client) Check(ctx context.Context) error {
	return c.do(ctx, http.MethodGet, "/api/v1/current-user", nil, nil)
}

type Account struct {
	ID      uint64 `json:"id"`
	Email   string `json:"email"`
	Name    string `json:"account_name,omitempty"`
	Login   string `json:"login_name,omitempty"`
	Enabled bool   `json:"enabled"`
	Imap    *struct {
		Host       string `json:"host"`
		Port       int    `json:"port"`
		Encryption string `json:"encryption"`
	} `json:"imap,omitempty"`
}

func (c *Client) Account(ctx context.Context, id uint64) (Account, error) {
	var a Account
	err := c.do(ctx, http.MethodGet, fmt.Sprintf("/api/v1/account/%d", id), nil, &a)
	return a, err
}

func (c *Client) Accounts(ctx context.Context) ([]Account, error) {
	var all []Account
	for page := 1; ; page++ {
		var r struct {
			Items      []Account `json:"items"`
			TotalItems int       `json:"total_items"`
		}
		if err := c.do(ctx, http.MethodGet, fmt.Sprintf("/api/v1/accounts?page=%d&page_size=100", page), nil, &r); err != nil {
			return nil, err
		}
		all = append(all, r.Items...)
		if len(r.Items) == 0 || len(all) >= r.TotalItems {
			return all, nil
		}
	}
}

// IMAP is how to reach a mailbox.
type IMAP struct {
	Host     string
	Port     int
	Security string // "Ssl", "StartTls" or "None"
	Login    string
	Password string
}

// AddAccount adds an IMAP mailbox to the archive; Bichon starts downloading it.
func (c *Client) AddAccount(ctx context.Context, email, name string, m IMAP) (Account, error) {
	var a Account
	err := c.do(ctx, http.MethodPost, "/api/v1/account", map[string]any{
		"email": email, "login_name": m.Login, "account_name": name, "enabled": true, "account_type": "IMAP",
		"use_dangerous": false, "auto_download_new_mailboxes": true,
		"imap": map[string]any{
			"host": m.Host, "port": m.Port, "encryption": m.Security,
			"auth": map[string]any{"auth_type": "Password", "password": m.Password},
		},
	}, &a)
	return a, err
}

// SetEnabled pauses or resumes downloading a mailbox (its archive stays).
func (c *Client) SetEnabled(ctx context.Context, id uint64, on bool) error {
	return c.do(ctx, http.MethodPost, fmt.Sprintf("/api/v1/account/%d", id), map[string]any{"enabled": on}, nil)
}

// SetFolders chooses which folders of a mailbox are downloaded.
func (c *Client) SetFolders(ctx context.Context, id uint64, folders []string) error {
	return c.do(ctx, http.MethodPost, fmt.Sprintf("/api/v1/account/%d", id), map[string]any{"sync_folders": folders}, nil)
}

// DeleteAccount removes a mailbox and everything archived from it.
func (c *Client) DeleteAccount(ctx context.Context, id uint64) error {
	return c.do(ctx, http.MethodDelete, fmt.Sprintf("/api/v1/account/%d", id), nil, nil)
}

type Mailbox struct {
	ID         uint64 `json:"id"`
	Name       string `json:"name"`
	Attributes []any  `json:"attributes"`
	Exists     int    `json:"exists"`
}

// Mailboxes lists a mailbox's folders; empty while Bichon is still connecting.
func (c *Client) Mailboxes(ctx context.Context, id uint64) ([]Mailbox, error) {
	var r struct {
		Mailboxes []Mailbox `json:"mailboxes"`
		Error     *string   `json:"error"`
	}
	if err := c.do(ctx, http.MethodGet, fmt.Sprintf("/api/v1/list-mailboxes/%d", id), nil, &r); err != nil {
		return nil, err
	}
	if r.Error != nil && *r.Error != "" {
		return nil, fmt.Errorf("%s", *r.Error)
	}
	return r.Mailboxes, nil
}

// Stats is a mailbox's archive size and how its downloading is going.
type Stats struct {
	Count     int
	Bytes     int64
	Syncing   bool
	LastSync  time.Time
	LastError string
}

func (c *Client) Stats(ctx context.Context, id uint64) (Stats, error) {
	var out Stats
	var size struct {
		Count int   `json:"total_count"`
		Size  int64 `json:"total_size"`
	}
	if err := c.do(ctx, http.MethodGet, fmt.Sprintf("/api/v1/accounts/%d/stats", id), nil, &size); err != nil {
		return out, err
	}
	out.Count, out.Bytes = size.Count, size.Size
	var dl struct {
		Active  any `json:"active_session"`
		History []struct {
			End     int64   `json:"end_time"`
			Status  string  `json:"status"`
			Message *string `json:"message"`
			Errors  []any   `json:"errors"`
		} `json:"history"`
	}
	if err := c.do(ctx, http.MethodGet, fmt.Sprintf("/api/v1/accounts/%d/download-stats", id), nil, &dl); err != nil {
		return out, nil // the size alone is still worth showing
	}
	out.Syncing = dl.Active != nil
	if n := len(dl.History); n > 0 {
		last := dl.History[n-1]
		out.LastSync = time.UnixMilli(last.End)
		if last.Status != "Success" {
			out.LastError = last.Status
			if last.Message != nil && *last.Message != "" {
				out.LastError = *last.Message
			} else if len(last.Errors) > 0 {
				out.LastError = fmt.Sprint(last.Errors[0])
			}
		}
	}
	return out, nil
}

// Search is what to look for; zero fields don't filter.
type Search struct {
	Text          string
	From          string
	Accounts      []uint64 // required: never search everyone's mail
	Mailbox       uint64
	HasAttachment bool
	Since, Before time.Time
	Page, Size    int
}

type Envelope struct {
	ID           string   `json:"id"`
	MessageID    string   `json:"message_id"`
	AccountID    uint64   `json:"account_id"`
	AccountEmail string   `json:"account_email"`
	MailboxName  string   `json:"mailbox_name"`
	Subject      string   `json:"subject"`
	Preview      string   `json:"preview"`
	From         string   `json:"from"`
	FromName     string   `json:"from_name"`
	To           []string `json:"to"`
	Cc           []string `json:"cc"`
	Date         int64    `json:"date"`
	InternalDate int64    `json:"internal_date"`
	Size         int64    `json:"size"`
	ThreadID     string   `json:"thread_id"`
	Attachments  int      `json:"regular_attachment_count"`
}

type Page struct {
	Items []Envelope `json:"items"`
	Total int        `json:"total_items"`
}

func (c *Client) Search(ctx context.Context, q Search) (Page, error) {
	if len(q.Accounts) == 0 {
		return Page{}, nil
	}
	f := map[string]any{"account_ids": q.Accounts}
	if t := strings.TrimSpace(q.Text); t != "" {
		f["text"] = t
	}
	if q.From != "" {
		f["from"] = q.From
	}
	if q.Mailbox != 0 {
		f["mailbox_ids"] = []uint64{q.Mailbox}
	}
	if q.HasAttachment {
		f["has_attachment"] = true
	}
	if !q.Since.IsZero() {
		f["since"] = q.Since.UnixMilli()
	}
	if !q.Before.IsZero() {
		f["before"] = q.Before.UnixMilli()
	}
	sort := "DATE"
	if f["text"] != nil {
		sort = "RELEVANCE"
	}
	var p Page
	err := c.do(ctx, http.MethodPost, "/api/v1/search-messages", map[string]any{
		"filter": f, "page": max(q.Page, 1), "page_size": min(max(q.Size, 1), 100), "sort_by": sort, "desc": true,
	}, &p)
	return p, err
}

func (c *Client) Envelope(ctx context.Context, account uint64, id string) (Envelope, error) {
	var e Envelope
	err := c.do(ctx, http.MethodGet, fmt.Sprintf("/api/v1/envelope/%d/%s", account, url.PathEscape(id)), nil, &e)
	return e, err
}

type Attachment struct {
	Name   string `json:"filename"`
	Type   string `json:"file_type"`
	Size   int64  `json:"size"`
	Hash   string `json:"content_hash"`
	Inline bool   `json:"inline"`
	ID     string `json:"content_id,omitempty"`
}

type Content struct {
	Text        string       `json:"text"`
	HTML        string       `json:"html"`
	Attachments []Attachment `json:"attachments"`
	HasRemote   bool         `json:"has_remote_content"`
}

// Content is a message's text and HTML; with blockRemote, images and anything else from the web are taken out.
func (c *Client) Content(ctx context.Context, account uint64, id string, blockRemote bool) (Content, error) {
	var m Content
	err := c.do(ctx, http.MethodGet, fmt.Sprintf("/api/v1/message-content/%d/%s?block_remote_content=%s",
		account, url.PathEscape(id), strconv.FormatBool(blockRemote)), nil, &m)
	return m, err
}

// Attachment streams an attachment's bytes; the caller closes it.
func (c *Client) Attachment(ctx context.Context, account uint64, id, hash string) (io.ReadCloser, int64, error) {
	// No overall time limit: a large attachment over a slow link takes as long as it takes (ctx still ends it).
	resp, err := c.request(ctx, &http.Client{Transport: c.HTTP.Transport}, http.MethodGet, fmt.Sprintf("/api/v1/download-attachment/%d/%s?content_hash=%s",
		account, url.PathEscape(id), url.QueryEscape(hash)), nil)
	if err != nil {
		return nil, 0, err
	}
	return resp.Body, resp.ContentLength, nil
}

// Delete removes messages from the archive only; the mailbox they came from is never touched.
func (c *Client) Delete(ctx context.Context, ids map[uint64][]string) error {
	body := map[string][]string{}
	for a, list := range ids {
		if len(list) > 0 {
			body[strconv.FormatUint(a, 10)] = list
		}
	}
	if len(body) == 0 {
		return nil
	}
	return c.do(ctx, http.MethodPost, "/api/v1/delete-messages", body, nil)
}
