# TGwear Architecture Documentation

> **Generated:** 2026-02-01  
> **Updated:** 2026-02-22  
> **Project:** TGwear - Telegram Client for Wear OS  
> **Version:** Analysis based on current codebase (post connection-fix update)

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Layer Breakdown](#layer-breakdown)
4. [Data Flow](#data-flow)
5. [Key Components](#key-components)
6. [Best Practices Analysis](#best-practices-analysis)
7. [Recent Changes (2026-02-22)](#recent-changes-2026-02-22)
8. [Improvement Recommendations](#improvement-recommendations)
9. [Security Considerations](#security-considerations)

---

## Overview

TGwear is a standalone Telegram client designed specifically for Wear OS smartwatches. It uses **TDLib** (Telegram Database Library) for all Telegram protocol handling and features a **Jetpack Compose** UI optimized for round watch displays.

### Tech Stack

| Layer | Technology |
|-------|------------|
| **UI** | Jetpack Compose for Wear OS |
| **Language** | Kotlin (primary), Java (TDLib bindings, VoIP) |
| **Telegram Protocol** | TDLib (native C++ with JNI bindings) |
| **Push Notifications** | Firebase Cloud Messaging (FCM) |
| **Voice Calls** | VoIP module (from Challegram project) |
| **Min SDK** | Android 7.1+ (API 25) with Wear OS |

---

## Architecture Diagram

```mermaid
graph TB
    subgraph "Presentation Layer - Wear OS UI"
        MA[MainActivity<br/>Chat List]
        CA[ChatActivity<br/>Messages]
        LA[LoginActivity<br/>Authentication]
        SA[SettingActivity<br/>Settings]
        VA[VoiceCallActivity<br/>VoIP Calls]
        subgraph "UI Components"
            MC[MainScreen]
            CC[ChatScreen]
            LC[LoginScreen]
            SC[SettingScreen]
        end
    end
    
    subgraph "Application Layer"
        TG[TgApi<br/>Core API Handler]
        UH[UpdateHandle<br/>Event Processor]
        CS[ClientSend<br/>Sync Requests]
        SR[SendRequest<br/>Async Requests]
        TPN[TgApiForPushNotification<br/>Background Handler]
    end
    
    subgraph "Data Layer"
        MODEL[Model Classes<br/>Chat, Message, etc.]
        SP[SharedPreferences<br/>Local Storage]
    end
    
    subgraph "External Services"
        TDLIB[TDLib<br/>Telegram Database Library]
        FCM[Firebase Cloud Messaging]
        VOIP[VoIP Module<br/>Voice Calls]
        CM[ConnectivityManager<br/>Network Monitoring]
    end
    
    subgraph "Remote"
        TG_SERVER[☁️ Telegram Servers<br/>DC1-DC5]
    end
    
    MA --> TG
    CA --> TG
    LA --> TG
    SA --> TG
    VA --> TG
    VA --> VOIP
    
    TG --> UH
    TG --> CS
    TG --> SR
    TG --> MODEL
    TG --> SP
    TG --> CM
    
    CM -->|SetNetworkType| TDLIB
    
    UH --> MODEL
    
    CS --> TDLIB
    SR --> TDLIB
    
    TPN --> TDLIB
    FCM --> TPN
    
    TDLIB --> TG_SERVER
    VOIP --> TG_SERVER
    
    classDef ui fill:#4a90d9,stroke:#2c5282,color:#fff
    classDef app fill:#48bb78,stroke:#276749,color:#fff
    classDef data fill:#ed8936,stroke:#c05621,color:#fff
    classDef external fill:#9f7aea,stroke:#6b46c1,color:#fff
    classDef remote fill:#fc8181,stroke:#c53030,color:#fff
    
    class MA,CA,LA,SA,VA,MC,CC,LC,SC ui
    class TG,UH,CS,SR,TPN app
    class MODEL,SP data
    class TDLIB,FCM,VOIP,CM external
    class TG_SERVER remote
```

---

## Layer Breakdown

### 1. Presentation Layer (UI)

```mermaid
graph LR
    subgraph "Activities"
        A1[MainActivity]
        A2[ChatActivity]
        A3[LoginActivity]
        A4[SettingActivity]
        A5[VoiceCallActivity]
        A6[ViewActivity]
        A7[ChatInfoActivity]
    end
    
    subgraph "Compose Screens"
        S1[MainScreen]
        S2[SplashChatScreen]
        S3[LoginScreens]
        S4[SettingScreen]
        S5[VoiceCallScreen]
    end
    
    subgraph "Shared Components"
        C1[Components.kt]
        C2[Theme]
        C3[verticalRotaryScroll]
    end
    
    A1 --> S1
    A2 --> S2
    A3 --> S3
    A4 --> S4
    A5 --> S5
    
    S1 --> C1
    S2 --> C1
    S3 --> C1
    S4 --> C1
    S5 --> C1
```

**Key Files:**

- `MainActivity.kt` - Entry point, chat list display
- `ChatActivity.kt` - Individual chat conversation
- `LoginActivity.kt` - Phone verification, 2FA, QR login
- `SettingActivity.kt` - App configuration
- `VoiceCallActivity.kt` - Voice call interface

### 2. Application Layer (Business Logic)

```mermaid
graph TB
    subgraph "TgApi Core"
        INIT[Initialization]
        AUTH[Authorization]
        CHAT[Chat Management]
        MSG[Message Handling]
    end
    
    subgraph "UpdateHandle"
        UH1[handleAuthorizationState]
        UH2[handleNewMessage]
        UH3[handleConnectionUpdate]
        UH4[handleCallUpdate]
        UH5[handleChatUpdates]
    end
    
    subgraph "Request Handling"
        CS1[ClientSend - Synchronous]
        SR1[SendRequest - Coroutines]
    end
    
    INIT --> AUTH
    AUTH --> CHAT
    CHAT --> MSG
    
    MSG --> UH1
    MSG --> UH2
    MSG --> UH3
    MSG --> UH4
    MSG --> UH5
```

**Key Files:**

- `TgApi.kt` - Core Telegram client wrapper
- `UpdateHandle.kt` - TDLib event processor (1000+ lines)
- `ClientSend.kt` - Synchronous API operations
- `SendRequest.kt` - Coroutine-based async operations

### 3. Data Layer

```mermaid
graph LR
    subgraph "Models"
        M1[Chat]
        M2[Message via TdApi]
        M3[ChatMessagesSave]
        M4[SettingItem]
        M5[NotificationMessage]
    end
    
    subgraph "Storage"
        S1[SharedPreferences]
        S2[TDLib Database]
        S3[External Files]
    end
    
    M1 --> S1
    M2 --> S2
    M3 --> S1
```

---

## Data Flow

### Message Reception Flow

```mermaid
sequenceDiagram
    participant TG as Telegram Servers
    participant TD as TDLib
    participant UH as UpdateHandle
    participant API as TgApi
    participant UI as ChatActivity
    
    TG->>TD: New Message
    TD->>UH: UpdateNewMessage
    UH->>API: handleNewMessage()
    API->>API: Update saveChatList
    API-->>UI: MutableState triggers recomposition
    UI->>UI: Display new message
```

### Connection State Flow

```mermaid
stateDiagram-v2
    [*] --> WaitingForNetwork
    WaitingForNetwork --> Connecting: Network available
    Connecting --> ConnectingToProxy: Proxy configured
    Connecting --> Updating: Connected
    Connecting --> Connecting: Recovery timer (30s)\nForces network type refresh
    ConnectingToProxy --> Updating: Proxy connected
    ConnectingToProxy --> ConnectingToProxy: Recovery timer (30s)
    Updating --> Ready: Sync complete
    Ready --> Connecting: Connection lost
    Ready --> [*]: App closed
    
    note right of Connecting
        NetworkCallback monitors connectivity.
        On Wear OS, checks for Wi-Fi, BT proxy,
        and LTE before reporting offline.
        30s recovery timer forces reconnect if stuck.
    end note
```

---

## Key Components

### TgApiManager (Singleton)

```kotlin
object TgApiManager {
    var tgApi: TgApi? = null  // Global TgApi instance
}
```

### Connection State Handler

```kotlin
// UpdateHandle.kt - handleConnectionUpdate()
when (update.state.constructor) {
    ConnectionStateReady.CONSTRUCTOR -> {
        isInConnectingState = false
        cancelConnectionRecovery()
        topTitle.value = ""
    }
    ConnectionStateConnecting.CONSTRUCTOR -> {
        isInConnectingState = true
        topTitle.value = "Connecting"
        scheduleConnectionRecovery()   // 30s auto-retry
    }
    ConnectionStateWaitingForNetwork.CONSTRUCTOR -> {
        isInConnectingState = false
        cancelConnectionRecovery()
        topTitle.value = "Offline"
    }
}
```

### Network Connectivity Monitor (New)

```kotlin
// TgApi.kt - Registered in init {}
// Uses registerDefaultNetworkCallback to track Wear OS preferred network
// (handles Wi-Fi, Bluetooth proxy, LTE)
connectivityManager.registerDefaultNetworkCallback(object : NetworkCallback() {
    override fun onAvailable(network: Network) {
        client.send(SetNetworkType(getActiveNetworkType())) { ... }
        cancelConnectionRecovery()
    }
    override fun onLost(network: Network) {
        // Check for remaining networks (e.g. BT proxy) before going offline
        val remaining = getActiveNetworkType()
        client.send(SetNetworkType(remaining)) { ... }
    }
})
```

---

## Best Practices Analysis

### ✅ What's Done Well

| Area | Implementation |
|------|----------------|
| **UI Architecture** | Uses Jetpack Compose with MutableState for reactive updates |
| **Coroutines** | Uses `lifecycleScope` for lifecycle-aware coroutines |
| **Error Handling** | Implements `CoroutineExceptionHandler` for crash recovery |
| **Theme Support** | Proper theming with `TGwearTheme` |
| **State Management** | Uses `mutableStateOf` for reactive UI updates |
| **Modularization** | Separates concerns (UI, API, Utils, Notifications) |

### ⚠️ Issues Found

| Severity | Issue | Location | Status |
|----------|-------|----------|--------|
| 🔴 **Critical** | Hardcoded API credentials in assets | `config.properties` | ⚠️ Open |
| 🔴 **Critical** | Static field leak annotations suppressed | `TgApiManager`, `ChatsListManager` | ⚠️ Open |
| 🟠 ~~**High**~~ | ~~Excessive use of `runBlocking`~~ | ~~Multiple files (24+ occurrences)~~ | ✅ **Fixed** (2026-02-22) |
| 🟠 **High** | Console logging with `println` | 300+ occurrences throughout | ⚠️ Open |
| 🟠 **High** | Generic exception catching | 50+ `catch (e: Exception)` blocks | ⚠️ Open |
| 🟡 **Medium** | Test mode backdoor | `TgApi.kt:127` | ⚠️ Open |
| 🟡 **Medium** | No dependency injection | Throughout | ⚠️ Open |
| 🟡 **Medium** | Large monolithic files | `UpdateHandle.kt` (1000+ lines) | ⚠️ Open |
| 🟢 **Low** | Mixed language in comments | Throughout | ⚠️ Open |

---

## Recent Changes (2026-02-22)

### Connection Stability Overhaul

Four major fixes were implemented to resolve the intermittent "Connecting..." issue that required force-stopping the app:

#### 1. 🛜 Network Change Detection (`TgApi.kt`)

**Problem:** TDLib had no awareness of Android network changes. On Wear OS, where connectivity frequently switches between Wi-Fi, Bluetooth proxy (via phone), and LTE, TDLib's internal socket-based detection was unreliable.

**Solution:** Added `ConnectivityManager.registerDefaultNetworkCallback()` that:

- Detects when network becomes available → calls `TdApi.SetNetworkType` to wake TDLib
- Detects when network is lost → checks for remaining networks (e.g., Bluetooth proxy) before telling TDLib to go offline
- Tracks network capability changes (Wi-Fi ↔ Bluetooth ↔ Cellular) and updates TDLib accordingly
- Uses `registerDefaultNetworkCallback` (not `registerNetworkCallback`) for proper Wear OS proxy support
- Properly unregisters callback in `close()` to prevent memory leaks

**Files:** `TgApi.kt` (new methods: `registerNetworkCallback()`, `getActiveNetworkType()`, `getNetworkTypeFromCapabilities()`)

#### 2. 🔋 Battery Optimization Exemption (`AndroidManifest.xml`, `MainActivity.kt`)

**Problem:** Wear OS aggressively kills background network connections via battery optimization. No exemption was requested.

**Solution:**

- Added `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission to manifest
- Added `requestBatteryOptimizationExemption()` method called during app initialization
- Prompts user once to exempt TGwear from battery optimization

**Files:** `AndroidManifest.xml`, `MainActivity.kt`

#### 3. 🔄 Connection Recovery Timer (`TgApi.kt`, `UpdateHandle.kt`)

**Problem:** When TDLib entered `Connecting` state, the handler simply set the title and waited passively. No retry or recovery mechanism existed.

**Solution:** Added a 30-second recovery timer system:

- When TDLib enters `Connecting` or `ConnectingToProxy` state → starts a 30-second timer
- After 30s of being stuck → forces a `SetNetworkType(None)` → `SetNetworkType(actual)` cycle to kick reconnection
- Timer reschedules itself if still stuck (repeated 30s retries)
- Timer is cancelled when connection becomes `Ready`, `Updating`, or `WaitingForNetwork`

**Files:** `TgApi.kt` (new methods: `scheduleConnectionRecovery()`, `cancelConnectionRecovery()`), `UpdateHandle.kt`

#### 4. 🧵 Replace `runBlocking` with Proper Coroutines

**Problem:** 24+ `runBlocking` calls throughout the codebase could deadlock the main thread, especially when TDLib was in a connecting/waiting state. This caused the UI to freeze and appear permanently stuck.

**Solution:** Replaced all instances with appropriate async patterns:

| File | Before | After |
|------|--------|-------|
| `ChatActivity.onDestroy` | `runBlocking { exitChatPage() }` | `lifecycleScope.launch(Dispatchers.IO)` |
| `ChatActivity.init` | `runBlocking { getChat() }` | Direct suspend call (already in suspend fun) |
| `ChatActivity` longPress | `runBlocking { getMessageTypeById() }` | `lifecycleScope.launch(Dispatchers.IO)` |
| `ChatInfoActivity.init` | 6× `runBlocking { ... }` | Direct suspend calls (already in suspend fun) |
| `LoginActivity.onDestroy` | `runBlocking { client.send() }` | Direct `client.send()` (already async) |
| `VoiceCallActivity.onCreate` | `runBlocking { getChat() }` | `CoroutineScope(Dispatchers.IO).launch` + `withContext(Main)` |
| `SendRequest.deleteMessageById` | `runBlocking { sendRequest() }` | `CoroutineScope(Dispatchers.IO).launch` |

**Files:** `ChatActivity.kt`, `ChatInfoActivity.kt`, `LoginActivity.kt`, `VoiceCallActivity.kt`, `SendRequest.kt`

---

## Improvement Recommendations

### 1. 🔒 Security Improvements (Critical)

#### 1.1 Remove Hardcoded API Credentials

**Current (Insecure):**

```properties
# config.properties
api_id=20508610
api_hash=dafae213c4ac948a2e24e80861b032c6
```

**Recommended:**

```kotlin
// Use BuildConfig or encrypted preferences
object ApiConfig {
    val apiId: Int
        get() = BuildConfig.TELEGRAM_API_ID
    
    val apiHash: String
        get() = EncryptedSharedPreferences
            .create(...)
            .getString("api_hash", null)
            ?: throw SecurityException("API hash not configured")
}
```

#### 1.2 Remove Test Mode Backdoor

```kotlin
// TgApi.kt:127 - REMOVE THIS:
if (user.id == 7513554495 && user.usernames?.activeUsernames[0] == "tgwear_review_bot") 
    isTestMode()
```

### 2. 🧵 Concurrency Improvements (High Priority)

#### 2.1 Replace `runBlocking` with Proper Coroutines

**Current (Problematic):**

```kotlin
val userInfo = runBlocking {
    tgApi?.getUser(chatId)
}
```

**Recommended:**

```kotlin
// Use LaunchedEffect in Compose
LaunchedEffect(chatId) {
    val userInfo = tgApi?.getUser(chatId)
    userInfoState.value = userInfo
}

// Or use suspend functions properly
suspend fun fetchUserInfo(chatId: Long): UserInfo? {
    return withContext(Dispatchers.IO) {
        tgApi?.getUser(chatId)
    }
}
```

#### 2.2 Fix Memory Leaks

**Current (Leaky):**

```kotlin
object TgApiManager {
    @SuppressLint("StaticFieldLeak")  // ⚠️ Suppressing the warning doesn't fix it!
    var tgApi: TgApi? = null
}
```

**Recommended:**

```kotlin
// Use Application-scoped dependency injection
class TGwearApplication : Application() {
    val tgApiManager: TgApiManager by lazy {
        TgApiManager(applicationContext)
    }
}

class TgApiManager(private val context: Context) {
    private var _tgApi: TgApi? = null
    val tgApi: TgApi? get() = _tgApi
    
    fun initialize() { /* ... */ }
    fun cleanup() { _tgApi = null }
}
```

### 3. 📝 Logging Improvements

#### 3.1 Replace println with Proper Logging

**Current:**

```kotlin
println("TgApi: Connecting")
println("Message deleted successfully")
```

**Recommended:**

```kotlin
// Create a centralized logger
object TgLog {
    private const val TAG = "TGwear"
    
    fun d(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }
    
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        // Optionally report to Crashlytics
    }
}

// Usage
TgLog.d("Connecting to Telegram")
```

### 4. 🏗️ Architecture Improvements

#### 4.1 Implement Repository Pattern

```mermaid
graph LR
    UI[UI Layer] --> VM[ViewModel]
    VM --> REPO[Repository]
    REPO --> LOCAL[Local DataSource]
    REPO --> REMOTE[TDLib DataSource]
```

```kotlin
interface ChatRepository {
    fun getChats(): Flow<List<Chat>>
    suspend fun sendMessage(chatId: Long, text: String): Result<Message>
    suspend fun markAsRead(chatId: Long, messageId: Long)
}

class ChatRepositoryImpl(
    private val tdLibDataSource: TdLibDataSource,
    private val localDataSource: LocalDataSource
) : ChatRepository {
    override fun getChats(): Flow<List<Chat>> = flow {
        // Emit cached data first, then fresh data
        emit(localDataSource.getCachedChats())
        val freshChats = tdLibDataSource.loadChats()
        localDataSource.cacheChats(freshChats)
        emit(freshChats)
    }
}
```

#### 4.2 Use ViewModels

```kotlin
class ChatViewModel(
    private val repository: ChatRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    private val chatId = savedStateHandle.get<Long>("chatId") ?: 0L
    
    val messages = repository.getMessages(chatId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val connectionState = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Connecting)
    
    fun sendMessage(text: String) {
        viewModelScope.launch {
            repository.sendMessage(chatId, text)
        }
    }
}
```

### 5. 🔋 Wear OS Specific Improvements

#### 5.1 Battery Optimization Exemption ✅ (Implemented 2026-02-22)

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

```kotlin
// Implemented in MainActivity.kt - requestBatteryOptimizationExemption()
fun requestBatteryOptimizationExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}
```

#### 5.2 Implement Ambient Mode Support

```kotlin
class MainActivity : ComponentActivity(), AmbientModeSupport.AmbientCallbackProvider {
    
    private lateinit var ambientController: AmbientModeSupport.AmbientController
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientController = AmbientModeSupport.attach(this)
    }
    
    override fun getAmbientCallback() = object : AmbientModeSupport.AmbientCallback() {
        override fun onEnterAmbient(ambientDetails: Bundle?) {
            // Switch to low-power UI
        }
        
        override fun onExitAmbient() {
            // Restore full UI
        }
    }
}
```

### 6. 📦 Code Organization

#### 6.1 Split Large Files

```
Current:
  UpdateHandle.kt (1082 lines) 

Recommended:
  handlers/
    ├── AuthorizationHandler.kt
    ├── MessageHandler.kt
    ├── ConnectionHandler.kt
    ├── ChatHandler.kt
    ├── CallHandler.kt
    └── NotificationHandler.kt
```

#### 6.2 Use Sealed Classes for States

```kotlin
sealed class ConnectionState {
    object Ready : ConnectionState()
    object Connecting : ConnectionState()
    object ConnectingToProxy : ConnectionState()
    object Updating : ConnectionState()
    object WaitingForNetwork : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
```

---

## Security Considerations

### Current Vulnerabilities

```mermaid
graph TD
    V1[Hardcoded API Keys<br/>in config.properties] --> R1[🔴 Can be extracted<br/>from APK]
    V2[println logging<br/>sensitive data] --> R2[🟠 Visible in logcat]
    V3[Test mode backdoor<br/>for specific user ID] --> R3[🟡 Bypasses normal flow]
    V4[Network security config<br/>may block connections] --> R4[🟡 Connection issues]
```

### Recommended Security Measures

1. **API Credentials**: Use Android Keystore or encrypted BuildConfig
2. **Logging**: Remove all `println` statements, use production-safe logging
3. **Test Mode**: Remove or protect with proper flags
4. **Network Security**: Ensure Telegram servers are reachable
5. **Code Obfuscation**: Enable R8/ProGuard for release builds

---

## File Structure

```
TGwear/
├── app/
│   └── src/main/
│       ├── java/com/gohj99/tgwear/
│       │   ├── *.kt                    # Activities (26 files)
│       │   ├── model/                  # Data classes (7 files)
│       │   │   ├── Chat.kt
│       │   │   ├── Message.kt
│       │   │   └── ...
│       │   ├── ui/                     # Compose UI (36 files)
│       │   │   ├── chat/
│       │   │   ├── main/
│       │   │   ├── setting/
│       │   │   └── theme/
│       │   └── utils/
│       │       ├── telegram/           # TDLib wrapper (5 files)
│       │       │   ├── TgApi.kt
│       │       │   ├── UpdateHandle.kt
│       │       │   ├── ClientSend.kt
│       │       │   ├── SendRequest.kt
│       │       │   └── Utils.kt
│       │       └── notification/       # FCM handling (5 files)
│       │           ├── TgApiForPushNotification.kt
│       │           ├── TdFirebaseMessagingService.kt
│       │           └── ...
│       ├── assets/
│       │   └── config.properties       # ⚠️ Contains API keys
│       └── res/
├── libtd/                              # TDLib Java bindings
│   └── src/main/java/org/drinkless/tdlib/
│       ├── Client.java
│       └── TdApi.java                  # 3.8MB - Generated API
└── docs/
    └── ARCHITECTURE.md                 # This file
```

---

## Summary

### Priority Action Items

| Priority | Action | Effort | Impact | Status |
|----------|--------|--------|--------|--------|
| ~~1~~ | ~~Replace `runBlocking` with coroutines~~ | ~~Medium~~ | ~~High~~ | ✅ Done |
| ~~2~~ | ~~Add network connectivity monitoring~~ | ~~Medium~~ | ~~Critical~~ | ✅ Done |
| ~~3~~ | ~~Add battery optimization exemption~~ | ~~Low~~ | ~~High~~ | ✅ Done |
| ~~4~~ | ~~Add connection recovery mechanism~~ | ~~Medium~~ | ~~High~~ | ✅ Done |
| 5 | Remove hardcoded API credentials | Low | Critical | ⚠️ Open |
| 6 | Fix memory leak in TgApiManager | Medium | High | ⚠️ Open |
| 7 | Replace println with proper logging | Low | Medium | ⚠️ Open |
| 8 | Add ViewModel layer | High | High | ⚠️ Open |
| 9 | Split UpdateHandle.kt | Medium | Medium | ⚠️ Open |
| 10 | Add Wear OS ambient mode | Medium | Medium | ⚠️ Open |
| 11 | Implement dependency injection | High | High | ⚠️ Open |

---

*This document was generated by analyzing the TGwear codebase. Last updated: 2026-02-22.*
