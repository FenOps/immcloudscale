//go:build tools

// Keeps gomobile's bind package in go.mod for build-aar.sh.
package tsbridge

import _ "golang.org/x/mobile/bind"
