# Architecture

## High-level shape

```
                 ┌──────────────────────────────┐
                 │  Android telephony framework │
                 │  (ImsManager, ImsPhone,       │
                 │   ImsPhoneCallTracker, …)     │
                 └──────────────┬───────────────┘
                                │  AIDL (ImsService surface)
                                ▼
       ┌─────────────────────────────────────────────────┐
       │                HamelinPortsImsService                 │
       │                                                  │
       │   ┌────────────────┐    ┌─────────────────┐    │
       │   │ MmTelFeature    │    │ Registration    │    │
       │   │  + CallSession  │    │ Controller      │    │
       │   │  + IncomingCall │    │  (REGISTER, AKA,│    │
       │   │  + Sms          │    │   IPsec, refresh│    │
       │   └────────┬────────┘    │   + 5xx retry)  │    │
       │            │             └────┬────────────┘    │
       │            │                  │                  │
       │   ┌────────▼──────────────────▼─────────┐       │
       │   │ jni/hamelinports_ims_jni.cpp              │       │
       │   │  → reSIProcate Stack + DUM           │       │
       │   │  → AKA / IPsec helpers               │       │
       │   └──────────────────────────────────────┘       │
       │                                                  │
       │            android.telephony.imsmedia            │
       │             (RTP/RTCP for audio + video)         │
       └────────────────────────┬─────────────────────────┘
                                │ IImsModemBridge AIDL
                                ▼
            ┌─────────────────────────────────┐
            │  Per-device modem-bridge service │
            │  (vendor-specific, or absent →   │
            │   in-process no-op)              │
            └────────────┬─────────────────────┘
                         │  vendor-specific binder
                         ▼
                ┌────────────────────┐
                │  Vendor RIL / modem │
                └────────────────────┘
```

## Design tenets

1. **Carrier-agnostic core.** No carrier-specific identifiers in
   source — Telefónica DE was the bring-up target, but every
   protocol decision is justified by RFC / 3GPP spec, not by
   matching one P-CSCF's quirks.
2. **Modem-agnostic core.** All vendor-proprietary modem
   co-ordination lives behind the `IImsModemBridge` AIDL in a separate
   per-device APK. The IMS service core can be reused on any device
   that ships either a bridge implementation or none (AOSP-conformant
   modems).
3. **No vendor blob runtime dependencies.** `HamelinPortsImsService`
   itself loads no proprietary libraries — REGISTER, calls, SMS, and
   media all run on AOSP framework APIs + reSIProcate (Apache 2.0).

## Components

### `aidl/` — `hamelinports-ims-aidl` java_library

Two AIDL interfaces, compiled to a Java stub library that is
`static_libs`-referenced by both HamelinPortsImsService and any per-device
bridge implementation.

- `IImsModemBridge` — one-way calls from HamelinPortsImsService to the
  bridge: `start`, `stop`, `sendRegistration`, `sendPreference`,
  `register/unregisterCallback`. Constants `RAT_LTE` / `RAT_NR` /
  `RAT_IWLAN` are first-class on the interface.
- `IImsModemBridgeCallback` — bridge → HamelinPortsImsService for
  vendor-specific events that don't fit any AOSP AIDL.

### `src/org/hamelinports/ims/`

- `HamelinPortsImsService.java` — the AIDL-discoverable `ImsService` that
  the framework's `ImsResolver` binds.
- `HamelinPortsMmTelFeature.java` — capability advertisement, MO/MT call
  routing, SRVCC notification handlers.
- `ImsRegistrationController.java` — REGISTER lifecycle per slot on one
  HandlerThread: AKA handshake, IPsec SA setup, IMS-PDN re-bind on LTE
  return, and renewal across deep sleep (one `ELAPSED_REALTIME_WAKEUP`
  check alarm, a timed partial wakelock per transaction, lapse checks on
  screen on / Doze exit / PDN change / MO request). It runs only while the
  framework enables IMS on the slot and the slot has an active
  subscription; a slot without a SIM stays idle (no retry, no alarm).
  With `persist.lineage.ims.iwlan_fallback.sec` set, a failed IWLAN data
  path (the tunnel looks up, the P-CSCF does not answer) is reported as
  deregistration reason 1402, and QNS hands IMS over to cellular. An
  IMS-PDN watchdog (exact alarm) re-requests a PDN stuck without a
  CONNECTED transport.
