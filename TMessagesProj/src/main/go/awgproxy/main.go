package main

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

type peerConfig struct {
	PublicKey     string   `json:"publicKey"`
	PresharedKey  string   `json:"presharedKey"`
	Endpoint      string   `json:"endpoint"`
	AllowedIPs    []string `json:"allowedIps"`
	Keepalive     int      `json:"keepalive"`
}

type streamDialer interface {
	DialStream(context.Context, string) (net.Conn, error)
}

type userspaceDialer struct {
	network *netstack.Net
}

func (d *userspaceDialer) DialStream(ctx context.Context, address string) (net.Conn, error) {
	return d.network.DialContext(ctx, "tcp", address)
}

var activeDevice *device.Device

type profile struct {
	PrivateKey string            `json:"privateKey"`
	Addresses  []string          `json:"addresses"`
	DNS        []string          `json:"dns"`
	MTU        int               `json:"mtu"`
	Peer       peerConfig        `json:"peer"`
	Amnezia    map[string]string `json:"amnezia"`
}

func main() {
	configPath := flag.String("config", "", "path to the AmneziaWG JSON profile")
	listenAddress := flag.String("listen", "127.0.0.1:1081", "local SOCKS5 listen address")
	flag.Parse()
	if *configPath == "" {
		log.Fatal("missing -config")
	}

	cfg, err := readProfile(*configPath)
	if err != nil {
		log.Fatal(err)
	}
	ipc, prefixes, dns, mtu, err := buildDeviceConfig(cfg)
	if err != nil {
		log.Fatal(err)
	}

	tunDevice, network, err := netstack.CreateNetTUN(prefixesToAddresses(prefixes), dns, mtu)
	if err != nil {
		log.Fatalf("create userspace network stack: %v", err)
	}
	activeDevice = device.NewDevice(tunDevice, conn.NewDefaultBind(),
		device.NewLogger(device.LogLevelError, "(telegram-awg) "))
	if err := activeDevice.IpcSet(ipc); err != nil {
		log.Fatalf("configure AmneziaWG device: %v", err)
	}
	if err := activeDevice.Up(); err != nil {
		log.Fatalf("start AmneziaWG device: %v", err)
	}
	dialer := &userspaceDialer{network: network}

	listener, err := net.Listen("tcp", *listenAddress)
	if err != nil {
		log.Fatalf("listen on local SOCKS5: %v", err)
	}
	log.Printf("READY %s", listener.Addr())
	for {
		client, err := listener.Accept()
		if err != nil {
			log.Printf("SOCKS accept failed: %v", err)
			continue
		}
		go handleSocks(client, dialer)
	}
}

func readProfile(path string) (profile, error) {
	var cfg profile
	data, err := os.ReadFile(path)
	if err != nil {
		return cfg, fmt.Errorf("read profile: %w", err)
	}
	if err := json.Unmarshal(data, &cfg); err != nil {
		return cfg, fmt.Errorf("parse profile JSON: %w", err)
	}
	return cfg, nil
}

func decodeKey(value, label string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(value))
	if err != nil || len(raw) != 32 {
		return "", fmt.Errorf("%s must be a 32-byte base64 key", label)
	}
	return hex.EncodeToString(raw), nil
}

