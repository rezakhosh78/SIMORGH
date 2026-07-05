# 🐦‍🔥 سیمورغ VPN

**SIMORGH VPN** یک کلاینت چندموتوره‌ی VPN برای اندروید است که برای اتصال عادی، شبکه‌های محدود، شرایط اینترانت ملی ایران، اختلال یا قطعی اینترنت بین‌الملل، موتور RKh-MSP، کانفیگ‌های XRAY، حالت Fragment، پروفایل‌های NipoVPN و DNS/MasterDNS طراحی شده است.

---

## ⚠️ حمایت مالی

USDT BEP20:

```text
0xeaA77532878d92b2218690605DebD192cd4f018f
```

این README بر اساس آخرین نسخه‌ی پروژه نوشته شده است:

```text
Version Name: 1.2.1
Version Code: 1202
Application ID: com.rkh.simorgh
Minimum SDK: 29
Target SDK: 36
```

> ⚠️ نکته مهم: اگر هیچ شبکه‌ی فعالی وجود نداشته باشد، هیچ برنامه‌ای نمی‌تواند اتصال برقرار کند. اما وقتی اینترانت ملی، شبکه داخلی یا مسیرهای داخلی هنوز در دسترس باشند و اینترنت بین‌الملل مسدود یا شدیداً محدود شده باشد، بخش **MSP** برای اسکن مسیرها، پیدا کردن IPهای سالم و ساخت مسیر اتصال طراحی شده است.

---

## ✨ نمای کلی سریع

SIMORGH پنج صفحه‌ی اصلی دارد:

| صفحه | کاربرد اصلی |
|---|---|
| 🛰️ **MSP** | موتور RKh-MSP، شرایط اینترانت ملی ایران، قطعی اینترنت بین‌الملل، اسکن IP، حالت Proxy/VPN و CF Config |
| 🚀 **Simple** | اتصال سریع یک‌کلیکی با کانفیگ‌های XRAY |
| 🧩 **Fragment** | بازیابی Dirty IP کلادفلر با تبدیل VLESS/Trojan به JSON نهایی، Address override و Ping واقعی با Xray |
| 🟣 **NipoVPN** | پشتیبانی از پروفایل‌های `nipovpn://` با هسته‌ی NipoVPN |
| 🧠 **DNS (MasterDNS)** | مدیریت مسیرهای DNS، پروفایل‌های MasterDNS، پروفایل‌های Resolver، لاگ‌ها و SOCKS bridge |

---

## 🔥 قابلیت‌های اصلی نسخه 1.2.1

- 🛰️ حالت **MSP** برای شرایط اینترانت ملی ایران و اختلال/قطعی اینترنت بین‌الملل
- 🚀 اتصال سریع از طریق صفحه **Simple**
- 🧩 حالت **Fragment** برای Cloudflare Dirty IP Recovery
- 🔁 تبدیل خودکار لینک‌های `vless://` و `trojan://` به JSON معتبر Xray
- 🧱 تزریق خودکار تنظیمات Fragment/finalmask به JSON نهایی
- ✏️ قابلیت ویرایش پارامترهای Fragment شامل `packets`، `lengths`، `delays` و `maxSplit`
- 🌐 فیلد **Address** در Fragment برای جایگزینی address خروجی داخل JSON نهایی
- 📡 Ping واقعی Fragment با Xray بر اساس JSON نهایی
- 🟣 پشتیبانی کامل از **NipoVPN**
- 🧠 پشتیبانی از **DNS (MasterDNS)**
- 📥 وارد کردن پروفایل `nipovpn://`
- 🧾 ذخیره، انتخاب، ویرایش، حذف و خروجی گرفتن از پروفایل‌های NipoVPN
- 📡 Ping واقعی Xray برای کانفیگ‌های Simple
- 🧪 اعتبارسنجی پایدار **3x Xray Ping**
- 🧯 محافظ کرش برای Ping All، اسکن Ping پس‌زمینه و Connect Scan
- 🧹 دکمه **Clear Cache** بدون حذف کانفیگ‌ها
- 📶 دکمه **Ping All** برای کانفیگ‌های Simple
- 🇮🇷 حالت **ServerLess (Patterniha)🇮🇷 / IRAN IPS**
- 🌐 Google DNS برای حالت Simple Normal
- 🧠 کش سلامت و latency
- 📶 تازه‌سازی Ping در پس‌زمینه بعد از اتصال
- 🔎 اسکن IP بر اساس ISP و SNI
- 🧱 حالت MSP Proxy و MSP VPN
- 🔌 پشتیبانی از پراکسی داخلی SOCKS5 و HTTP
- ☁️ **CF Config** برای VLESS WS TLS
- 🧹 دکمه **Clear IPs** داخل کارت MSP Route
- 🧭 انتخاب Routing Strategy
- 📊 نمایش زنده سرعت دانلود/آپلود
- 🛡️ خود برنامه SIMORGH از تونل VPN خودش خارج می‌شود تا loop ایجاد نشود و اسکن دقیق‌تر انجام شود.

