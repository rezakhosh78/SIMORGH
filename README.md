# 🐦‍🔥 SIMORGH VPN

**SIMORGH VPN** is a multi-engine Android VPN client designed for normal connectivity, restricted networks, Iran national intranet conditions, international internet outages MSP-Engine, XRAY configs, and NipoVPN profiles.
---

## ⚠️ Donate

USDT BEP20:

```text
0x304B5D9e118732C98FA60c473A763aD5076FFfb0
```

This README is based on the latest provided project version:

```text
Version Name: 1.1.23.44
Version Code: 12344
Application ID: com.rkh.simorgh
Minimum SDK: 29
Target SDK: 35
```

> ⚠️ Important: if there is no active network at all, no app can connect. However, when Iran's national intranet / local network / domestic routes are still available while international internet access is blocked or heavily restricted, the **Advance** page is designed to scan routes, find clean IPs, and provide a method for accessing websites.

---

## ✨ Quick Overview

SIMORGH has three main pages:

| Page | Main Purpose |
|---|---|
| 🚀 **Simple** | Fast one-click XRAY config connection |
| 🧠 **Advance** | MSP-Engine ,Iran national intranet, international outage conditions, IP scanning, Proxy/VPN, and CF Config |
| 🧩 **NipoVPN** | `nipovpn://` profile support powered by the NipoVPN core |

---

## 🔥 Main Features in Version 1.1.23.44

- 🚀 Fast connection through the **Simple** page
- 🧠 **Advance** mode for Iran national intranet and international internet outage conditions
- 🧩 Full **NipoVPN** support
- 📥 `nipovpn://` profile import
- 🧾 Save, select, edit, delete, and export NipoVPN profiles
- 📡 Real Xray ping for Simple configs
- 🧪 Stable **3x Xray Ping** validation
- 🧯 Crash Guard for Ping All, background ping scan, and Connect Scan
- 🔁 **Next Healthy** button to switch to the next healthy config
- 🧹 **Clear Cache** without deleting configs
- 📋 Simple config list shown as `Config 1`, `Config 2`, ...
- 📶 **Ping All** for Simple configs
- 🇮🇷 **ServerLess 🇮🇷 / IRAN IPS** mode
- 🌐 Google DNS for Simple Normal mode
- 🧠 Health and latency cache
- 📶 Background ping refresh after connection
- 🛰️ **RKh-MSP** engine in Advance
- 🔎 ISP and SNI based IP scanning
- 🧱 Proxy Mode and VPN Mode
- 🔌 Local SOCKS5 and HTTP proxy support
- ☁️ **CF Config** for VLESS WS TLS with IP Memory
- 💾 IP Memory for saving clean IPs
- 🧭 Routing Strategy selection
- 📊 Live download/upload speed
- 🛡️ SIMORGH app is excluded from its own VPN tunnel to prevent loops and improve scanning

---

# 📱 Main App Pages

## 1. 🚀 Simple

The **Simple** page is designed for fast connection using XRAY configs.

The user does not need to manage complex settings. The app downloads configs from the internal subscription, tests them, and connects to a reachable healthy config.

### Simple Features

- One-click connection
- Internal subscription source
- **Update** button
- **Next Healthy** button
- **Clear Cache** button
- Config count display
- Selected config display
- Ping display
- Connection timer
- Config list opens by tapping the Config area
- Original config names are hidden and shown as `Config 1`, `Config 2`, ...
- **Ping All** button
- Connect to a specific config from the list
- ServerLess support
- Stable real Xray Ping scan

### Simple Subscription

In the Simple page, XRAY configs are received and managed through the app's internal system.

### Simple Normal Mode

In Normal mode, Simple uses XRAY configs from the subscription.

Normal mode features:

- Downloads configs from the subscription
- Tests configs using Xray Ping
- Connects to the first reachable config
- Continues scanning after connecting
- Prepares cache for Next Healthy
- Uses Google DNS:

```text
8.8.8.8
8.8.4.4
```

### Simple ServerLess Mode

Simple includes a dropdown for **ServerLess**.

Display name:

```text
ServerLess(Patterniha) 🇮🇷
IRAN IPS
```

ServerLess usage:

- Designed for special Iran-related routes
- Uses the bundled internal ServerLess config
- Runs Xray with a TUN/tun2socks compatible structure
- Useful when the ServerLess config works better than normal subscription configs

