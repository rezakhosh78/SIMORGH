# 🦅 SIMORGH VPN

**SIMORGH VPN** یک کلاینت اندرویدی چندموتوره برای اتصال در شرایط عادی، محدودیت اینترنت، نت ملی ایران، قطعی اینترنت بین‌الملل و استفاده از کانفیگ‌هایMSP-Engine , XRAY و NipoVPN است.

---

## ⚠️ Donate

USDT BEP20:

```text
0x304B5D9e118732C98FA60c473A763aD5076FFfb0
```
این README بر اساس نسخه‌ی جدید پروژه نوشته شده است:

```text
Version Name: 1.1.23.44
Version Code: 12344
Application ID: com.rkh.simorgh
Minimum SDK: 29
Target SDK: 35
```

> ⚠️ نکته مهم: اگر هیچ شبکه‌ای فعال نباشد، هیچ برنامه‌ای امکان اتصال ندارد. اما وقتی **نت ملی ایران / مسیر داخلی / شبکه داخلی** فعال باشد و اینترنت بین‌الملل قطع یا محدود شده باشد، بخش **Advance** برای پیدا کردن مسیر، IP سالم و دسترسی به سایت‌ها طراحی شده است.

---

## ✨ معرفی کوتاه

SIMORGH از سه صفحه اصلی تشکیل شده است:

| صفحه | کاربرد اصلی |
|---|---|
| 🚀 **Simple** | اتصال ساده و سریع با کانفیگ‌های XRAY |
| 🧠 **Advance** | مناسب نت ملی ایران، قطعی اینترنت بین‌الملل، اسکن IP، Proxy/VPN و CF Config |
| 🧩 **NipoVPN** | اتصال با پروفایل‌های `nipovpn://` و هسته NipoVPN |

---

## 🔥 ویژگی‌های اصلی نسخه 1.1.23.44

- 🚀 اتصال سریع با صفحه **Simple**
- 🧠 صفحه **Advance** برای شرایط نت ملی و قطعی اینترنت بین‌الملل
- 🧩 پشتیبانی کامل از **NipoVPN / NIPOVPN**
- 📥 وارد کردن پروفایل‌های `nipovpn://`
- 🧾 ذخیره، انتخاب، ویرایش، حذف و خروجی گرفتن از پروفایل‌های NipoVPN
- 📡 تست Ping واقعی با Xray برای کانفیگ‌های Simple
- 🧪 اعتبارسنجی پایدار با **3x Xray Ping**
- 🧯 Crash Guard برای جلوگیری از کرش هنگام Ping All، اسکن پس‌زمینه و Connect Scan
- 🔁 دکمه **Next Healthy** برای رفتن به کانفیگ سالم بعدی
- 🧹 دکمه **Clear Cache** بدون حذف کانفیگ‌ها
- 📋 نمایش لیست کانفیگ‌های Simple به صورت شماره‌ای مثل Config 1, Config 2
- 📶 Ping All برای تست همه کانفیگ‌های Simple
- 🇮🇷 حالت **ServerLess 🇮🇷 / IRAN IPS**
- 🌐 DNS گوگل برای Simple Normal
- 🧠 کش سلامت کانفیگ‌ها و پینگ‌ها
- 📶 اسکن پینگ در پس‌زمینه بعد از اتصال
- 🛰️ موتور **RKh-MSP** در Advance
- 🔎 اسکن IP بر اساس ISP و SNI
- 🧱 پشتیبانی از Proxy Mode و VPN Mode
- 🔌 پروکسی محلی SOCKS5 و HTTP
- ☁️ بخش **CF Config** برای VLESS WS TLS با IP Memory
- 💾 IP Memory برای ذخیره Clean IP ها
- 🧭 Routing Strategy برای انتخاب مسیر
- 📊 نمایش سرعت زنده Download و Upload
- 🛡️ Exclude شدن خود برنامه از VPN برای جلوگیری از Loop و بهتر شدن اسکن

---

# 📱 صفحات اصلی برنامه

## 1. 🚀 Simple

صفحه **Simple** برای اتصال سریع با کانفیگ‌های XRAY طراحی شده است.

در این صفحه کاربر نیازی به تنظیمات پیچیده ندارد. برنامه کانفیگ‌ها را از سابسکریپشن داخلی دریافت می‌کند، آن‌ها را تست می‌کند و به یک کانفیگ سالم وصل می‌شود.

### امکانات Simple