---

# 📱 صفحات اصلی برنامه

## 1. 🚀 Simple

صفحه **Simple** برای اتصال سریع با کانفیگ‌های XRAY طراحی شده است.

کاربر نیاز ندارد تنظیمات پیچیده را مدیریت کند. برنامه کانفیگ‌ها را از سیستم داخلی دریافت می‌کند، آن‌ها را تست می‌کند و به یک کانفیگ سالم و قابل دسترس وصل می‌شود.

### قابلیت‌های Simple

- اتصال یک‌کلیکی
- منبع داخلی کانفیگ‌ها
- دکمه **Update**
- دکمه **Clear Cache**
- نمایش تعداد کانفیگ‌ها
- نمایش کانفیگ انتخاب‌شده
- نمایش Ping
- تایمر اتصال
- باز شدن لیست کانفیگ‌ها با لمس بخش Config
- دکمه **Ping All**
- اتصال به یک کانفیگ مشخص از لیست
- پشتیبانی از ServerLess
- اسکن پایدار و واقعی Xray Ping

### منبع کانفیگ Simple

در صفحه Simple، کانفیگ‌های XRAY از طریق سیستم داخلی برنامه دریافت و مدیریت می‌شوند.

### حالت Simple Normal

در حالت Normal، بخش Simple از کانفیگ‌های XRAY دریافت‌شده استفاده می‌کند.

ویژگی‌های حالت Normal:

- دریافت کانفیگ‌ها از منبع داخلی
- تست کانفیگ‌ها با Xray Ping
- اتصال به اولین کانفیگ قابل دسترس
- ادامه اسکن بعد از اتصال
- استفاده از Google DNS:

```text
8.8.8.8
8.8.4.4
```

### حالت Simple ServerLess

بخش Simple دارای گزینه **ServerLess** است.

نام نمایشی:

```text
ServerLess(Patterniha) 🇮🇷
IRAN IPS
```

کاربرد ServerLess:

- طراحی‌شده برای مسیرهای خاص مرتبط با ایران
- استفاده از کانفیگ داخلی ServerLess
- اجرای Xray با ساختار سازگار با TUN/tun2socks
- مناسب زمانی که کانفیگ ServerLess بهتر از کانفیگ‌های عادی کار می‌کند

> ServerLess معمولاً یک کانفیگ دارد و برای اتصال مستقیم ServerLess طراحی شده است.

---

## 2. 🛰️ MSP

صفحه **MSP** بخش اصلی برای شرایط خاص شبکه ایران است.

این بخش برای زمانی طراحی شده که اینترنت بین‌الملل مسدود، شدیداً محدود یا ناپایدار است اما اینترانت ملی، شبکه داخلی یا مسیرهای داخلی هنوز در دسترس هستند. در این حالت MSP با موتور **RKh-MSP**، اسکن IP، انتخاب SNI و ساخت مسیر تلاش می‌کند راهی برای دسترسی ایجاد کند.

> ✅ **MSP برای شرایط اینترانت ملی ایران، قطعی کامل اینترنت بین‌الملل و محدودیت شدید اینترنت مناسب است، به شرطی که شبکه داخلی یا مسیر مورد نیاز هنوز در دسترس باشد.**

### قابلیت‌های MSP

- موتور **RKh-MSP**
- انتخاب ISP
- انتخاب SNI
- اسکن IP روی پورت 443
- پشتیبانی از IP دستی
- انتخاب سرعت اسکن
- انتخاب حداکثر تعداد IP
- حالت Proxy
- حالت VPN
- پراکسی داخلی SOCKS5
- پراکسی داخلی HTTP
- Next IP
- Ping All برای IPهای سالم
- Routing Strategy
- CF Config برای VLESS
- نمایش مسیر فعال
- شمارنده‌های Scanned / Clean / Saved
- نمایش زنده سرعت
- اسکن پس‌زمینه
- خروج خود SIMORGH از تونل VPN برای اسکن دقیق‌تر