> ServerLess usually has only one config, so **Next Healthy** is disabled for ServerLess and is intended for Simple Normal mode.

---

## 2. 🧠 Advance

The **Advance** page is the key section for special Iran network conditions.

It is designed for cases where international internet access is blocked, heavily restricted, or unstable, while Iran's national intranet / domestic network / local routes are still available. In such cases, Advance uses the **RKh-MSP** engine, IP scanning, SNI selection, and route building to provide a method for accessing websites.

> ✅ **Advance is suitable for Iran national intranet conditions, full international internet outages, and severe internet restrictions, as long as the local network or required route is still available.**

### Advance Features

- **RKh-MSP** engine
- ISP selection
- SNI selection
- IP scan on port 443
- Manual IP support
- Scan speed selection
- Max IP count selection
- Proxy Mode
- VPN Mode
- Internal SOCKS5 proxy
- Internal HTTP proxy
- IP Memory
- Next IP
- Ping All for clean IPs
- Copy and Clear for IP Memory
- Routing Strategy
- CF Config for VLESS
- Active route display
- Scanned / Clean / Saved counters
- Live speed display
- Background scanning
- SIMORGH is excluded from its own VPN tunnel for more accurate scanning

### Proxy Mode in Advance

In **Proxy Mode**, the app only starts the local proxy.

Ports:

```text
SOCKS5: 127.0.0.1:9990
HTTP:   127.0.0.1:9991
```

Proxy Mode is useful for:

- Using SIMORGH with apps like v2rayNG
- Testing the RKh-MSP route without starting a full Android VPN
- Debugging
- Cases where only a local proxy is needed

### VPN Mode in Advance

In **VPN Mode**, all Android traffic is routed through SIMORGH.

General flow:

```text
Android TUN
   ↓
tun2socks
   ↓
Xray
   ↓
Local RKh-MSP Proxy
   ↓
Clean IP Pool
```

VPN Mode is useful for:

- Full-device tunneling
- Connecting directly from SIMORGH
- Using discovered clean IPs
- Easier usage compared to Proxy Mode

### Advance Settings

Settings are available from the Settings icon.

#### ISP

Select the ISP used for IP scanning.

Default:

```text
AbrArvan CDN and IaaS
```

#### SNI

Select SNI values for TLS testing.

Default:

```text
chatgpt.com
```

#### Manual IP

You can manually paste IPs, CIDR ranges, or IP ranges.

When Manual IP mode is enabled:

- ISP range scanning is skipped
- Manual IPs are merged into IP Memory
- The app tests only the provided manual IPs

#### Scan

Scan options:

- Slow
- Normal
- Fast
- Max IPs

Default maximum:

```text
33000
```

#### Proxy

Choose the local proxy protocol:

- SOCKS5
- HTTP

#### Route

Available routing strategies:

| Option | Description |
|---|---|
| Default | Tries clean/saved IPs for the requested host and keeps the working route |
| Random | Random selection from healthy IPs |
| Round Robin | Rotates through healthy IPs |
| Least Loss | Chooses the route with lower failure rate |
| Lowest Latency | Chooses the route with the lowest latency |
| Hybrid Score | Combines quality, latency, and failures for better route selection |

---

## 3. 🧩 NipoVPN

The **NipoVPN** page is one of the most important additions in the latest SIMORGH version.

It supports `nipovpn://` profiles and uses the native NipoVPN core.

The NipoVPN core is bundled internally with the app, and its default config is managed inside SIMORGH.

### NipoVPN Traffic Flow

```text
Android VPN TUN
   ↓
tun2socks
   ↓
Xray local SOCKS inbound
   ↓
Xray SOCKS outbound
   ↓
NipoVPN SOCKS5 Agent
```

Internal NipoVPN port:

```text
127.0.0.1:9992
```

Displayed in the UI as:

```text
SOCKS5 9992 → XRAY
```

### NipoVPN Features

- Dedicated **NipoVPN** tab
- NipoVPN Agent connection
- `nipovpn://` link import
- **Add Profile** button
- Profile list
- Profile selection
- Profile editing with the pencil icon
- Profile export/copy with the copy icon
- Profile delete
- Profile save
- NipoVPN server test
- Config reset
- Ping display
- Nipo Server display
- Local SOCKS5 display
- `NipoVPN connected` status
- Google DNS
- App excluded from VPN loop

