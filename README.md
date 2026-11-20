# 🎬 Miruro & Ani.pm — Cloudstream Provider Repository

[![Build and Publish Cloudstream Plugins](https://github.com/sabbirbd3001-source/cloudstream-miruro-anipm/actions/workflows/build.yml/badge.svg)](https://github.com/sabbirbd3001-source/cloudstream-miruro-anipm/actions/workflows/build.yml)
[![Cloudstream v3](https://img.shields.io/badge/Cloudstream-v3%20Compatible-blue.svg)](https://github.com/recloudstream/cloudstream)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-purple.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-GPLv3-green.svg)](LICENSE)

[English](#english) | [বাংলা](#বাংলা)

---

## <a name="বাংলা"></a>🇧🇩 বাংলা বিবরণ (Bengali Overview)

এটি **Cloudstream 3**-এর জন্য একটি অফিশিয়াল মানের এক্সটেনশন রিপোজিটরি (Extension Repository)। এখানে জনপ্রিয় ও আধুনিক দুটি অ্যানিমে সাইটের জন্য হাই-পারফরম্যান্স প্লাগইন অন্তর্ভুক্ত রয়েছে:
1. **Miruro** (`https://www.miruro.cx/`)
2. **Ani.pm** (`https://ani.pm/`)

### ✨ মূল বৈশিষ্ট্যসমূহ (Key Features):

#### 1. 🌟 Ani.pm Provider (`com.anipm`)
- **সম্পূর্ণ হোম ফিড সাপোর্ট**: Spotlight, Trending, Top Watched Today, Top Watched This Week, Top Anime Movies এবং Latest Airing।
- **তাত্ক্ষণিক সার্চ ও ইউআরএল ডিটেকশন**: নাম লিখে সার্চ ছাড়াও যেকোনো Ani.pm অ্যানিমে লিঙ্ক (`/anime/{slug}-{id}`) সরাসরি ওপেন করার সুবিধা।
- **সাব এবং ডাব উভয়ই সাপোর্ট**: Japanese Sub (ইংলিশ সাবটাইটেলসহ) এবং English Dub ট্র্যাক সরাসরি উপলব্ধ।
- **সম্পূর্ণ প্লেব্যাক পাইপলাইন**: Settlar session ও `embed.settlar.io` ডিরেক্ট HLS (m3u8) এক্সট্রাকশন—১০৮০p ক্রিস্টাল ক্লিয়ার স্ট্রিমিং।
- **উন্নত সাবটাইটেল**: Plain VTT সাবটাইটেল এবং স্টাইলিশ ASS সাবটাইটেল সাপোর্ট।
- **ক্লাউডফ্লেয়ার বাইপাস প্রোটেকশন**: `WebViewResolver` এ `ani.pm` ও `settlar.io` ডোমেইন ইন্টিগ্রেটেড।

#### 2. ⚡ Miruro Provider (`com.miruro`)
- **ক্যাটালগ ব্রাউজিং**: Trending, Most Popular, Airing Schedule এবং Top Rated অ্যানিমে কালেকশন।
- **হাইব্রিড SSR + API ইঞ্জিন**: Miruro-এর এনক্রিপ্টেড `__SSR_DATA__` এবং REST API উভয় থেকেই ১০০% নির্ভুল এপিসোড তালিকা, পোস্টার, ব্যানার এবং ইউটিউব ট্রেইলার এক্সট্রাকশন।
- **ক্যাটালগ ডিক্রিপশন**: Miruro-এর XOR এনক্রিপ্টেড এবং GZIP কম্প্রেসড API ও SSR ডেটা ডিকোড করার সুবিধা।
- **সরাসরি আইডি ও মেটাডেটা ম্যাপিং**: `external_ids` থেকে স্বয়ংক্রিয় AniList ID এবং MAL ID লিঙ্কিং।
- **Keeply Proxy স্ট্রিমিং ও সাবটাইটেল**: Miruro-এর `s1.keeply.top` প্রক্সি m3u8 ও `/sub.vtt` সাবটাইটেল সরাসরি সাপোর্ট।
- **Cloudflare বাইপাস**: ক্লাউডস্ট্রিমের অ্যান্ড্রয়েড `WebViewResolver` যুক্ত থাকায় ক্লাউডফ্লেয়ার চ্যালেঞ্জ স্বয়ংক্রিয়ভাবে হ্যান্ডেল হয়।

---

### 📲 ক্লাউডস্ট্রিমে যেভাবে যুক্ত করবেন (How to Install in Cloudstream)

#### পদ্ধতি ১: ১-ক্লিক ইনস্টল (One-Click Install)
আপনার অ্যান্ড্রয়েড ফোনে নিচের লিংকে ক্লিক করুন:
👉 **[Add to Cloudstream (১-ক্লিক ইনস্টল)](cloudstreamrepo://raw.githubusercontent.com/sabbirbd3001-source/cloudstream-miruro-anipm/builds/plugins.json)**

#### পদ্ধতি ২: ম্যানুয়াল ইনস্টলেশন (Manual Setup)
1. **Cloudstream অ্যাপটি ওপেন করুন**।
2. নিচের ডানে **Settings (গিয়ার আইকন)** এ যান।
3. **Extensions** ট্যাবে ট্যাপ করুন।
4. **Add Repository** বাটনে চাপ দিন।
5. নিচের তথ্যগুলো দিন:
   - **Repository Name**: `Miruro & Ani.pm Repository`
   - **Repository URL**:
     ```text
     https://raw.githubusercontent.com/sabbirbd3001-source/cloudstream-miruro-anipm/builds/plugins.json
     ```
6. **Download / Save** দিন।
7. এখন Extensions লিস্টে গিয়ে **Ani.pm** ও **Miruro** প্লাগইন দুটি **Install** বাটনে ক্লিক করে ইনস্টল করুন!

---

## <a name="english"></a>🇬🇧 English Documentation

An extension repository for **Cloudstream 3** providing providers for two anime streaming services:
- **Miruro** (`https://www.miruro.cx/`)
- **Ani.pm** (`https://ani.pm/`)

### 📦 Repository Structure

```text
cloudstream-miruro-anipm/
├── .github/
│   └── workflows/
│       └── build.yml              # Automated CI/CD workflow for GitHub Actions
├── MiruroProvider/                # Miruro (miruro.cx) provider module
│   ├── src/main/kotlin/com/miruro/
│   │   ├── MiruroPlugin.kt        # CloudstreamPlugin registration
│   │   └── MiruroProvider.kt      # MainAPI implementation (API, Keeply Proxy, XOR/Gzip)
│   ├── src/main/res/drawable/     # Plugin icon
│   ├── src/test/kotlin/com/miruro/# Comprehensive unit tests
│   └── build.gradle.kts           # Module build configuration
├── AniPmProvider/                 # Ani.pm (ani.pm) provider module
│   ├── src/main/kotlin/com/anipm/
│   │   ├── AniPmPlugin.kt         # CloudstreamPlugin registration
│   │   └── AniPmProvider.kt       # MainAPI implementation (Home feed, Settlar & Embed HLS)
│   ├── src/main/res/drawable/     # Plugin icon
│   ├── src/test/kotlin/com/anipm/ # Unit tests for data models & parsing
│   └── build.gradle.kts           # Module build configuration
├── build.gradle.kts               # Root Gradle project configuration
├── settings.gradle.kts            # Multi-module settings
├── repo.json                      # Cloudstream repository descriptor
├── plugins.json                   # Generated plugin index for Cloudstream app
└── README.md                      # Documentation
```

---

### 🛠️ Local Development & Build Guide

#### Prerequisites
- **JDK 17 or 21**
- **Android SDK** (API Level 35)

#### 1. Run Unit Tests
```bash
./gradlew test
```
All tests under `MiruroProviderTest` and `AniPmProviderTest` verify:
- API endpoints & URL builders
- JSON data model serialization/deserialization
- Keeply proxy token generation & XOR decryption
- Subtitle extraction & type mapping

#### 2. Build `.cs3` and `.jar` Plugins
```bash
./gradlew makePlugins
```
The compiled output will be generated in:
- `MiruroProvider/build/MiruroProvider.cs3`
- `AniPmProvider/build/AniPmProvider.cs3`
- `build/plugins.json`

---

### 🚀 GitHub Actions Setup (Publish to GitHub)

1. Create a new GitHub repository: e.g. `cloudstream-miruro-anipm`.
2. Push this project to the `main` branch:
   ```bash
   git init
   git add .
   git commit -m "Initial commit of Miruro and Ani.pm Cloudstream repository"
   git branch -M main
   git remote add origin https://github.com/sabbirbd3001-source/cloudstream-miruro-anipm.git
   git push -u origin main
   ```
3. GitHub Actions will automatically run `.github/workflows/build.yml`:
   - It compiles both plugins.
   - It generates `.cs3` and `.jar` packages.
   - It writes `plugins.json` with file hashes and sizes.
   - It publishes everything to the `builds` orphan branch automatically.
4. Your Cloudstream repository URL will be ready at:
   ```text
   https://raw.githubusercontent.com/sabbirbd3001-source/cloudstream-miruro-anipm/builds/plugins.json
   ```

---

### 💡 Troubleshooting & FAQ

- **Streams buffering or slow?**
  Go to Cloudstream **Settings -> Player** and ensure **Hardware Acceleration** is enabled and player is set to **ExoPlayer** or **MPV**.
- **Blocked by ISP / DNS issue?**
  Go to Cloudstream **Settings -> Network** and turn on **DNS-over-HTTPS (DoH)** with Cloudflare (`1.1.1.1`) or Google (`8.8.8.8`).
- **Cloudflare Challenge?**
  Cloudstream has a built-in headless WebView that resolves Cloudflare challenges automatically. Ensure Android System WebView is up to date in the Google Play Store.
