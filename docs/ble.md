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

Tested on Android 8, 11 and 17.

Scanning needs the Nearby devices permission; Android 11 and earlier also need
location to be on (the system holds back scan results until it is; the scan
dialog says so and opens the location settings). Connecting to a saved host
needs neither.

## Behaviour

* Logins follow the server's SSH configuration: what network SSH allows or
  refuses, BLE does too.
* One client per server at a time. A busy server says so before the app
  subscribes; the app then reports it and leaves, without disturbing the
  session that is running.
* No Bluetooth pairing. ble-term keeps EATT off on the server, so servers
  do not ask to pair; one that does (EATT turned on in its BlueZ settings),
  during connection setup or later in the session, gets a hint instead of
  a retry loop.
* No port forwarding and no jump hosts. Keystroke timing obfuscation is off:
  it would use much of the link's bandwidth.
* A server that stops (upgrade, restart, shutdown) says so before the link
  ends. A session that is quiet for 20 s is also probed with a write that
  the server must acknowledge, so a server that went away without a word
  is noticed.
* Throughput is tens of kilobytes per second. New BLE hosts therefore have
  compression on (the server must allow it; OpenSSH does by default); it
  can be turned off per host.

## Code and tests

| Part | Where |
|---|---|
| SSH over a non-TCP stream | `transport/BleSsh.kt`; hooks `directTransportFactory` and `obscureKeystrokeTiming` in `transport/SSH.kt` |
| BleStream state machine (no Android dependencies) | `transport/ble/BleStreamLink.kt` |
| GATT client, liveness probe, pairing requests | `transport/ble/BleStreamTransport.kt`, `transport/ble/IdleProbe.kt`, `transport/ble/PairingWatch.kt` |
| Server scan | `ui/screens/hosteditor/BleScanDialog.kt` |

The protocol and what must stay compatible with servers are specified in the
ble-term repository (`docs/protocol.md`, `docs/compatibility.md`);
`BleStreamLinkTest` uses the same test vectors as the server. Unit tests:

```
./gradlew :app:testOssDebugUnitTest --tests 'org.connectbot.transport.ble.*' \
    --tests 'org.connectbot.transport.BleSshTest' \
    --tests 'org.connectbot.ui.screens.hosteditor.BleScanDialogStateTest'
```