- `CarrierConfigInjector.java` — one per process: pushes the carrier-config
  overrides (kernel-supported IPsec algorithms, DPD interval, the QNS
  fallback rules, per-carrier assets) as a persistent override.
- `reg/` — `RefreshPolicy` (renewal window, lapse rule, retry floor and
  budget), `RegistrationState`, `SlotActivation` (start/stop/restart per
  subscription, credential status → retry or idle), `AttemptReadiness`
  (no IMS network handle, P-CSCF or credentials → the attempt is not sent
  and goes through the retry budget), `IwlanFallbackTracker` (transport
  failures on IWLAN → when to report, pending, taken / not taken) and
  `ImsPdnWatchdogPolicy` (when the watchdog tears down); pure Java,
  host-tested.
- `config/` — `IwlanDpdInterval`, `IwlanFallbackPolicy` (the property → the
  QNS rule arrays, or empty arrays to clear stale ones) and
  `CarrierConfigPushLedger` (when an override is pushed again); pure Java,
  host-tested.
- `HamelinPortsCallSession.java` / `HamelinPortsIncomingCallSession.java` —
  MO and MT IMS call profiles + SDP negotiation. The MT session follows
  `call/MtCallState` (one terminated report; a failure after accept also ends
  the dialog with a BYE); both refuse an onFailure without a failure status
  (`call/SipStatus`); pure Java, host-tested.
- `HamelinPortsSmsImpl.java` — SIP MESSAGE-based MO and MT SMS; an RP-ERROR
  is decoded in full (`sip/RpError`), mapped to retry over IMS / CS fallback
  by the carrier's RP-Cause lists (`sip/RpErrorPolicy`, with
  `persist.lineage.ims.sms_rp_fallback`), and each send gets exactly one
  outcome (`sip/PendingMoSms`); all three pure Java, host-tested.
- `sip/` — SIP/SDP helpers, native handle lifetimes,
  `HamelinPortsSipStack` (the JNI front door), `SecurityClientOffer` (the
  UE's SPIs and ports in Security-Client; pure Java, host-tested).
- `net/` — IPsec SA setup helpers, ESP routing fix.
- `modem/` — bridge factory + AIDL-side proxies (see below).

### `jni/hamelinports_ims_jni.cpp`

Native bridge between the Java service and reSIProcate. Hosts the
`SipStack` and `DialogUsageManager`, wires registration/INVITE/MESSAGE
handlers, exposes JNI methods for outbound REGISTER, INVITE, ACK, BYE,
re-INVITE, MESSAGE, refresh.

Every socket the stack creates passes a per-slot socket hook that pins it
with `android_setsocknetwork` to the slot's IMS network (TCP sockets also get
`TCP_USER_TIMEOUT`): listening, accepted and outgoing TCP sockets, the
transport selector's connected-UDP route probe (it picks the Via source
address, so an unpinned probe picks the default network's address) and
c-ares' DNS sockets. The last two are handed to the hook by our
reSIProcate tree (`TransportSelector::setSocketFunc`,
`ares_set_socket_configure_callback`). The network handle is a parameter of
the stack start, which refuses to start without one, and is cleared when the
stack stops. The process is never bound to a network: a socket the hook
cannot pin is discarded (its descriptor is swapped for a dead socket,
`jni/sip_socket_discard.h`), so the request on it fails and the registration
policy retries. As a backstop the JNI library links with
`-Wl,--wrap=socket,--wrap=connect,--wrap=sendto,--wrap=sendmsg`
(`jni/sip_socket_guard.cpp`): an IP socket the hook has not pinned cannot
`connect()` and cannot `sendto()`/`sendmsg()` to an address (ENETUNREACH and a
log line; a send without an address goes to the connected peer and passes),
so a path that bypasses the hook fails loudly instead of leaving on the
default network.