### Editable NipoVPN Fields

After importing a profile, these fields can be edited:

- Name
- Token
- Protocol
- Fake URLs
- Methods
- Endpoints
- Timeout
- Pull Timeout
- Tunnel Enable
- Connection Reuse
- TLS Enable
- TLS Verify Peer
- TLS Cert File
- TLS Key File
- TLS CA File
- Log Level
- Server IP
- Server Port
- HTTP Version
- User Agent

---

# ☁️ CF Config

**CF Config** is available inside the Advance page.

It is designed for Cloudflare VLESS WS TLS configs.

Displayed title:

```text
CF Config
Clouflare Vless Ws TLS Config
```

### CF Config Features

- Enable/disable CF Config
- Paste VLESS config
- Use clean IPs from IP Memory
- Test latency for IPs
- **Latency All** button
- **Connect** button
- Sort by Latency
- Replace only the VLESS address with the selected clean IP

> In CF Config, only the VLESS address is replaced by the clean IP. SNI, Host, and Path remain from the original config.

---

# 💾 IP Memory

**IP Memory** stores and manages clean IPs.

### IP Memory Features

- Displays number of clean IPs
- Shows latency when available
- **Show / Hide** button
- **Ping All** button
- **Copy** button
- **Clear** button
- Used by Advance
- Used by CF Config
- Keeps clean IPs for later connections

---

# 📊 Live Speed

SIMORGH displays live traffic speed:

```text
↓ Download
↑ Upload
```

This is used in:

- Simple XRAY
- Advance VPN / Proxy
- NipoVPN

---

# 🧪 Simple Stability Improvements

For better Simple stability, this README includes the following improvements:

- Crash guards around **Ping All**
- Crash guards around background ping scan
- Crash guards around shuffled Connect Scan
- Reduced strict 3x Xray ping concurrency to avoid native process/OOM restarts
- Timeout around each 3x Xray ping validation
- Cancels the previous Simple background ping scan before starting a new Connect scan
- Guards latency-cache writes
- Guards worker channel sends

---

# 📘 How to Use

## 🚀 How to Use Simple

1. Open the app.
2. Go to the **Simple** tab.
3. If this is your first run, tap **Update**.
4. Tap the **Config** area to open the config list.
5. Tap **Ping All** to test all configs.
6. Tap the big Connect button.
7. The app tests configs using real Xray Ping.
8. The first reachable config is selected.
9. After connection, background scanning continues.
10. If the connection becomes weak, tap **Next Healthy**.

### Using Clear Cache in Simple

When ping results look wrong or a config has ping but does not connect:

1. Tap **Clear Cache**.
2. Only health and ping memory are cleared.
3. Configs are not deleted.
4. Run Ping All or Connect again.

---

## 🇮🇷 How to Use ServerLess in Simple

1. Open the **Simple** tab.
2. Turn **ServerLess** ON.
3. `IRAN IPS` is displayed.
4. Tap Connect.
5. The app runs the bundled internal ServerLess config.

> ServerLess usually has one config, so Next Healthy is not useful for this mode.

---

## 🧠 How to Use Advance During Iran National Intranet / International Internet Outage

Use this method when international internet access is blocked or heavily restricted, but Iran national intranet / local network is still available.

1. Open the **Advance** tab.
2. Open **Settings**.
3. Choose an ISP from the **ISP** section.
4. Choose one or more SNI values from the **SNI** section.
5. If needed, add manual IPs in the **Manual** section.
6. Choose scan speed from the **Scan** section.
7. Choose the proxy protocol from the **Proxy** section:
   - SOCKS5 for `127.0.0.1:9990`
   - HTTP for `127.0.0.1:9991`
8. Select **Proxy Mode** if you only need a local proxy.
9. Select **VPN Mode** if you want the whole phone to be tunneled.
10. Tap the big Connect button.
11. The app starts scanning.
12. The first clean IP is added to the route.
13. Scanning continues in the background.
14. Use **Next IP** to switch routes.

---

## 🔌 How to Use Proxy Mode with v2rayNG

1. Open **Advance** in SIMORGH.
2. Select **Proxy** mode.
3. Go to Settings > Proxy and choose:
   - SOCKS5
   - HTTP
4. Tap Connect.
5. In v2rayNG, create a local proxy:

