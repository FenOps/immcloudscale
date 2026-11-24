// Package immich is the small slice of the Immich API the server needs to provision users and
// per-device API keys.
package immich

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type Client struct {
	Base string // e.g. http://127.0.0.1:2283
	HTTP *http.Client
}

func New(base string) *Client {
	return &Client{Base: strings.TrimRight(base, "/"), HTTP: &http.Client{Timeout: 30 * time.Second}}
}

type APIError struct {
	Status  int
	Message string
}

func (e *APIError) Error() string { return fmt.Sprintf("immich: HTTP %d: %s", e.Status, e.Message) }

func (c *Client) do(ctx context.Context, method, path, token string, body, out any) error {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.Base+"/api"+path, rd)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		var e struct {
			Message any `json:"message"`
		}
		_ = json.Unmarshal(data, &e)
		msg := fmt.Sprint(e.Message)
		if e.Message == nil {
			msg = strings.TrimSpace(string(data))
		}
		return &APIError{resp.StatusCode, msg}
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

// Ping is true once the server answers /api/server/ping.
func (c *Client) Ping(ctx context.Context) bool {
	var r struct{ Res string }
	return c.do(ctx, http.MethodGet, "/server/ping", "", nil, &r) == nil && r.Res == "pong"
}

// AdminSignUp creates the first (admin) account on a fresh server. Fails if one exists.
func (c *Client) AdminSignUp(ctx context.Context, email, password, name string) error {
	return c.do(ctx, http.MethodPost, "/auth/admin-sign-up", "", map[string]string{"email": email, "password": password, "name": name}, nil)
}

func (c *Client) Login(ctx context.Context, email, password string) (string, error) {
	var r struct {
		AccessToken string `json:"accessToken"`
	}
	err := c.do(ctx, http.MethodPost, "/auth/login", "", map[string]string{"email": email, "password": password}, &r)
	return r.AccessToken, err
}

type User struct {
	ID    string `json:"id"`
	Email string `json:"email"`
	Name  string `json:"name"`
}

func (c *Client) CreateUser(ctx context.Context, adminToken, email, password, name string) (User, error) {
	var u User
	err := c.do(ctx, http.MethodPost, "/admin/users", adminToken, map[string]any{
		"email": email, "password": password, "name": name, "shouldChangePassword": false,
	}, &u)
	return u, err
}

func (c *Client) DeleteUser(ctx context.Context, adminToken, id string) error {
	return c.do(ctx, http.MethodDelete, "/admin/users/"+id, adminToken, map[string]any{"force": false}, nil)
}

type APIKey struct {
	ID     string
	Secret string
}

// CreateAPIKey makes a full-access key for the signed-in user (one per paired phone).
func (c *Client) CreateAPIKey(ctx context.Context, userToken, name string) (APIKey, error) {
	var r struct {
		Secret string `json:"secret"`
		APIKey struct {
			ID string `json:"id"`
		} `json:"apiKey"`
	}
	err := c.do(ctx, http.MethodPost, "/api-keys", userToken, map[string]any{"name": name, "permissions": []string{"all"}}, &r)
	return APIKey{r.APIKey.ID, r.Secret}, err
}

func (c *Client) DeleteAPIKey(ctx context.Context, userToken, id string) error {
	return c.do(ctx, http.MethodDelete, "/api-keys/"+id, userToken, nil, nil)
}

type Storage struct {
	DiskSize            string  `json:"diskSize"`
	DiskUse             string  `json:"diskUse"`
	DiskUsagePercentage float64 `json:"diskUsagePercentage"`
}

func (c *Client) Storage(ctx context.Context, token string) (Storage, error) {
	var s Storage
	err := c.do(ctx, http.MethodGet, "/server/storage", token, nil, &s)
	return s, err
}