func buildDeviceConfig(cfg profile) (string, []netip.Prefix, []netip.Addr, int, error) {
	privateKey, err := decodeKey(cfg.PrivateKey, "privateKey")
	if err != nil {
		return "", nil, nil, 0, err
	}
	publicKey, err := decodeKey(cfg.Peer.PublicKey, "peer.publicKey")
	if err != nil {
		return "", nil, nil, 0, err
	}
	if strings.TrimSpace(cfg.Peer.Endpoint) == "" {
		return "", nil, nil, 0, errors.New("peer.endpoint is required")
	}

	var prefixes []netip.Prefix
	for _, value := range cfg.Addresses {
		prefix, err := netip.ParsePrefix(strings.TrimSpace(value))
		if err != nil {
			return "", nil, nil, 0, fmt.Errorf("invalid interface address %q: %w", value, err)
		}
		prefixes = append(prefixes, prefix)
	}
	if len(prefixes) == 0 {
		return "", nil, nil, 0, errors.New("at least one interface address is required")
	}

	var dns []netip.Addr
	for _, value := range cfg.DNS {
		ip, err := netip.ParseAddr(strings.TrimSpace(value))
		if err != nil {
			return "", nil, nil, 0, fmt.Errorf("invalid DNS address %q: %w", value, err)
		}
		dns = append(dns, ip)
	}
	if len(dns) == 0 {
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}

	allowed := cfg.Peer.AllowedIPs
	if len(allowed) == 0 {
		allowed = []string{"0.0.0.0/0", "::/0"}
	}
	if cfg.MTU <= 0 {
		cfg.MTU = 1408
	}
	keepalive := cfg.Peer.Keepalive
	if keepalive <= 0 {
		keepalive = 25
	}

	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\npublic_key=%s\nendpoint=%s\n", privateKey, publicKey, strings.TrimSpace(cfg.Peer.Endpoint))
	for _, prefix := range allowed {
		if _, err := netip.ParsePrefix(strings.TrimSpace(prefix)); err != nil {
			return "", nil, nil, 0, fmt.Errorf("invalid peer allowed IP %q: %w", prefix, err)
		}
		fmt.Fprintf(&b, "allowed_ip=%s\n", strings.TrimSpace(prefix))
	}
	if cfg.Peer.PresharedKey != "" {
		psk, err := decodeKey(cfg.Peer.PresharedKey, "peer.presharedKey")
		if err != nil {
			return "", nil, nil, 0, err
		}
		fmt.Fprintf(&b, "preshared_key=%s\n", psk)
	}
	fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", keepalive)

	// Pass through only recognized AmneziaWG keys. This preserves custom
	// obfuscation settings instead of silently replacing them with presets.
	allowedKeys := map[string]bool{
		"jc": true, "jmin": true, "jmax": true,
		"s1": true, "s2": true, "s3": true, "s4": true,
		"h1": true, "h2": true, "h3": true, "h4": true,
		"i1": true, "i2": true, "i3": true, "i4": true, "i5": true,
		"header_protection_key": true, "content_padding_addition": true,
		"rekey_after_time": true, "rekey_timeout": true, "reject_after_time": true,
		"keepalive_timeout": true, "max_handshake_attempts": true,
		"random_trailers": true, "disable_cookies": true,
	}
	for key, value := range cfg.Amnezia {
		key = strings.ToLower(strings.TrimSpace(key))
		value = strings.TrimSpace(value)
		if !allowedKeys[key] || value == "" || strings.ContainsAny(value, "\r\n") {
			continue
		}
		if _, err := strconv.Atoi(value); err == nil || strings.HasPrefix(key, "h") || strings.HasPrefix(key, "i") || strings.Contains(key, "key") || strings.Contains(key, "addition") || key == "random_trailers" || key == "disable_cookies" {
			fmt.Fprintf(&b, "%s=%s\n", key, value)
		} else {
			return "", nil, nil, 0, fmt.Errorf("invalid AmneziaWG value for %s", key)
		}
	}
	return b.String(), prefixes, dns, cfg.MTU, nil
}

func prefixesToAddresses(prefixes []netip.Prefix) []netip.Addr {
	addresses := make([]netip.Addr, 0, len(prefixes))
	for _, prefix := range prefixes {
		addresses = append(addresses, prefix.Addr())
	}
	return addresses
}

func handleSocks(client net.Conn, dialer streamDialer) {
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(15 * time.Second))

	var greeting [2]byte
	if _, err := io.ReadFull(client, greeting[:]); err != nil || greeting[0] != 5 {
		return
	}
	methods := make([]byte, int(greeting[1]))
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	noAuth := false
	for _, method := range methods {
		if method == 0 {
			noAuth = true
			break
		}
	}
	if !noAuth {
		_, _ = client.Write([]byte{5, 0xff})
		return
	}
	if _, err := client.Write([]byte{5, 0}); err != nil {
		return
	}

	var request [4]byte
	if _, err := io.ReadFull(client, request[:]); err != nil || request[0] != 5 || request[1] != 1 {
		writeSocksReply(client, 7)
		return
	}
	var host string
	switch request[3] {
	case 1:
		raw := make([]byte, 4)
		if _, err := io.ReadFull(client, raw); err != nil { return }
		host = net.IP(raw).String()
	case 4:
		raw := make([]byte, 16)
		if _, err := io.ReadFull(client, raw); err != nil { return }
		host = net.IP(raw).String()
	case 3:
		var size [1]byte
		if _, err := io.ReadFull(client, size[:]); err != nil { return }
		raw := make([]byte, int(size[0]))
		if _, err := io.ReadFull(client, raw); err != nil { return }
		host = string(raw)
	default:
		writeSocksReply(client, 8)
		return
	}
	var portBytes [2]byte
	if _, err := io.ReadFull(client, portBytes[:]); err != nil { return }
	port := binary.BigEndian.Uint16(portBytes[:])
	if port == 0 || host == "" {
		writeSocksReply(client, 1)
		return
	}

	_ = client.SetDeadline(time.Time{})
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	upstream, err := dialer.DialStream(ctx, net.JoinHostPort(host, strconv.Itoa(int(port))))
	if err != nil {
		log.Printf("SOCKS connect %s:%d failed: %v", host, port, err)
		writeSocksReply(client, 5)
		return
	}
	defer upstream.Close()
	if err := writeSocksReply(client, 0); err != nil {
		return
	}

	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(upstream, client); if tcp, ok := upstream.(*net.TCPConn); ok { _ = tcp.CloseWrite() }; done <- struct{}{} }()
	go func() { _, _ = io.Copy(client, upstream); if tcp, ok := client.(*net.TCPConn); ok { _ = tcp.CloseWrite() }; done <- struct{}{} }()
	<-done
}

func writeSocksReply(w io.Writer, code byte) error {
	_, err := w.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}