- اتصال با یک دکمه
- دریافت کانفیگ از Subscription داخلی
- دکمه **Update**
- دکمه **Next Healthy**
- دکمه **Clear Cache**
- نمایش تعداد Config ها
- نمایش Config انتخاب‌شده
- نمایش Ping
- نمایش Timer اتصال
- نمایش لیست کانفیگ‌ها با کلیک روی Config
- مخفی‌سازی نام واقعی کانفیگ‌ها و نمایش به صورت `Config 1`, `Config 2`, ...
- دکمه **Ping All**
- اتصال مستقیم به یک کانفیگ از داخل لیست
- پشتیبانی از حالت ServerLess
- اسکن پایدار با Xray Ping واقعی

### سابسکریپشن Simple

در بخش Simple، کانفیگ‌های XRAY از سیستم داخلی برنامه دریافت و مدیریت می‌شوند.

### حالت Normal در Simple

در حالت عادی، Simple از کانفیگ‌های XRAY داخل سابسکریپشن استفاده می‌کند.

ویژگی‌های حالت Normal:

- دریافت کانفیگ‌ها از سابسکریپشن
- تست همه کانفیگ‌ها با Xray Ping
- انتخاب اولین کانفیگ Reachable هنگام Connect
- ادامه اسکن بعد از اتصال
- آماده‌سازی کش برای Next Healthy
- استفاده از DNS گوگل:

```text
8.8.8.8
8.8.4.4
```

### حالت ServerLess در Simple

در Simple یک گزینه کشویی برای **ServerLess** وجود دارد.

نام نمایشی:

```text
ServerLess 🇮🇷
IRAN IPS
```

کاربرد ServerLess:

- مناسب برای مسیرهای خاص ایران
- استفاده از کانفیگ داخلی ServerLess
- اجرای Xray با ساختار
- مناسب برای حالت‌هایی که کانفیگ ServerLess بهتر جواب می‌دهد

> در حالت ServerLess معمولاً فقط یک کانفیگ وجود دارد، به همین دلیل **Next Healthy** برای ServerLess فعال نیست و مخصوص Simple Normal است.

---

## 2. 🧠 Advance

صفحه **Advance** مهم‌ترین بخش برای شرایط خاص اینترنت ایران است.

این بخش برای زمانی طراحی شده که اینترنت بین‌الملل قطع، محدود یا دچار اختلال شدید است و فقط **نت ملی ایران / شبکه داخلی / مسیرهای داخلی** در دسترس هستند. در چنین شرایطی Advance تلاش می‌کند با موتور **RKh-MSP**، اسکن IP، انتخاب SNI و ساخت مسیر مناسب، روشی برای دسترسی به سایت‌ها فراهم کند.

> ✅ **Advance مناسب زمان نت ملی، قطعی کامل اینترنت بین‌الملل و محدودیت شدید اینترنت است؛ به شرطی که شبکه داخلی یا مسیر لازم برای اسکن و اتصال فعال باشد.**

### امکانات Advance

- موتور **RKh-MSP**
- انتخاب ISP
- انتخاب SNI
- اسکن IP روی Port 443
- پشتیبانی از Manual IP
- انتخاب سرعت اسکن
- انتخاب Max IPs
- Proxy Mode
- VPN Mode
- پروکسی داخلی SOCKS5
- پروکسی داخلی HTTP
- IP Memory
- Next IP
- Ping All برای Clean IP ها
- Copy و Clear برای IP Memory
- Routing Strategy
- CF Config برای VLESS
- نمایش مسیر فعال
- نمایش تعداد Scanned / Clean / Saved
- نمایش سرعت زنده
- ادامه اسکن در پس‌زمینه

### Proxy Mode در Advance

در **Proxy Mode** برنامه فقط پروکسی داخلی را فعال می‌کند.

پورت‌ها:

```text
SOCKS5: 127.0.0.1:9990
HTTP:   127.0.0.1:9991
```

کاربرد Proxy Mode:

- استفاده با برنامه‌هایی مثل v2rayNG
- تست مسیر RKh-MSP بدون فعال کردن VPN کامل
- مناسب برای دیباگ
- مناسب زمانی که فقط به یک پروکسی محلی نیاز دارید

### VPN Mode در Advance

در **VPN Mode** کل ترافیک اندروید از مسیر SIMORGH عبور می‌کند.

مسیر کلی:

```text
Android TUN
   ↓   ↓
Xray
   ↓
Local RKh-MSP Proxy
   ↓
Clean IP
```

کاربرد VPN Mode:

- تونل کردن کل دستگاه
- اتصال مستقیم از داخل SIMORGH
- استفاده از IP های سالم پیدا شده
- مناسب برای استفاده ساده‌تر نسبت به Proxy Mode

### تنظیمات Advance

از آیکن Settings می‌توان تنظیمات زیر را کنترل کرد:

#### ISP

انتخاب ISP برای اسکن IP ها.

پیش‌فرض:

```text
AbrArvan CDN and IaaS
```

#### SNI

انتخاب SNI برای تست TLS.

پیش‌فرض:

```text
chatgpt.com
```

#### Manual IP

در این بخش می‌توان IP، CIDR یا Range را دستی وارد کرد.

وقتی Manual IP فعال باشد:

- اسکن ISP نادیده گرفته می‌شود
- IP های دستی وارد IP Memory می‌شوند
- برنامه فقط همان IP های دستی را تست می‌کند

#### Scan

تنظیمات اسکن:

- Slow
- Normal
- Fast
- Max IPs

حداکثر مقدار پیش‌فرض:

```text
33000
```

#### Proxy

انتخاب نوع پروکسی داخلی:

- SOCKS5
- HTTP

#### Route

انتخاب استراتژی مسیر:

| گزینه | توضیح |
|---|---|
| Default | مسیرهای سالم را برای هر Host تست و مسیر جواب‌گو را نگه می‌دارد |
| Random | انتخاب تصادفی از IP های سالم |
| Round Robin | چرخش بین IP های سالم |
| Least Loss | انتخاب مسیر با کمترین خطا |
| Lowest Latency | انتخاب مسیر با کمترین تأخیر |
| Hybrid Score | ترکیب کیفیت، پینگ و خطا برای انتخاب مسیر بهتر |

---

## 3. 🧩 NipoVPN / NIPOVPN

صفحه **NipoVPN** یکی از بخش‌های مهم نسخه جدید SIMORGH است.

این بخش برای اتصال با پروفایل‌های `nipovpn://` طراحی شده و از هسته native NipoVPN استفاده می‌کند.

هسته NipoVPN به‌صورت داخلی همراه برنامه ارائه شده و کانفیگ پیش‌فرض آن نیز داخل خود برنامه مدیریت می‌شود.

### مسیر اتصال NipoVPN

مسیر اتصال در NipoVPN به این صورت است:

```text
Android VPN TUN
   ↓   ↓
Xray local SOCKS inbound
   ↓
Xray SOCKS outbound
   ↓
NipoVPN SOCKS5 Agent
```

پورت داخلی NipoVPN:

```text
127.0.0.1:9992
```

در UI هم به صورت زیر نمایش داده می‌شود:

```text
SOCKS5 9992 → XRAY
```

### امکانات NipoVPN

- تب اختصاصی **NipoVPN**
- اتصال با NipoVPN Agent
- وارد کردن لینک `nipovpn://`
- دکمه **Add Profile**
- نمایش لیست پروفایل‌ها
- انتخاب پروفایل
- ویرایش پروفایل با آیکن مداد
- خروجی گرفتن / Copy از پروفایل با آیکن کپی
- حذف پروفایل
- ذخیره پروفایل
- تست سرور NipoVPN
- Reset کردن کانفیگ
- نمایش Ping
- نمایش Nipo Server
- نمایش Local SOCKS5
- وضعیت `NipoVPN connected`
- استفاده از Google DNS
- Exclude شدن خود برنامه از Loop

### فیلدهای قابل ویرایش در NipoVPN

بعد از وارد کردن پروفایل، فیلدهای زیر قابل ویرایش هستند:

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

بخش **CF Config** داخل صفحه Advance قرار دارد.

این بخش برای کانفیگ‌های Cloudflare VLESS WS TLS استفاده می‌شود.

عنوان داخل برنامه:

```text
CF Config
Clouflare Vless Ws TLS Config
```

### امکانات CF Config

- فعال/غیرفعال کردن CF Config
- وارد کردن VLESS Config
- استفاده از Clean IP های ذخیره‌شده در IP Memory
- تست Latency برای IP ها
- دکمه **Latency All**
- دکمه **Connect**
- مرتب‌سازی بر اساس Latency
- جایگزینی فقط آدرس VLESS با Clean IP

> در CF Config فقط آدرس VLESS با Clean IP جایگزین می‌شود و SNI / Host / Path از کانفیگ اصلی حفظ می‌شوند.

---

# 💾 IP Memory

بخش **IP Memory** برای ذخیره و مدیریت Clean IP ها استفاده می‌شود.

### امکانات IP Memory

- نمایش تعداد Clean IP ها
- نمایش Ping در صورت وجود
- دکمه **Show / Hide**
- دکمه **Ping All**
- دکمه **Copy**
- دکمه **Clear**
- استفاده توسط Advance
- استفاده توسط CF Config
- ادامه استفاده از IP های ذخیره‌شده در اتصال‌های بعدی