Upcalls into Java (`jni/call_listener_slot.{h,cpp}`): every listener method ID
is resolved once, in `JNI_OnLoad`, from the listener interface
(`CallSessionListener`, `IncomingCallListener`, `SmsSessionListener`,
`RegistrationListener`, `AkaProvider`). An ID taken from one implementation's
class would be dispatched by that class's vtable slot on every other one (the
MO and MT call sessions are different classes). Each listener sits in a
`ListenerSlot`: Java threads replace it under a mutex, and each upcall on the
DUM thread works on its own local reference, so a replacement never frees a
reference in use. Host test `HamelinPortsImsListenerSlotTest` (fake JNIEnv);
`tests/check-jni-listener-ids.sh` fails on `GetObjectClass` in `jni/`, on a
`GetMethodID` call outside the resolver, or on a file-scope or static
`jmethodID` cache.

Gates: `tests/check-process-binding.sh` fails on any process-binding API in
`src/` or `jni/`; `tests/check-sip-socket-sites.sh` fails unless every
`socket()`/`accept()`/`socketpair()`/`connect()`/`sendto()`/`sendmsg()`/
`sendmmsg()` site in the compiled reSIProcate and c-ares sources and in `jni/`
is listed with its status in `tests/sip-socket-sites.txt` (hooked sites with
the hook call as evidence) and the probe, DNS and guard wiring is in place.

Includes carrier-quirk fixes accumulated during bring-up (in-dialog
TCP routing, SDP offer/answer matching, 5xx Retry-After parsing).

### `jni/ims_xfrm`, `jni/ims_oemipc/ims_ipsec_setup.c`

Standalone C tools invoked via `Runtime.exec` for kernel XFRM
operations the IpSecManager API doesn't fully cover.

## Modem-bridge separation

Vendor-proprietary modem-coordination surfaces that AOSP's `IRadioIms`
does not model are intentionally kept out of this APK. Without those
messages, on hardware where the modem doesn't autonomously observe IMS
registrations the EPC routes MT voice as CSFB even when our IMS REGISTER
succeeded; with them, routing flips to IMS.

The split:

```
HamelinPortsImsService (this repo) --AIDL--> per-device bridge service
                                             (under the device tree)
```

`ImsModemBridgeFactory.create()`:

1. Queries `PackageManager.queryIntentServices` for the action
   `org.hamelinports.ims.modem.action.BIND_BRIDGE`.