### Proxy Mode در MSP

در **Proxy Mode** برنامه فقط پراکسی محلی را اجرا می‌کند.

پورت‌ها:

```text
SOCKS5: 127.0.0.1:9990
HTTP:   127.0.0.1:9991
```

Proxy Mode مناسب است برای:

- استفاده از SIMORGH با برنامه‌هایی مثل v2rayNG
- تست مسیر RKh-MSP بدون اجرای VPN کامل اندروید
- دیباگ
- زمانی که فقط پراکسی محلی لازم است

### VPN Mode در MSP

در **VPN Mode** تمام ترافیک اندروید از SIMORGH عبور می‌کند.

جریان کلی:

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

VPN Mode مناسب است برای:

- تونل کامل دستگاه
- اتصال مستقیم از داخل SIMORGH
- استفاده از IPهای سالم پیدا شده
- استفاده ساده‌تر نسبت به Proxy Mode

### تنظیمات MSP

تنظیمات از آیکن Settings در دسترس هستند.

#### ISP

انتخاب ISP برای اسکن IP.

پیش‌فرض:

```text
AbrArvan CDN and IaaS
```

#### SNI

انتخاب مقدارهای SNI برای تست TLS.

پیش‌فرض:

```text
chatgpt.com
```

#### Manual IP

می‌توانید IP، CIDR یا بازه IP را به صورت دستی وارد کنید.

وقتی Manual IP فعال باشد:

- اسکن رنج ISP رد می‌شود
- IPهای دستی توسط برنامه تست می‌شوند
- فقط IPهای واردشده بررسی می‌شوند

#### Scan

گزینه‌های اسکن:

- Slow
- Normal
- Fast
- Max IPs

حداکثر پیش‌فرض:

```text
33000
```

#### Proxy

انتخاب پروتکل پراکسی محلی:

- SOCKS5
- HTTP

#### Route

استراتژی‌های Route:

| گزینه | توضیح |
|---|---|
| Default | IPهای سالم/ذخیره‌شده را برای هاست مقصد تست می‌کند و مسیر کارکرده را نگه می‌دارد |
| Random | انتخاب تصادفی از IPهای سالم |
| Round Robin | چرخش بین IPهای سالم |
| Least Loss | انتخاب مسیر با خطای کمتر |
| Lowest Latency | انتخاب مسیر با کمترین latency |
| Hybrid Score | ترکیب کیفیت، latency و خطا برای انتخاب بهتر |

---

## 3. 🧩 Fragment

صفحه **Fragment** برای Cloudflare Dirty IP Recovery با استفاده از کانفیگ‌های ساده VLESS یا Trojan طراحی شده است.

کاربر می‌تواند یک لینک ساده `vless://` یا `trojan://` وارد کند. SIMORGH آن را به JSON معتبر Xray تبدیل می‌کند، Address اختیاری را اعمال می‌کند، تنظیمات Fragment/finalmask را اضافه می‌کند، JSON نهایی را اعتبارسنجی می‌کند و سپس Xray را با همان کانفیگ اجرا می‌کند.

زیرعنوان نمایشی:

```text
Cloudflare Dirty IP Recovery
```

### قابلیت‌های Fragment

- تب اختصاصی **Fragment**
- پشتیبانی از لینک‌های ساده `vless://`
- پشتیبانی از لینک‌های ساده `trojan://`
- تبدیل لینک VLESS/Trojan به JSON معتبر Xray
- افزودن تنظیمات Fragment/finalmask به JSON نهایی
- جلوگیری از خراب شدن JSON با ساختاردهی JSON به جای اتصال متنی
- فیلد Address برای جایگزینی address خروجی داخل JSON نهایی
- نمایش Effective Address از روی JSON ساخته‌شده
- نمایش Ping
- Ping واقعی با Xray بر اساس JSON نهایی
- دکمه بزرگ Connect/Disconnect
- نمایش وضعیت اتصال Fragment در هدر
- پنل مخفی/قابل باز شدن **Fragment Setting**
- پارامترهای قابل ویرایش Fragment

