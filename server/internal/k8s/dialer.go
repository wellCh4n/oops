package k8s

import (
	"context"
	"net"
	"time"
)

type netDialer struct{ timeout time.Duration }

func (d *netDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: d.timeout, KeepAlive: 30 * time.Second}
	return dialer.DialContext(ctx, network, address)
}