---

# 📊 Live Speed

SIMORGH سرعت لحظه‌ای اتصال را نمایش می‌دهد:

```text
↓ Download
↑ Upload
```

این بخش در حالت‌های زیر کاربرد دارد:

- Simple XRAY
- Advance VPN / Proxy
- NipoVPN

---

# 🧪 بهبود پایداری Simple

برای پایداری بهتر بخش Simple، تغییرات زیر در README ذکر شده است:

- محافظت در برابر کرش هنگام **Ping All**
- محافظت در برابر کرش هنگام اسکن پینگ پس‌زمینه
- محافظت هنگام Shuffled Connect Scan
- کاهش همزمانی Ping های سخت‌گیرانه 3x برای جلوگیری از فشار زیاد روی Native Process
- اضافه شدن Timeout برای هر 3x Xray Ping
- لغو اسکن پس‌زمینه قبلی قبل از Connect Scan جدید
- محافظت از نوشتن Latency Cache
- محافظت از ارسال Worker Channel

---

# 📘 آموزش استفاده

## 🚀 آموزش استفاده از Simple

1. برنامه را باز کنید.
2. وارد تب **Simple** شوید.
3. اگر اولین بار است، روی **Update** بزنید.
4. برای دیدن لیست کانفیگ‌ها روی بخش **Config** بزنید.
5. برای تست همه کانفیگ‌ها روی **Ping All** بزنید.
6. روی دکمه بزرگ Connect بزنید.
7. برنامه کانفیگ‌ها را با Xray Ping واقعی تست می‌کند.
8. اولین کانفیگ قابل اتصال را انتخاب می‌کند.
9. بعد از اتصال، اسکن در پس‌زمینه ادامه پیدا می‌کند.
10. اگر اتصال ضعیف شد، روی **Next Healthy** بزنید.

### استفاده از Clear Cache در Simple

وقتی Ping ها اشتباه شدند یا کانفیگی Ping دارد ولی وصل نمی‌شود:

1. روی **Clear Cache** بزنید.
2. فقط حافظه سلامت و پینگ پاک می‌شود.
3. خود کانفیگ‌ها حذف نمی‌شوند.
4. دوباره Ping All یا Connect را اجرا کنید.

---

## 🇮🇷 آموزش استفاده از ServerLess در Simple

1. وارد تب **Simple** شوید.
2. گزینه **ServerLess** را روی ON بگذارید.
3. متن `IRAN IPS` نمایش داده می‌شود.
4. روی Connect بزنید.
5. برنامه کانفیگ داخلی ServerLess را اجرا می‌کند.

> چون ServerLess معمولاً یک کانفیگ دارد، Next Healthy برای آن کاربرد ندارد.

---

## 🧠 آموزش استفاده از Advance برای نت ملی و قطعی اینترنت بین‌الملل

این روش برای زمانی است که اینترنت بین‌الملل قطع یا محدود است اما نت ملی / شبکه داخلی هنوز فعال است.

1. وارد تب **Advance** شوید.
2. از Settings وارد بخش **ISP** شوید.
3. ISP مناسب را انتخاب کنید.
4. از بخش **SNI** یک یا چند SNI انتخاب کنید.
5. در صورت نیاز از بخش **Manual**، IP های دستی وارد کنید.
6. از بخش **Scan** سرعت اسکن را انتخاب کنید.
7. از بخش **Proxy** نوع پروکسی را انتخاب کنید:
   - SOCKS5 برای `127.0.0.1:9990`
   - HTTP برای `127.0.0.1:9991`
8. اگر می‌خواهید فقط پروکسی محلی بسازید، **Proxy Mode** را انتخاب کنید.
9. اگر می‌خواهید کل گوشی تونل شود، **VPN Mode** را انتخاب کنید.
10. روی دکمه بزرگ Connect بزنید.
11. برنامه شروع به اسکن می‌کند.
12. اولین Clean IP وارد مسیر می‌شود.
13. اسکن در پس‌زمینه ادامه پیدا می‌کند.
14. برای تعویض مسیر از **Next IP** استفاده کنید.

---

## 🔌 آموزش استفاده از Proxy Mode با v2rayNG

1. در SIMORGH وارد **Advance** شوید.
2. Mode را روی **Proxy** بگذارید.
3. در Settings > Proxy یکی از حالت‌ها را انتخاب کنید:
   - SOCKS5
   - HTTP
4. روی Connect بزنید.
5. در v2rayNG یک پروکسی محلی بسازید:

```text
SOCKS5: 127.0.0.1:9990
HTTP:   127.0.0.1:9991
```