### پارامترهای قابل ویرایش Fragment

داخل **Fragment Setting** این مقدارها قابل ویرایش هستند:

- `packets`
- `lengths`
- `delays`
- `maxSplit`

### رفتار JSON نهایی در Fragment

حالت Fragment کانفیگ نهایی Xray را به این ترتیب می‌سازد:

```text
VLESS/Trojan link
   ↓
Convert to valid Xray JSON
   ↓
Apply Address override if provided
   ↓
Inject Fragment/finalmask settings
   ↓
Validate final JSON
   ↓
Run Xray
```

### Ping در Fragment

دکمه **Ping** لینک خام VLESS/Trojan را مستقیم تست نمی‌کند.

SIMORGH ابتدا JSON نهایی را می‌سازد، یک پردازش موقت Xray با همان JSON اجرا می‌کند، ترافیک تست را از داخل Xray عبور می‌دهد و latency نهایی مسیر را نمایش می‌دهد.

---

## 4. 🟣 NipoVPN

صفحه **NipoVPN** از پروفایل‌های `nipovpn://` و اتصال Native NipoVPN پشتیبانی می‌کند.

هسته NipoVPN داخل برنامه قرار دارد و کانفیگ پیش‌فرض آن داخل SIMORGH مدیریت می‌شود.

### جریان ترافیک NipoVPN

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

پورت داخلی NipoVPN:

```text
127.0.0.1:9992
```

نمایش در UI:

```text
SOCKS5 9992 → XRAY
```

### قابلیت‌های NipoVPN

- تب اختصاصی **NipoVPN**
- اتصال NipoVPN Agent
- وارد کردن لینک `nipovpn://`
- دکمه **Add Profile**
- لیست پروفایل‌ها
- انتخاب پروفایل
- ویرایش پروفایل با آیکن مداد
- خروجی/کپی پروفایل با آیکن کپی
- حذف پروفایل
- ذخیره پروفایل
- تست سرور NipoVPN
- Reset کانفیگ
- نمایش Ping
- نمایش Nipo Server
- نمایش SOCKS5 محلی
- وضعیت `NipoVPN connected`
- Google DNS
- خروج خود برنامه از loop تونل VPN

### فیلدهای قابل ویرایش NipoVPN

بعد از وارد کردن پروفایل، این فیلدها قابل ویرایش هستند:

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

## 5. 🧠 DNS (MasterDNS)

صفحه **DNS (MasterDNS)** برای مدیریت DNS، پروفایل‌های MasterDNS و Resolver طراحی شده است.

این بخش برای زمانی مناسب است که نیاز دارید رفتار DNS، انتخاب Resolver، DNS Bridge یا مسیرهای مرتبط با DNS را جدا از MSP، Simple، Fragment و NipoVPN کنترل کنید.

### قابلیت‌های DNS (MasterDNS)

- تب اختصاصی **DNS**
- مدیریت پروفایل MasterDNS
- مدیریت پروفایل Resolver
- وارد کردن لیست Resolver
- ذخیره پروفایل Resolver
- افزودن پروفایل Resolver
- انتخاب پروفایل Resolver
- حذف پروفایل Resolver
- Refresh Logs
- Clear Logs
- پشتیبانی از SOCKS Bridge
- نمایش وضعیت DNS
- نمایش وضعیت اتصال در هدر
- مدیریت امن‌تر lifecycle سرویس هنگام Stop/Destroy

### حالت‌های MasterDNS

MasterDNS به عنوان یک حالت کمکی برای تست و مدیریت DNS استفاده می‌شود.

کاربردهای معمول:

- تست رفتار Resolver
- مدیریت پروفایل‌های Resolver
- بررسی لاگ‌های DNS
- استفاده از DNS Bridge با مسیر SOCKS محلی
- عیب‌یابی مشکلات مرتبط با DNS

---

# ☁️ CF Config

**CF Config** داخل صفحه MSP قرار دارد.

این بخش برای کانفیگ‌های Cloudflare VLESS WS TLS طراحی شده است.

عنوان نمایشی:

```text
CF Config
Cloudflare VLESS WS TLS Config
```

### قابلیت‌های CF Config