```text
SOCKS5: 127.0.0.1:9990
HTTP:   127.0.0.1:9991
```

6. If v2rayNG works with this proxy, the RKh-MSP route and IP pool are working.

---

## 🧩 How to Use NipoVPN

1. Open the **NipoVPN** tab.
2. Paste your profile link into the field:

```text
nipovpn://
```

3. Tap **Add Profile**.
4. Tap **Show Profiles**.
5. Select the profile you want.
6. Tap the pencil icon `✎` to edit.
7. Tap the copy icon `⧉` to export/copy the profile.
8. Tap **Test** if you want to test the server.
9. If you changed fields, tap **Save Profile**.
10. Tap Connect.
11. The status should become:

```text
NipoVPN connected
```

---

## ☁️ How to Use CF Config

1. First run Advance scanning so IP Memory is filled.
2. Open **CF Config**.
3. Enable CF Config.
4. Paste your VLESS WS TLS config.
5. Tap **Latency All**.
6. Choose the IP with better latency.
7. Tap **Connect**.

> Only the VLESS address is replaced with the clean IP. SNI, Host, and Path remain unchanged.

---

# 🛠️ Troubleshooting

## Simple does not connect

- Tap Update
- Tap Ping All
- Tap Clear Cache
- Try Connect again
- Use Next Healthy if you have cached healthy configs
- Test ServerLess ON/OFF

## Next Healthy does not work

- Next Healthy is only for Simple Normal
- Run Ping All first
- If healthy cache is empty, the app will ask you to run Ping All

## ServerLess does not connect

- Toggle ServerLess OFF and ON
- Clear Cache
- Run Connect again
- Remember that ServerLess usually has only one config

## Advance does not work during Iran national intranet conditions

- Change ISP
- Change SNI
- Try Manual IPs
- Change Scan Speed
- Test Proxy Mode separately with v2rayNG
- Change Route Strategy
- Clear IP Memory and scan again

## NipoVPN does not connect

- Make sure the link starts with `nipovpn://`
- Tap Add Profile
- Select the profile
- Check Server IP and Server Port
- Run Test
- Tap Save Profile
- Connect again

## CF Config does not work

- Fill IP Memory through Advance first
- Make sure the config starts with `vless://`
- Tap Latency All
- Choose a healthier IP
- Check SNI / Host / Path in the original config

---

# 🧱 Technical Structure

## Important Internal Data

SIMORGH manages the required IP scan, SNI, ServerLess, and NipoVPN data internally, so source file paths are not exposed in this README.

## Traffic Flows

### Simple Normal

```text
Android TUN
   ↓
tun2socks
   ↓
Xray SOCKS
   ↓
Selected XRAY Config
```

### Simple ServerLess

```text
Android TUN
   ↓
tun2socks
   ↓
Xray mixed inbound
   ↓
ServerLess routing
```

### Advance VPN Mode

```text
Android TUN
   ↓
tun2socks
   ↓
Xray
   ↓
RKh-MSP Local Proxy
   ↓
Clean IP Pool
```

### Advance Proxy Mode

```text
Local App / v2rayNG
   ↓
127.0.0.1:9990 SOCKS5
or
127.0.0.1:9991 HTTP
   ↓
RKh-MSP Clean IP Pool
```

### NipoVPN

```text
Android TUN
   ↓
tun2socks
   ↓
Xray 127.0.0.1:10808
   ↓
NipoVPN SOCKS5 127.0.0.1:9992
   ↓
NipoVPN Agent
```

---

# 📦 Installation

1. Install the APK.
2. Grant VPN permission.
3. Use **Simple** for fast XRAY connection.
4. Use **Advance** for Iran national intranet and international internet outage conditions.
5. Use **NipoVPN** for `nipovpn://` profiles.

---

# 🔐 Required Permissions

- Internet access
- VPN permission
- Network state access
- Foreground Service for stable connection

---

# 👨‍💻 Made By

```text
Made By RKh!
Telegram: @pingplas_channel
```

---

# ⚠️ Disclaimer

This project is provided for educational, research, and legal use only. Users are responsible for how they use the app. Please use this tool according to the laws of your region.

---

# ⭐ Support

If SIMORGH is useful to you, please give the project a ⭐ on GitHub.

```text
SIMORGH VPN
Fast. Smart. Advanced. NipoVPN Ready.
```

