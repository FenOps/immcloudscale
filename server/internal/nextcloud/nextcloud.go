// Package nextcloud provisions users and app passwords through Nextcloud's OCS API.
package nextcloud

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Client struct {
	Base string // e.g. http://127.0.0.1:8080
	HTTP *http.Client
}

func New(base string) *Client {
	return &Client{Base: strings.TrimRight(base, "/"), HTTP: &http.Client{Timeout: 60 * time.Second}}
}

// OCSError is a non-success OCS reply. Status is the HTTP status.
type OCSError struct {
	Status, Code int
	Message      string
	// Parsed is false when the reply wasn't an OCS envelope (e.g. an HTML error page from a proxy).
	Parsed bool
}

func (e *OCSError) Error() string { return fmt.Sprintf("nextcloud: %s (code %d)", e.Message, e.Code) }

type ocsMeta struct {
	Status     string `json:"status"`
	StatusCode int    `json:"statuscode"`
	Message    string `json:"message"`
}

func (c *Client) ocs(ctx context.Context, method, path, user, pass string, form url.Values, data any) error {
	var body io.Reader
	if form != nil {
		body = strings.NewReader(form.Encode())
	}
	sep := "?"
	if strings.Contains(path, "?") {
		sep = "&"
	}
	req, err := http.NewRequestWithContext(ctx, method, c.Base+path+sep+"format=json", body)
	if err != nil {
		return err
	}
	req.SetBasicAuth(user, pass)
	req.Header.Set("OCS-APIRequest", "true")
	req.Header.Set("Accept", "application/json")
	if form != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var env struct {
		OCS struct {
			Meta ocsMeta         `json:"meta"`
			Data json.RawMessage `json:"data"`
		} `json:"ocs"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		return &OCSError{Status: resp.StatusCode, Code: resp.StatusCode, Message: strings.TrimSpace(string(raw))}
	}
	m := env.OCS.Meta
	// OCS v1 reports success as 100, v2 as 200.
	if m.StatusCode != 100 && m.StatusCode != 200 {
		return &OCSError{Status: resp.StatusCode, Code: m.StatusCode, Message: m.Message, Parsed: true}
	}
	if data != nil && len(env.OCS.Data) > 0 {
		return json.Unmarshal(env.OCS.Data, data)
	}
	return nil
}

// Installed is true once /status.php says the instance finished installing.
func (c *Client) Installed(ctx context.Context) bool {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, c.Base+"/status.php", nil)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	var s struct {
		Installed   bool `json:"installed"`
		Maintenance bool `json:"maintenance"`
	}
	return json.NewDecoder(resp.Body).Decode(&s) == nil && s.Installed && !s.Maintenance
}

func (c *Client) CreateUser(ctx context.Context, admin, adminPass, user, pass, display, email string) error {
	f := url.Values{"userid": {user}, "password": {pass}, "displayName": {display}}
	if email != "" {
		f.Set("email", email)
	}
	return c.ocs(ctx, http.MethodPost, "/ocs/v1.php/cloud/users", admin, adminPass, f, nil)
}

// UserExists reports whether an account exists (OCS 404/998 means it doesn't).
func (c *Client) UserExists(ctx context.Context, admin, adminPass, user string) (bool, error) {
	err := c.ocs(ctx, http.MethodGet, "/ocs/v1.php/cloud/users/"+url.PathEscape(user), admin, adminPass, nil, nil)
	var oe *OCSError
	if errors.As(err, &oe) && oe.Parsed && (oe.Code == 404 || oe.Code == 998) {
		return false, nil
	}
	return err == nil, err
}

func (c *Client) DeleteUser(ctx context.Context, admin, adminPass, user string) error {
	return c.ocs(ctx, http.MethodDelete, "/ocs/v1.php/cloud/users/"+url.PathEscape(user), admin, adminPass, nil, nil)
}

// AppPassword exchanges the user's password for a revocable app password (one per phone).
func (c *Client) AppPassword(ctx context.Context, user, pass string) (string, error) {
	var d struct {
		AppPassword string `json:"apppassword"`
	}
	err := c.ocs(ctx, http.MethodGet, "/ocs/v2.php/core/getapppassword", user, pass, nil, &d)
	return d.AppPassword, err
}

// RevokeAppPassword deletes an app password, authenticating with that app password.
// An app password that no longer signs in (401 / OCS 997) is already gone, which counts as success.
func (c *Client) RevokeAppPassword(ctx context.Context, user, appPass string) error {
	err := c.ocs(ctx, http.MethodDelete, "/ocs/v2.php/core/apppassword", user, appPass, nil, nil)
	var oe *OCSError
	if errors.As(err, &oe) && (oe.Status == http.StatusUnauthorized || oe.Code == 997) {
		return nil
	}
	return err
}
