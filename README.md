# 📺 TV Clean Home — Universal Launcher & Debloater
### For Amazon Fire TV Stick, onn. Google TV & Chromecast with Google TV

An ultra-lightweight (~18 MB RAM), zero-bloat, privacy-focused **Universal TV Home Launcher, Sideload App Drawer, Background RAM Booster & Safe Debloater**. Works seamlessly across **Amazon Fire TV Stick** (Fire OS 6, 7, and 8), **onn. Google TV** (FHD / 4K / Pro), and **Chromecast with Google TV**.

One universal APK, zero third-party dependencies, and runtime hardware detection.

---

## ⚡ Performance Comparison

| Metric | Stock Fire TV / Google TV | TV Clean Home v1.2.2 |
| :--- | :--- | :--- |
| **Launcher RAM Usage** | ~250 MB – 350 MB | **~18 MB** (Pure Android Framework) |
| **Ads & Autoplay Promos** | Sponsored banners, video trailers, shopping feeds | **Zero Ads / Pure OLED Dark Mode** |
| **Fire TV Settings & HUD** | Blocked by typical 3P launchers | **Full native access to Settings, Wi-Fi & HUD** |
| **Dedicated Remote Buttons** | "Free TV" / Live Guide breaks on custom launchers | **Fully whitelisted & supported** |
| **Sideloaded Apps** | Buried deep or hidden entirely | **Instant access via `+ Add App` drawer** |
| **Remote D-Pad Response** | Frequent lag and micro-stutters | **Instant (< 0.05s response)** |
| **Debloater Included** | None (manual command line required) | **1-Click Built-in Safe Debloater with Revert** |
| **Live Network Speed** | None | **Real-time Wi-Fi Link Speed & Live Bandwidth** |

---

## 🚀 How to Install on Amazon Fire TV Stick

### Method 1: The 1-Click PC Setup (Easiest & Fastest)
*Requirements: Your PC/laptop and Fire TV Stick connected to the same Wi-Fi.*

1. **On your Fire TV Stick**:
   - Go to **Settings ➔ My Fire TV ➔ About**.
   - Click **Fire TV Stick** repeatedly **7 times** until it says *"No need, you are already a developer"*.
   - Press Back, open **Developer Options**, and turn **ADB Debugging: ON**.
   - Note your Fire TV's IP address under **Settings ➔ My Fire TV ➔ About ➔ Network** (e.g. `192.168.1.150`).
2. **On your PC**:
   - Download and unzip **`TVCleaner-v1.2-PC-Tools.zip`**.
   - Double-click **`setup_firetv.bat`**.
   - Enter your Fire TV's IP address.
   - Look at your TV screen and click **Always allow / OK** on the prompt.
   - **Done!** TV Clean Home is installed and running as your primary home launcher.
3. *(Optional)* **To remove Amazon bloatware & telemetry:**
   - Double-click **`debloat_firetv.bat`** and select **[1] Safe Debloat**.

---

### Method 2: Direct on TV via Downloader (No PC Required)

1. On your Fire TV, install the free **Downloader** app from the Amazon Appstore.
2. In Fire TV **Settings ➔ My Fire TV ➔ Developer Options ➔ Install unknown apps**, turn **Downloader: ON**.
3. Open Downloader and enter the direct download URL for the APK:
   `https://velazquez-lab.pages.dev/downloads/TVCleaner.apk`
4. Click **Install**.
5. Go to **Settings ➔ Accessibility**, select **TV Clean Home**, and toggle it **ON**.
6. Press the **Home** button on your Fire TV remote — you are now on TV Clean Home!

---

## 📺 How to Install on onn. Google TV & Chromecast

### Method 1: 1-Click PC Setup
1. **On TV**:
   - Go to **Settings ➔ System ➔ About**.
   - Click **Android TV OS build** 7 times to enable Developer Options.
   - Go to **Settings ➔ System ➔ Developer Options**:
     - Turn **USB debugging: ON**.
     - Turn **Wireless debugging: ON**.
2. **On your PC**:
   - Double-click **`setup.bat`**.
   - Enter your TV's IP address (the script automatically connects to port 5555 on onn. sticks, or prompts for the 5-digit port on Chromecast).
   - **Done!**

---

## 🛠 Features & Remote Controls

- **Hardware Home Interception:** Pressing the remote's **Home** key from any app (YouTube, Netflix, Prime, Stremio, etc.) always returns cleanly to TV Clean Home.
- **Native Settings Access:** Click the **⚙ Settings** pill or press the remote's gear button to open system settings without getting kicked out.
- **Dedicated "Free TV" Button:** Compatible with the onn. remote's dedicated Free TV button — launches Google Freeplay channels without closing.
- **`[ + Add App ]` Sideload Drawer:** View and launch *every* installed app, including mobile sideloaded APKs.
- **Tile Customization:** Long-press **OK** or press the **Menu (`☰`)** button on any app tile to Move Left/Right or Hide it.
- **Live Bandwidth & Memory Monitor:** Tap the RAM pill anytime for an instant 1-click memory cleanup.
- **100% Open Source & Zero Dependencies:** Pure Android SDK framework classes only. APK size is under **50 KB**!

---

## 📜 Rebuilding the APK
To rebuild the universal signed APK in ~5 seconds from source:
```powershell
.\build.ps1
```

---

## Velazquez Lab

More free tools from Cesar Velazquez: [Velazquez Lab](https://velazquez-lab.pages.dev)

- GitHub: https://github.com/crtGhoul
- Codeberg: https://codeberg.org/crtGhoul
- YouTube: https://www.youtube.com/@VelazquezLabs
- Reddit: https://www.reddit.com/user/Manteqaada/
