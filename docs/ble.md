# SSH over Bluetooth LE

Hosts with the protocol `blessh` reach a [blessh](https://github.com/lomalkin/blessh)
server over Bluetooth Low Energy, without any IP network. The SSH session is
the usual one (host key check, authentication, terminal); only its byte stream
runs over Bluetooth LE: an L2CAP channel, or the GATT stream where no channel
can be used. What a client must do is specified in blessh's
[docs/protocol.md](https://github.com/lomalkin/blessh/blob/dev/docs/protocol.md);
this implementation follows it.

## Using it

1. Install the blessh server (Linux or macOS). It prints the server's host key
   fingerprint.
2. In the host editor choose the protocol **blessh**, tap **Scan for Bluetooth
   LE servers**, pick the server and enter the user name. The host field gets
   the server's name, or its Bluetooth address if it advertises no valid name;
   either can also be typed.
3. On the first connection, compare the fingerprint with the server's.

Tested on Android 8, 11 and 17.

Scanning needs the Nearby devices permission; Android 11 and earlier also need
location to be on (the system holds back scan results until it is; the scan
dialog says so and opens the location settings). Connecting to a host saved
with an address needs neither. A host saved with a name is connected to at
the address it had last time; if the server is not there (macOS servers
change their address), the app scans for the name, which needs the same as
scanning.

## Behaviour

* Transports, as the protocol requires: on Android 10 and later the server's
  L2CAP channels, in its order, each checked with the channel handshake
  before SSH starts; a channel that stalls is skipped. Then the GATT stream,
  unless the server has none (a macOS server). Android 9 and earlier use the
  GATT stream only, so they cannot reach a macOS server. The terminal says
  which transport is in use.
* Logins follow the server's SSH configuration: what network SSH allows or
  refuses, BLE does too.
* The GATT stream carries one client per server at a time. A busy server says
  so before the app subscribes; the app then reports it and leaves, without
  disturbing the session that is running. Channels carry one session each.
* No Bluetooth pairing. blessh keeps EATT off on the server, so servers do
  not ask to pair; one that does (EATT turned on in its BlueZ settings),
  during connection setup or later in the session, gets a hint instead of a
  retry loop.
* No port forwarding and no jump hosts. Keystroke timing obfuscation is off:
  it would use much of the link's bandwidth.
* A server that stops (upgrade, restart, shutdown) says so before the link
  ends. On the GATT stream a session that is quiet for 20 s is also probed
  with a write that the server must acknowledge, so a server that went away
  without a word is noticed; a channel reports a lost link itself.
* Throughput is tens of kilobytes per second. New blessh hosts therefore have
  compression on (the server must allow it; OpenSSH does by default); it can
  be turned off per host.

## Code and tests

| Part | Where |
|---|---|
| SSH over a non-TCP stream | `transport/BleSsh.kt`; hooks `directTransportFactory` and `obscureKeystrokeTiming` in `transport/SSH.kt` |
| Finding the server, choosing the transport | `transport/ble/BlesshConnector.kt` |
| INFO and server names (no Android dependencies) | `transport/ble/ServerInfo.kt` |
| Channel handshake (no Android dependencies) | `transport/ble/ChannelHandshake.kt` |
| L2CAP channel transport | `transport/ble/L2capTransport.kt` |
| BleStream state machine (no Android dependencies) | `transport/ble/BleStreamLink.kt` |
| GATT client, liveness probe, pairing requests | `transport/ble/BleStreamTransport.kt`, `transport/ble/IdleProbe.kt`, `transport/ble/PairingWatch.kt` |
| Server scan | `ui/screens/hosteditor/BleScanDialog.kt` |

`ChannelHandshakeTest` and `BleStreamLinkTest` use the protocol's test
vectors. Unit tests:

```
./gradlew :app:testOssDebugUnitTest --tests 'org.connectbot.transport.ble.*' \
    --tests 'org.connectbot.transport.BleSshTest' \
    --tests 'org.connectbot.ui.screens.hosteditor.BleScanDialogStateTest'
```