- فعال/غیرفعال کردن CF Config
- وارد کردن کانفیگ VLESS
- استفاده از IPهای سالم حاصل از اسکن MSP
- تست latency برای IPها
- دکمه **Latency All**
- دکمه **Connect**
- مرتب‌سازی بر اساس latency
- جایگزینی فقط address کانفیگ VLESS با IP انتخاب‌شده

> در CF Config فقط address کانفیگ VLESS با IP سالم جایگزین می‌شود. SNI، Host و Path از کانفیگ اصلی باقی می‌مانند.

---

# 📊 Live Speed

SIMORGH سرعت ترافیک را به صورت زنده نمایش می‌دهد:

```text
↓ Download
↑ Upload
```

این بخش در موارد زیر استفاده می‌شود:

- Simple XRAY
- MSP VPN / Proxy
- Fragment
- NipoVPN
- DNS (MasterDNS)

---

# 🧪 بهبودهای پایداری Simple

برای پایداری بهتر Simple، این موارد در برنامه اعمال شده‌اند:

- محافظ کرش برای **Ping All**
- محافظ کرش برای Ping پس‌زمینه
- محافظ کرش برای Connect Scan
- کاهش concurrency در 3x Xray Ping برای جلوگیری از فشار native process/OOM
- Timeout برای هر تست 3x Xray Ping
- لغو Ping پس‌زمینه قبلی قبل از شروع Connect Scan جدید
- محافظ نوشتن latency-cache
- محافظ ارسال worker channel
- مدیریت امن‌تر state اتصال/قطع اتصال Simple
- محافظ کرش Disconnect در ServerLess

---

# 📘 روش استفاده

## 🚀 روش استفاده از Simple

1. برنامه را باز کنید.
2. وارد تب **Simple** شوید.
3. اگر اولین اجراست، روی **Update** بزنید.
4. روی بخش **Config** بزنید تا لیست کانفیگ باز شود.
5. روی **Ping All** بزنید تا همه کانفیگ‌ها تست شوند.
6. روی دکمه بزرگ Connect بزنید.
7. برنامه کانفیگ‌ها را با Xray Ping واقعی تست می‌کند.
8. اولین کانفیگ قابل دسترس انتخاب می‌شود.
9. بعد از اتصال، اسکن پس‌زمینه ادامه پیدا می‌کند.

### استفاده از Clear Cache در Simple

وقتی نتیجه Ping اشتباه به نظر می‌رسد یا کانفیگ Ping دارد ولی وصل نمی‌شود:

1. روی **Clear Cache** بزنید.
2. فقط حافظه سلامت و Ping پاک می‌شود.
3. کانفیگ‌ها حذف نمی‌شوند.
4. دوباره Ping All یا Connect را اجرا کنید.

---

## 🇮🇷 روش استفاده از ServerLess در Simple

1. وارد تب **Simple** شوید.
2. **ServerLess** را روشن کنید.
3. `IRAN IPS` نمایش داده می‌شود.
4. روی Connect بزنید.
5. برنامه کانفیگ داخلی ServerLess را اجرا می‌کند.

> ServerLess معمولاً یک کانفیگ دارد و برای اتصال مستقیم ServerLess طراحی شده است.

---

## 🧩 روش استفاده از Fragment

1. وارد تب **Fragment** شوید.
2. یک کانفیگ `vless://` یا `trojan://` وارد کنید.
3. در صورت نیاز، IP یا دامنه را در فیلد **Address** وارد کنید.
4. روی **Ping** بزنید تا JSON نهایی از داخل Xray تست شود.
5. فقط اگر می‌خواهید مقدارهای Fragment را تغییر دهید، **Fragment Setting** را باز کنید.
6. روی دکمه بزرگ Connect بزنید.
7. برنامه کانفیگ را تبدیل می‌کند، تنظیمات Fragment/finalmask را اضافه می‌کند، JSON نهایی را اعتبارسنجی می‌کند و Xray را اجرا می‌کند.
8. در هدر باید این وضعیت نمایش داده شود:

```text
Fragment Connected
```

---

## 🛰️ روش استفاده از MSP در شرایط اینترانت ملی یا قطعی اینترنت بین‌الملل

از این روش زمانی استفاده کنید که اینترنت بین‌الملل مسدود یا شدیداً محدود شده اما اینترانت ملی یا شبکه داخلی هنوز در دسترس است.

