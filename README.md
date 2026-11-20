<div align="center">

# 📱 Smart USSD

**A clean, modern Android app that turns plain USSD text menus into tappable buttons.**

Dial a code, read the reply in a beautiful screen, tap an option instead of typing numbers.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Java-ED8B00?logo=openjdk&logoColor=white)
![UI](https://img.shields.io/badge/theme-dark-0A1020)
![Status](https://img.shields.io/badge/status-in%20development-22D3EE)

</div>

---

## ✨ Why Smart USSD?

The normal phone dialer shows USSD replies in a small system pop-up. You have to read a wall of text, type a number, and press Send, again and again. **Smart USSD** reads that reply for you and shows it as a clear full-screen page:

- Menu options become **big tappable buttons** with highlighted number badges
- Balance, validity and similar replies become **neat label / value rows**
- Numbers, amounts and dates **stand out** in bold colour
- If the network does not answer, the app **resets itself and returns to the home page**

---

## 🚀 Features

| | Feature |
|---|---|
| 🔢 | Built-in dial pad with backspace, so no system keyboard is needed |
| 📶 | **SIM selector** for dual-SIM phones |
| 🧠 | Smart parser that detects menu items such as `1. Balance`, `2. Packs` |
| 👆 | Tap an option to send it, no typing needed |
| ⬅️ | **Back / Next / Home** options get their own button row at the bottom |
| 🎨 | Highlighted buttons and number badges for easy reading |
| 📊 | Key-value replies (`Balance: TK 0.31, EXP: 09-09-26`) shown as clean stat rows |
| ⏳ | Animated loader with a **Cancel** button |
| ♻️ | **Timeout reset:** everything is cleared and you land on the home page |
| ⚠️ | Friendly error screens for refused codes, missing permissions and service problems |
| 🌙 | Dark theme with a splash screen |
| 🔒 | Portrait layout, no ads, no data stored |

---

## 🖼️ Screenshots

> Add your own screenshots to a `screenshots/` folder and update the paths below.

| Home | Loading | Menu result | Timeout reset |
|:---:|:---:|:---:|:---:|
| ![Home](screenshots/home.png) | ![Loading](screenshots/loading.png) | ![Menu](screenshots/menu.png) | ![Reset](screenshots/reset.png) |

---

## 🔧 How it works

Smart USSD has two ways of talking to the network.

1. **With the USSD helper turned on (recommended).**
   The app dials the code through the system dialer and keeps the system USSD dialog open in the background. An Accessibility Service reads the reply text and Smart USSD shows it as its own page. When you tap a button, the number is typed into the hidden dialog and **Send** is pressed for you. This gives a full interactive session, including PIN and amount entry.

2. **Without the helper.**
   The app chains your choice onto the code instead. For example `*121#` followed by `1` becomes `*121*1#`. Free-text input such as `*` and `#` needs the helper.

```
 You dial *121#
      │
      ▼
 USSDManager ──► system dialer ──► network
      │                               │
      │         reply text            │
      ◄───────────────────────────────┘
      ▼
 USSDResponseParser ──► menu items + body text
      │
      ▼
 ResponsePanel ──► buttons, stat rows, highlighted numbers
```

---

## 🔐 Permissions

| Permission | Why it is needed |
|---|---|
| `CALL_PHONE` | To send the USSD code |
| `READ_PHONE_STATE` | To list your SIM cards and pick the right one |
| `ACCESS_NETWORK_STATE` | To check the network state |
| `INTERNET` | Reserved for future features, not used for USSD |
| Accessibility Service (optional) | Reads the USSD reply from the system phone dialog so it can be shown inside the app |

**Privacy:** the Accessibility Service only reads the USSD reply from the system phone dialog so it can be displayed in the app. No other data is read or stored.

---

## ⚙️ Enable the USSD helper

1. Open the app and dial a code. The app will offer to enable the helper.
2. Tap **Enable USSD helper** to open Accessibility settings.
3. Open **Installed apps** (or **Downloaded apps**) and choose **Smart USSD**.
4. Turn the switch **ON** and tap **Allow**.
5. Come back to the app and dial your code again.

> 💡 If the switch is greyed out, go to **Settings › Apps › Smart USSD**, tap **⋮** and choose **Allow restricted settings**.

---

## 🛠️ Build from source

**Requirements**

- Android Studio (recent version)
- A physical phone with a SIM card. USSD does **not** work on emulators.

**Steps**

```bash
git clone https://github.com/sohan-parves/SmartUSSD.git
cd SmartUSSD
```

1. Open the folder in Android Studio and let Gradle sync.
2. Connect your phone with USB debugging turned on.
3. Press **Run ▶**.
4. Allow the phone permissions when the app asks.

---

## 🎛️ Configuration

Timing is controlled in `USSDManager.java`:

| Constant | Default | Meaning |
|---|---|---|
| `RETRY_AFTER_MS` | `8000` | Time before a silent re-dial, used only when auto retry is on |
| `MAX_AUTO_RETRIES` | `0` | Number of silent re-dials. `0` means auto retry is **off** |
| `TOTAL_WAIT_MS` | calculated | Longest the loader waits before the timeout reset |

---

## 📁 Project structure

```
src/main
├── java/com/sohan/smartussd
│   ├── MainActivity.java            # Home page, session and timeout logic
│   ├── DialerActivity.java
│   ├── USSDActivity.java
│   ├── adapters/
│   │   └── USSDMenuAdapter.java     # Menu buttons and navigation row
│   ├── models/
│   │   └── USSDMenuItem.java
│   ├── ui/
│   │   ├── ResponsePanel.java       # Loader, result and error screens
│   │   ├── BodyRenderer.java        # Stat rows and highlighted numbers
│   │   ├── LoadingView.java
│   │   ├── ErrorView.java
│   │   ├── DialPadView.java
│   │   └── PulseLoaderView.java
│   └── ussd/
│       ├── USSDManager.java         # Dialing, errors and timeouts
│       ├── USSDResponseParser.java  # Turns reply text into menu items
│       ├── USSDSession.java
│       └── USSDAccessibilityService.java
└── res/                             # Layouts, drawables, colours, themes
```

---

## ⚠️ Notes

- USSD behaviour differs between phone brands, Android versions and operators. If something does not work on your device, please open an issue with your phone model and Android version.
- This app does not replace your operator's service. It only changes how replies are shown.

---

## 🤝 Contributing

Ideas and pull requests are welcome.

1. Fork the repository
2. Create a branch: `git checkout -b feature/my-idea`
3. Commit your changes: `git commit -m "Add my idea"`
4. Push the branch: `git push origin feature/my-idea`
5. Open a Pull Request

---

## 📄 License

Choose a license for your project, for example [MIT](https://choosealicense.com/licenses/mit/), and add a `LICENSE` file.

---

<div align="center">

Made with ❤️ by **YOUR_NAME**

If this project helps you, give it a ⭐ on GitHub.

</div>
