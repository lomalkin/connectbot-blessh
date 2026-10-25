# SSH over Bluetooth LE

Hosts with the protocol `ble` reach a [ble-term](https://github.com/aluminique/ble-term-server)
server over Bluetooth Low Energy, without any IP network. The SSH session is
the usual one (host key check, authentication, terminal); only its byte stream
runs over a GATT service, framed by the ble-term BleStream protocol.

## Using it

1. Install ble-term on the server (Linux with BlueZ). The installer prints the
   server's host key fingerprint.
2. In the host editor choose the protocol **ble**, tap **Scan for Bluetooth LE
   servers**, pick the server and enter the user name.
3. On the first connection, compare the fingerprint with the installer's.

Scanning needs the Nearby devices permission; Android 11 and earlier also need
location to be on.

## Behaviour

* Logins follow the server's SSH configuration (ble-term 0.2.0 and later):
  what network SSH allows or refuses, BLE does too.
* No Bluetooth pairing. A server that asks to pair (Debian 12 with EATT on)
  gets a hint instead of a retry loop: set `Channels = 1` on the server.
* No port forwarding and no jump hosts. Keystroke timing obfuscation is off:
  it would use much of the link's bandwidth.
* A session that is quiet for 20 s is probed with a write that the server
  must acknowledge, so a server whose daemon went away is noticed.
* Throughput is tens of kilobytes per second.

## Code and tests

| Part | Where |
|---|---|
| SSH over a non-TCP stream | `transport/BleSsh.kt`; hooks `directTransportFactory` and `obscureKeystrokeTiming` in `transport/SSH.kt` |
| BleStream state machine (no Android dependencies) | `transport/ble/BleStreamLink.kt` |
| GATT client, liveness probe | `transport/ble/BleStreamTransport.kt`, `transport/ble/IdleProbe.kt` |
| Server scan | `ui/screens/hosteditor/BleScanDialog.kt` |

The protocol and what must stay compatible with servers are specified in the
ble-term repository (`docs/protocol.md`, `docs/compatibility.md`);
`BleStreamLinkTest` uses the same test vectors as the server. Unit tests:

```
./gradlew :app:testOssDebugUnitTest --tests 'org.connectbot.transport.ble.*' \
    --tests 'org.connectbot.transport.BleSshTest' \
    --tests 'org.connectbot.ui.screens.hosteditor.BleScanDialogStateTest'
```