1. وارد تب **MSP** شوید.
2. Settings را باز کنید.
3. از بخش **ISP** یک ISP انتخاب کنید.
4. از بخش **SNI** یک یا چند مقدار انتخاب کنید.
5. در صورت نیاز، IP دستی در بخش **Manual** وارد کنید.
6. سرعت اسکن را از بخش **Scan** انتخاب کنید.
7. پروتکل پراکسی را از بخش **Proxy** انتخاب کنید:
   - SOCKS5 برای `127.0.0.1:9990`
   - HTTP برای `127.0.0.1:9991`
8. اگر فقط پراکسی محلی می‌خواهید، **Proxy Mode** را انتخاب کنید.
9. اگر تونل کامل گوشی را می‌خواهید، **VPN Mode** را انتخاب کنید.
10. روی دکمه بزرگ Connect بزنید.
11. برنامه شروع به اسکن می‌کند.
12. اولین IP سالم به مسیر اضافه می‌شود.
13. اسکن در پس‌زمینه ادامه پیدا می‌کند.
14. برای تغییر مسیر از **Next IP** استفاده کنید.

---

## 🔌 روش استفاده از Proxy Mode با v2rayNG

1. در SIMORGH وارد **MSP** شوید.
2. حالت **Proxy** را انتخاب کنید.
3. در Settings > Proxy یکی از موارد زیر را انتخاب کنید:
   - SOCKS5
   - HTTP
4. روی Connect بزنید.
5. در v2rayNG یک پراکسی محلی بسازید:

```text
SOCKS5: 127.0.0.1:9990
HTTP:   127.0.0.1:9991
```

6. اگر v2rayNG با این پراکسی کار کرد، مسیر RKh-MSP و IP pool درست کار می‌کنند.

---

## 🟣 روش استفاده از NipoVPN

1. وارد تب **NipoVPN** شوید.
2. لینک پروفایل خود را در فیلد وارد کنید:

```text
nipovpn://
```

3. روی **Add Profile** بزنید.
4. روی **Show Profiles** بزنید.
5. پروفایل مورد نظر را انتخاب کنید.
6. برای ویرایش روی آیکن مداد `✎` بزنید.
7. برای خروجی/کپی پروفایل روی آیکن کپی `⧉` بزنید.
8. برای تست سرور روی **Test** بزنید.
9. اگر فیلدها را تغییر دادید، روی **Save Profile** بزنید.
10. روی Connect بزنید.
11. وضعیت باید به این شکل شود:

```text
NipoVPN connected
```

---

## 🧠 روش استفاده از DNS (MasterDNS)

1. وارد تب **DNS** شوید.
2. یک پروفایل MasterDNS انتخاب یا ایجاد کنید.
3. در صورت نیاز لیست Resolver وارد یا انتخاب کنید.
4. بعد از تغییر مقدارها، پروفایل را ذخیره کنید.
5. روی Connect بزنید.
6. با **Refresh Logs** وضعیت runtime را بررسی کنید.
7. برای پاک کردن نمای لاگ از **Clear Logs** استفاده کنید.
8. بعد از پایان تست DNS/MasterDNS، Disconnect کنید.

---

## ☁️ روش استفاده از CF Config

1. ابتدا MSP scanning را اجرا کنید تا IPهای سالم در دسترس باشند.
2. بخش **CF Config** را باز کنید.
3. CF Config را فعال کنید.
4. کانفیگ VLESS WS TLS خود را وارد کنید.
5. روی **Latency All** بزنید.
6. IP با latency بهتر را انتخاب کنید.
7. روی **Connect** بزنید.

> فقط address کانفیگ VLESS با IP سالم جایگزین می‌شود. SNI، Host و Path تغییر نمی‌کنند.

---

# 🛠️ عیب‌یابی

## Simple وصل نمی‌شود

- روی Update بزنید
- روی Ping All بزنید
- روی Clear Cache بزنید
- دوباره Connect را امتحان کنید
- ServerLess را روشن/خاموش تست کنید

## ServerLess وصل نمی‌شود

- ServerLess را خاموش و روشن کنید
- Clear Cache بزنید
- دوباره Connect را اجرا کنید
- توجه کنید که ServerLess معمولاً یک کانفیگ دارد

## Fragment وصل نمی‌شود