2. If a service is registered, `bindService()` it. Returns a
   `BoundImsModemBridge` proxy that forwards calls to the latched
   remote (and silently drops calls before the binding completes —
   safe under the AIDL's `oneway` contract).
3. If no service is registered, returns `NoOpImsModemBridge` — an
   in-process stub. The IMS service runs unchanged.

Per-device bridge implementations are free to differ on the wire:
each chooses whatever transport its vendor RIL exposes (vendor RIL
channels, QMI commands, etc.); a fully AOSP-conformant device most
likely ships nothing at all.

## Porting to a new device

### Decision tree

```
Does the device's vendor RIL publish AOSP's android.hardware.radio.ims.IRadioIms?
├─ Yes (most modern Qualcomm / MediaTek): no bridge needed; AOSP IRadioIms
│       handles MT routing, SRVCC, etc. NoOpImsModemBridge stays in place.
└─ No  → does the device run a Samsung-derived RIL (Shannon, secril,
         secril-on-Unisoc)?
        ├─ Yes: implement an IImsModemBridge that emits Samsung IIL byte frames
        │       on top of vendor.samsung.hardware.radio.channel — see "Samsung
        │       IIL bridge" below; the byte protocol is shared, only transport
        │       and header size vary per chipset family.
        └─ No  → write a fully custom bridge against whatever vendor surface
                 the modem actually exposes (QMI, AT-over-vendor-binder, etc.).
                 No shared infrastructure helps here.
```

### AOSP-conformant modems

Drop into `device.mk`:

```mk
PRODUCT_PACKAGES += HamelinPortsImsService privapp-permissions-org.hamelinports.ims
PRODUCT_PROPERTY_OVERRIDES += persist.dbg.volte_avail_ovr=1
```

### Custom-bridge devices (non-Samsung, non-AOSP)

1. Create `device/<vendor>/<device>/<Vendor>ImsModemBridge/`.
2. `static_libs: ["hamelinports-ims-aidl"]` in the bridge's `Android.bp`.
3. Implement `IImsModemBridge.Stub` for the start/stop +
   sendRegistration + sendPreference methods, plus a `Service`
   declaring an `<intent-filter>` for the `BIND_BRIDGE` action.
4. Add the APK to `device.mk` PRODUCT_PACKAGES.

### Samsung IIL bridge — byte protocol shared, transport varies

All Samsung-RIL devices we've seen route IMS-state notifications to the
modem through the same opaque "IIL" byte-frame channel, but the binder
transport that carries the bytes — and the size of the IIL frame header
itself — differs per chipset family.

**Transport.** Look up the vendor RIL's published service in
`/vendor/etc/vintf/manifest/vendor.samsung.hardware.sehradio_manifest_*.xml`:

| Stack / chipset family            | Transport      | Service name                                                                  | Java API                                  |
|-----------------------------------|---------------|--------------------------------------------------------------------------------|--------------------------------------------|
| Shannon (e.g. Exynos 9611, A51)   | **AIDL**      | `vendor.samsung.hardware.radio.channel.ISehRadioChannel/imsd{,2}`              | `ServiceManager.checkService` + `Parcel`   |
| Samsung-RIL on Unisoc (e.g. UMS512, gta8) | **HIDL**  | `vendor.samsung.hardware.radio.channel@2.0::ISehChannel/imsd{,2}`              | `HwBinder.getService` + `HwParcel`         |

Both expose the same two functional methods on top of HIDL/AIDL housekeeping:

- `setCallback(ISehChannelCallback)` — transaction code 1 (HIDL: `FIRST_CALL_TRANSACTION`)
- `send(vec<uint8>)` — transaction code 2

`send()` carries one opaque IIL frame; the modem-side firmware parses
the bytes inside the proprietary `secril_*` modules.

**IIL frame format.** The frame is `[header] + [body]`, where the header
size depends on the chipset family:

| Stack family               | Header size | Header layout                                                          |
|----------------------------|-------------|------------------------------------------------------------------------|
| Shannon                    | **5 bytes** | `[len_lo, len_hi, mainCmd=0x70, subCmd, cmdType=0x03 EXEC]`            |
| Samsung-RIL on Unisoc      | **7 bytes** | `[len_lo, len_hi, seq=0, aseq=0, mainCmd=0x70, subCmd, cmdType=0x03]`  |

`len` is total frame length (header + body) as little-endian u16.
`seq`/`aseq` are unused on uplink (always zero on uplink frames; modem
uses them for paired req/rsp tracking which we don't need for
fire-and-forget NOTIs).

**Sub-command bodies are identical** across the two families. The
load-bearing ones for MT IMS-call routing are:

- `IPC_IIL_REGISTRATION` (sub=0x01, cmdType=3 NOTI), 268-byte body:
  - `body[0]` LimitedMode (0)
  - `body[1]` capability flags bitmap (VOLTE 0x01 / SMSIP 0x02 / RCS 0x04 / PSVT 0x08 / CDPN 0x20)
  - `body[2]` PdnType (0)
  - `body[3]` FeatureTag bitmap (CS 0x01 / SMSIP 0x02 / VOLTE 0x04 / VIDEO 0x08 / MMTEL 0x10)
  - `body[4..9]` zeros / Ecmp / EpdgMode / ErrorCode / reserved
  - `body[10]` IMPU UTF-8 length (max 256)
  - `body[11..n]` IMPU UTF-8 bytes
  - `body[0x10B]` RegiRat (`RAT_LTE=14`, `RAT_NR=20`, `RAT_IWLAN=18`)
- `IPC_IIL_PREFERENCE` (sub=0x06, cmdType=3 NOTI), 14-byte body — VoLTE / VT / SMS-over-IMS preference flags. Recommended on bridge bind to advertise capabilities.
- `IPC_IIL_CONNECTED` (sub=0x12) — handshake on bridge bind. Not strictly required but stock Samsung sends it.

Everything else (`RETRYOVER`, `SSAC`, `ISIM_LOADED`, `EMC_ATTACH_AUTH`,
`VONR_USER_STATUS`, `SIP_SUSPEND`) is informational and can be no-op'd
for first bring-up.

**Implementation pattern.** Two reference implementations live in-tree:

- `device/samsung/a51/SamsungImsModemBridge/` — Shannon AIDL transport, 5-byte IIL header.
- `device/samsung/gta8/SamsungImsModemBridge/` — Samsung-RIL-on-Unisoc HIDL transport, 7-byte IIL header.

Both share the same `IImsModemBridge.aidl` contract and the same
sub-command body layouts; they diverge only in the binder transport
helpers and the `HEADER_LEN`/`TOTAL_LEN` constants. New Samsung-derived
ports should pick whichever reference matches their chipset family,
copy it, and bump the framing constants if needed. Once two devices
have shipped working ports the shared bits are due to be extracted into
an external repo (see memory note `project_samsung_ims_bridge_shared_repo_question`).

**Sepolicy.** Per-device bridges should bundle their sepolicy files
next to the bridge source rather than scatter rules across the device
tree's `sepolicy/` directory. The recommended layout (used by
gta8's `SamsungImsModemBridge/`):

```
SamsungImsModemBridge/
├── BoardConfig.mk              # registers the dirs below
└── sepolicy/
    ├── system_ext_public/      # types shared with vendor
    ├── system_ext/             # platform_app/system_app rules
    └── vendor/                 # rild ↔ bridge binder round-trip rules
```

The device `BoardConfig.mk` then `include`s the bridge's `BoardConfig.mk`,
which appends to `SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS`,
`SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS`, and `BOARD_VENDOR_SEPOLICY_DIRS`.
This keeps the bridge self-contained and easier to extract to a
shared repo later.

Rules needed (in addition to whatever the bridge implementation
specifically requires):

AIDL transport (Shannon, A51-style):

```
allow system_app hal_radio_service:service_manager find;
binder_call(system_app, rild)
allow rild system_app:binder call;
```

HIDL transport (Samsung-RIL-on-Unisoc, gta8-style — with the bridge
running as `platform_app` because `sharedUserId="android.uid.system"`
silently breaks the AM bind on A16+):

```
hwbinder_use(platform_app)
allow platform_app hal_sehradio_channel_hwservice:hwservice_manager find;
binder_call(platform_app, rild)
allow rild platform_app:binder call;
```

The `hal_sehradio_channel_hwservice` type itself must be declared in
`system_ext_public/` so both the system-side `find` rule and the
vendor-side `add_hwservice()` see the same type at compile time.

## Build dependencies

- Carrier-agnostic: `HamelinPortsImsService` + `hamelinports-ims-aidl` +
  reSIProcate (`external/resiprocate`) + the AOSP imsmedia library.
- Per-device: a bridge APK as above, plus carrier-config XML if the
  device's carrier needs non-default IMS provisioning.

## Sepolicy

`HamelinPortsImsService` runs in `system_app` (sharedUserId=`android.uid.system`,
platform certificate). Per-device bridges using the same UID inherit
all rules; the only common per-device add typically needed is
`hal_radio_service:service_manager find` if the bridge needs to lookup
a vendor binder service — see `device/samsung/a51/sepolicy/private/
system_app.te`.

## Diagnostics

`dumpsys activity service org.hamelinports.ims/.HamelinPortsImsService` is the
authoritative source for current state. It prints the process network
binding (`processBoundNetwork=none` expected) and, per slot:

- usable + renewal-pending, and the state line (generation, age,
  remaining, in-flight transaction, pending retry, retry budget)
- P-CSCF list
- last-registered / last-deregistered timestamps + counts
- refresh counters (sent / not sent) and fresh attempts
- next check alarm
- the `refresh_new_sa` experiment switch and the network's last answer to
  a refresh that offered a new Security-Client
- `iwlanFallback`: enabled and hold time, counted transport failures,
  reports / taken / not taken, the last report and a pending one

The JNI logs, one line each and with identities masked
(`jni/sip_log_redact.h`, host test `HamelinPortsImsLogRedactTest`): the
Contacts of every REGISTER 200 OK (count, `+g.3gpp.smsip`, address family and
port), and the PAI host plus Warning / Reason / P-Debug-Info of the answer to
an MO MESSAGE and of every inbound MESSAGE (`sm-submit-report` marked). The
app logs the decoded RP-ERROR with its raw octets (no address element).

## Known limitations

- **SRVCC** mid-call: the framework dispatches to our handler but on
  some modems `RIL_UNSOL_SRVCC_STATE_NOTIFY` is not emitted even with
  the AIDL HAL bound, so calls drop on LTE → 2G transition.
  Investigation ongoing.
- **eSIM / DSDS**: dual-SIM is detected but only the active subscription
  registers. Concurrent IMS on both slots is untested.
