package main

import (
	"encoding/base64"
	"strings"
	"testing"
)

const testKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

func TestBuildDeviceConfigPreservesAmneziaParameters(t *testing.T) {
	cfg := profile{
		PrivateKey: testKey,
		Addresses:  []string{"10.8.0.2/32"},
		DNS:        []string{"1.1.1.1"},
		MTU:        1408,
		Peer: peerConfig{
			PublicKey:  testKey,
			Endpoint:   "192.0.2.1:51820",
			AllowedIPs: []string{"0.0.0.0/0"},
			Keepalive:  25,
		},
		Amnezia: map[string]string{
			"jc": "4",
			"jmin": "40",
			"jmax": "70",
			"h1": "123456",
			"header_protection_key": "00112233445566778899aabbccddeeff",
		},
	}

	ipc, prefixes, dns, mtu, err := buildDeviceConfig(cfg)
	if err != nil {
		t.Fatalf("buildDeviceConfig returned error: %v", err)
	}
	for _, expected := range []string{
		"private_key=",
		"public_key=",
		"endpoint=192.0.2.1:51820",
		"allowed_ip=0.0.0.0/0",
		"jc=4",
		"jmin=40",
		"jmax=70",
		"h1=123456",
		"header_protection_key=00112233445566778899aabbccddeeff",
	} {
		if !strings.Contains(ipc, expected) {
			t.Errorf("IPC config does not contain %q", expected)
		}
	}
	if len(prefixes) != 1 || prefixes[0].String() != "10.8.0.2/32" {
		t.Errorf("unexpected interface prefixes: %v", prefixes)
	}
	if len(dns) != 1 || dns[0].String() != "1.1.1.1" {
		t.Errorf("unexpected DNS servers: %v", dns)
	}
	if mtu != 1408 {
		t.Errorf("unexpected MTU: %d", mtu)
	}
}

func TestBuildDeviceConfigRejectsInvalidKey(t *testing.T) {
	cfg := profile{
		PrivateKey: base64.StdEncoding.EncodeToString([]byte("short")),
		Addresses:  []string{"10.8.0.2/32"},
		Peer: peerConfig{
			PublicKey: testKey,
			Endpoint:  "192.0.2.1:51820",
		},
	}
	if _, _, _, _, err := buildDeviceConfig(cfg); err == nil {
		t.Fatal("expected invalid private key to be rejected")
	}
}

func TestBuildDeviceConfigRejectsInvalidAllowedIP(t *testing.T) {
	cfg := profile{
		PrivateKey: testKey,
		Addresses:  []string{"10.8.0.2/32"},
		Peer: peerConfig{
			PublicKey:  testKey,
			Endpoint:   "192.0.2.1:51820",
			AllowedIPs: []string{"not-a-prefix"},
		},
	}
	if _, _, _, _, err := buildDeviceConfig(cfg); err == nil {
		t.Fatal("expected invalid AllowedIPs entry to be rejected")
	}
}