- مطمئن شوید کانفیگ با `vless://` یا `trojan://` شروع می‌شود
- اگر Address را override کرده‌اید، مقدار آن را بررسی کنید
- ابتدا Ping بگیرید تا JSON نهایی تست شود
- اگر مقدارهای Fragment را تغییر داده‌اید، آن‌ها را Reset کنید
- IP یا دامنه دیگری در Address تست کنید
- Disconnect و دوباره Connect کنید

## MSP در شرایط اینترانت ملی کار نمی‌کند

- ISP را تغییر دهید
- SNI را تغییر دهید
- IP دستی تست کنید
- سرعت اسکن را تغییر دهید
- Proxy Mode را جداگانه با v2rayNG تست کنید
- Routing Strategy را تغییر دهید
- IPهای ذخیره‌شده را پاک کنید و دوباره اسکن کنید

## NipoVPN وصل نمی‌شود

- مطمئن شوید لینک با `nipovpn://` شروع می‌شود
- روی Add Profile بزنید
- پروفایل را انتخاب کنید
- Server IP و Server Port را بررسی کنید
- Test بزنید
- Save Profile بزنید
- دوباره Connect کنید

## DNS (MasterDNS) وصل نمی‌شود

- پروفایل MasterDNS انتخاب‌شده را بررسی کنید
- پروفایل Resolver را بررسی کنید
- لاگ‌ها را Refresh کنید و آخرین خطا را ببینید
- Resolver دیگری تست کنید
- لاگ‌ها را Clear کنید و دوباره Connect بزنید
- اگر runtime گیر کرده، Disconnect و دوباره Start کنید

## CF Config کار نمی‌کند

- ابتدا MSP scanning را اجرا کنید تا IPهای سالم در دسترس باشند
- مطمئن شوید کانفیگ با `vless://` شروع می‌شود
- روی Latency All بزنید
- IP سالم‌تری انتخاب کنید
- SNI / Host / Path کانفیگ اصلی را بررسی کنید

---

# 🧱 ساختار فنی

## داده‌های داخلی مهم

SIMORGH داده‌های مورد نیاز اسکن IP، SNI، ServerLess و NipoVPN را داخل برنامه مدیریت می‌کند، بنابراین مسیر فایل‌های سورس در این README نمایش داده نشده است.

## جریان‌های ترافیک

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

### Fragment

```text
VLESS / Trojan link
   ↓
Generated Xray JSON
   ↓
Address override
   ↓
Fragment/finalmask settings
   ↓
Xray
   ↓
Android TUN / tun2socks
```

### MSP VPN Mode

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

### MSP Proxy Mode

```text
Local App / v2rayNG
   ↓
127.0.0.1:9990 SOCKS5
or
127.0.0.1:9991 HTTP
   ↓
RKh-MSP Clean IP Pool
```

### DNS (MasterDNS)

```text
MasterDNS profile
   ↓
Resolver profile
   ↓
MasterDNS runtime
   ↓
Local SOCKS / DNS bridge
   ↓
Xray bridge when needed
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

1. APK را نصب کنید.
2. مجوز VPN را بدهید.
3. از **MSP** برای شرایط اینترانت ملی و اختلال اینترنت بین‌الملل استفاده کنید.
4. از **Simple** برای اتصال سریع XRAY استفاده کنید.
5. از **Fragment** برای Cloudflare Dirty IP Recovery با کانفیگ‌های VLESS/Trojan استفاده کنید.
6. از **NipoVPN** برای پروفایل‌های `nipovpn://` استفاده کنید.
7. از **DNS (MasterDNS)** برای تست DNS و پروفایل‌های Resolver استفاده کنید.

---

# 🔐 مجوزهای مورد نیاز

- دسترسی اینترنت
- مجوز VPN
- دسترسی وضعیت شبکه
- Foreground Service برای اتصال پایدار

---

# 👨‍💻 ساخته شده توسط

```text
Made By RKh!
Telegram: @pingplas_channel
```

---

# ⚠️ سلب مسئولیت

این پروژه فقط برای استفاده آموزشی، پژوهشی و قانونی ارائه شده است. مسئولیت نحوه استفاده از برنامه بر عهده کاربر است. لطفاً طبق قوانین منطقه خود از این ابزار استفاده کنید.

---

# ⭐ حمایت

اگر SIMORGH برای شما مفید بود، لطفاً در GitHub به پروژه ستاره بدهید.

```text
SIMORGH VPN
Fast. Smart. MSP. Fragment, DNS, and NipoVPN Ready.
```