6. اگر v2rayNG با این پروکسی کار کرد، مسیر RKh-MSP و IP Pool درست کار می‌کند.

---

## 🧩 آموزش استفاده از NipoVPN

1. وارد تب **NipoVPN** شوید.
2. لینک پروفایل خود را داخل فیلد زیر وارد کنید:

```text
nipovpn://
```

3. روی **Add Profile** بزنید.
4. روی **Show Profiles** بزنید.
5. پروفایل موردنظر را انتخاب کنید.
6. برای ویرایش روی آیکن مداد `✎` بزنید.
7. برای خروجی گرفتن از پروفایل روی آیکن کپی `⧉` بزنید.
8. در صورت نیاز روی **Test** بزنید تا سرور تست شود.
9. اگر اطلاعات را تغییر دادید، روی **Save Profile** بزنید.
10. روی دکمه Connect بزنید.
11. وضعیت اتصال باید به شکل زیر شود:

```text
NipoVPN connected
```

---

## ☁️ آموزش استفاده از CF Config

1. ابتدا در Advance اسکن انجام دهید تا IP Memory پر شود.
2. وارد بخش **CF Config** شوید.
3. CF Config را فعال کنید.
4. کانفیگ VLESS WS TLS خود را وارد کنید.
5. روی **Latency All** بزنید.
6. IP با Latency بهتر را انتخاب کنید.
7. روی **Connect** بزنید.

> فقط آدرس VLESS با Clean IP جایگزین می‌شود؛ SNI، Host و Path حفظ می‌شوند.

---

# 🛠️ عیب‌یابی

## Simple وصل نمی‌شود

- Update بزنید
- Ping All بزنید
- Clear Cache بزنید
- دوباره Connect کنید
- اگر چند کانفیگ سالم دارید، Next Healthy را بزنید
- ServerLess را ON/OFF تست کنید

## Next Healthy کار نمی‌کند

- Next Healthy فقط برای Simple Normal است
- ابتدا باید Ping All اجرا شده باشد
- اگر کش سالم خالی باشد، برنامه پیام می‌دهد که Ping All بزنید

## ServerLess وصل نمی‌شود

- یک بار ServerLess را OFF و دوباره ON کنید
- Clear Cache بزنید
- Connect را دوباره اجرا کنید
- توجه کنید ServerLess معمولاً یک کانفیگ دارد

## Advance در نت ملی جواب نمی‌دهد

- ISP را عوض کنید
- SNI را عوض کنید
- Manual IP تست کنید
- Scan Speed را تغییر دهید
- Proxy Mode را جداگانه با v2rayNG تست کنید
- Route Strategy را تغییر دهید
- IP Memory را Clear و دوباره اسکن کنید

## NipoVPN وصل نمی‌شود

- مطمئن شوید لینک با `nipovpn://` شروع می‌شود
- Add Profile را بزنید
- پروفایل را انتخاب کنید
- Server IP و Server Port را بررسی کنید
- Test را اجرا کنید
- Save Profile را بزنید
- دوباره Connect کنید

## CF Config کار نمی‌کند

- اول IP Memory را با Advance پر کنید
- مطمئن شوید کانفیگ با `vless://` شروع می‌شود
- Latency All بزنید
- IP سالم‌تر را انتخاب کنید
- SNI / Host / Path کانفیگ اصلی را بررسی کنید

---

# 🧱 ساختار فنی پروژه


## مسیرهای اصلی ترافیک

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

# 📦 نصب

1. فایل APK را نصب کنید.
2. مجوز VPN را تأیید کنید.
3. برای اتصال سریع از **Simple** استفاده کنید.
4. برای نت ملی و قطعی اینترنت بین‌الملل از **Advance** استفاده کنید.
5. برای پروفایل‌های NipoVPN از تب **NipoVPN** استفاده کنید.

---

# 🔐 مجوزهای موردنیاز

- دسترسی اینترنت
- دسترسی VPN
- دسترسی وضعیت شبکه
- اجرای Foreground Service برای اتصال پایدار

---

# 👨‍💻 سازنده

```text
Made By RKh!
Telegram: @pingplas_channel
```

---

# ⚠️ Disclaimer

این پروژه فقط برای اهداف آموزشی، تحقیقاتی و استفاده قانونی ارائه شده است. مسئولیت استفاده از برنامه بر عهده کاربر است. لطفاً از این ابزار مطابق قوانین محل زندگی خود استفاده کنید.

---

# ⭐ حمایت

اگر SIMORGH برای شما مفید بود، در GitHub به پروژه ⭐ بدهید.

```text
SIMORGH VPN
Fast. Smart. Advanced. NipoVPN Ready.
```
