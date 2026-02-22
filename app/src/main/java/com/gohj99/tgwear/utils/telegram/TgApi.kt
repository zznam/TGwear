/*
 * Copyright (c) 2024-2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.tgwear.utils.telegram

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.gohj99.tgwear.model.Chat
import com.gohj99.tgwear.model.ChatMessagesSave
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.thunderdog.challegram.voip.VoIPInstance
import java.io.File
import java.util.concurrent.CountDownLatch

class TgApi(
    internal val context: Context,
    var chatsList: MutableState<List<Chat>>,
    internal val userId: String = "",
    internal val topTitle: MutableState<String>,
    internal val chatsFoldersList: MutableState<List<TdApi.ChatFolder>>,
    internal val mainChatListPosition: MutableState<Int>,
    internal val onPaused: MutableState<Boolean>,
    internal val onCall: (TdApi.Call) -> Unit,
    internal val onError: (String) -> Unit
) {
    var saveChatId = 0L
    var replyMessage = mutableStateOf<TdApi.Message?>(null)
    var updateFileCallBackList = mutableMapOf<Int, (TdApi.File) -> Unit>()
    var errorSetTdlibParameters = false
    internal var saveChatMessagesList = mutableMapOf<Long, ChatMessagesSave>() //聊天在后台时更新
    internal var saveChatList = mutableStateOf(emptyList<TdApi.Message>()) // 保存的聊天列表，前台更新
    internal var saveChatIdList = mutableMapOf<Long, MutableList<Long>>()
    internal val client: Client = Client.create({ update -> handleUpdate(update) }, null, null)
    internal val sharedPref = context.getSharedPreferences("LoginPref", MODE_PRIVATE)
    internal val settingsSharedPref = context.getSharedPreferences("app_settings", MODE_PRIVATE)
    internal val chatMutex = Mutex()
    @Volatile
    internal var isAuthorized: Boolean = false
    internal val authLatch = CountDownLatch(1)
    internal var isExitChatPage = true
    internal var lastReadOutboxMessageId = mutableStateOf(0L)
    internal var lastReadInboxMessageId = mutableStateOf(0L)
    internal var currentUser: List<String> = emptyList()
    var forwardMessage: MutableState<TdApi.Message?> = mutableStateOf(null)
    var chatReadList = mutableStateMapOf<Long, Int>()
    var testMode = mutableStateOf(false)
    var callItem: TdApi.Call? = null
    var voipItem: VoIPInstance? = null
    var onCallback = mutableMapOf<Long, (TdApi.Call, String?) -> Unit>()
    var isIncomingCall = true

    // Network connectivity monitoring for Wear OS
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectionRecoveryRunnable: Runnable? = null
    internal var isInConnectingState = false

    init {
        // 获取应用外部数据目录
        val externalDir: File = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("Failed to get external directory.")
        // 获取API ID和API Hash
        val config = loadConfig(context)
        val tdapiId = config.getProperty("api_id").toInt()
        val tdapiHash = config.getProperty("api_hash")
        val encryptionKeyString = sharedPref.getString("encryption_key", null)
        val isUseTestDc = settingsSharedPref.getBoolean("useTestDc", false)
        client.send(TdApi.SetTdlibParameters().apply {
            databaseDirectory = externalDir.absolutePath + (if (userId == "") "/tdlib" else {
                "/$userId/tdlib"
            })
            apiId = tdapiId
            apiHash = tdapiHash
            systemLanguageCode = context.resources.configuration.locales[0].language
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = getAppVersion(context)
            useSecretChats = false
            useMessageDatabase = true
            useChatInfoDatabase = true
            useFileDatabase = false
            useTestDc = isUseTestDc
            filesDirectory = externalDir.absolutePath
            databaseEncryptionKey = encryptionKeyString?.chunked(2)?.map { it.toInt(16).toByte() }
                ?.toByteArray()
                ?: throw IllegalStateException("Encryption key not found")
        }) { result ->
            println("SetTdlibParameters result: $result")
            if (result is TdApi.Error) {
                isAuthorized = false
                errorSetTdlibParameters = true
                onError("result.message")
                throw IllegalStateException(result.message)
            }
        }

        // 等待授权状态更新
        try {
            authLatch.await()
        } catch (e: InterruptedException) {
            close()
            throw IllegalStateException("Interrupted while waiting for authorization", e)
        }

        if (!isAuthorized) {
            if (errorSetTdlibParameters) {
                onError("SetTdlibParameters error")
                throw IllegalStateException("SetTdlibParameters error")
            } else {
                close()
                onError("Failed to authorize")
                throw IllegalStateException("Failed to authorize")
            }
        }

        client.send(TdApi.GetMe()) {
            if (it is TdApi.User) {
                val user = it
                currentUser = listOf(user.id.toString(), "${user.firstName} ${user.lastName}")
                if (user.id == 7513554495 && user.usernames?.activeUsernames[0] == "tgwear_review_bot") isTestMode()
            }
        }

        // Register network connectivity callback to detect network changes on Wear OS
        registerNetworkCallback()
    }

    /**
     * Registers a NetworkCallback to monitor connectivity changes.
     * When network becomes available, informs TDLib via SetNetworkType
     * so it can reconnect immediately instead of waiting for its own timeout.
     *
     * On Wear OS, the watch can connect to the internet via:
     * - Wi-Fi (direct)
     * - Bluetooth proxy (via connected phone)
     * - LTE (if available)
     * We must check for all of these before reporting no network.
     */
    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("TgApi", "Network available - informing TDLib")
                val networkType = getActiveNetworkType()
                client.send(TdApi.SetNetworkType(networkType)) { result ->
                    Log.d("TgApi", "SetNetworkType (onAvailable) result: $result")
                }
                // Cancel any pending recovery since network is back
                cancelConnectionRecovery()
            }

            override fun onLost(network: Network) {
                // On Wear OS, when Wi-Fi is lost, the watch may still have internet
                // via Bluetooth proxy through the connected phone. Check if there's
                // still an active network before telling TDLib we're offline.
                val remainingNetworkType = getActiveNetworkType()
                if (remainingNetworkType is TdApi.NetworkTypeNone) {
                    Log.d("TgApi", "All networks lost - informing TDLib")
                    client.send(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) { result ->
                        Log.d("TgApi", "SetNetworkType (onLost) result: $result")
                    }
                } else {
                    Log.d("TgApi", "Network lost but another network still active (e.g. Bluetooth proxy)")
                    client.send(TdApi.SetNetworkType(remainingNetworkType)) { result ->
                        Log.d("TgApi", "SetNetworkType (onLost, fallback) result: $result")
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val networkType = getNetworkTypeFromCapabilities(capabilities)
                client.send(TdApi.SetNetworkType(networkType)) { result ->
                    Log.d("TgApi", "SetNetworkType (onCapabilitiesChanged) result: $result")
                }
            }
        }

        try {
            // Use registerDefaultNetworkCallback to track the system's preferred
            // network, which properly handles Wear OS Bluetooth proxy connections
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e("TgApi", "Failed to register network callback: ${e.message}")
        }
    }

    /**
     * Get the current active network type to inform TDLib.
     */
    private fun getActiveNetworkType(): TdApi.NetworkType {
        val activeNetwork = connectivityManager.activeNetwork ?: return TdApi.NetworkTypeNone()
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return TdApi.NetworkTypeNone()
        return getNetworkTypeFromCapabilities(capabilities)
    }

    private fun getNetworkTypeFromCapabilities(capabilities: NetworkCapabilities): TdApi.NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> TdApi.NetworkTypeOther()
            else -> TdApi.NetworkTypeOther()
        }
    }

    /**
     * Schedules a connection recovery attempt after a timeout.
     * If TDLib is stuck in Connecting state for too long, this forces
     * a network type refresh to kick the reconnection.
     */
    internal fun scheduleConnectionRecovery() {
        cancelConnectionRecovery()
        val runnable = Runnable {
            if (isInConnectingState) {
                Log.d("TgApi", "Connection recovery: forcing network type refresh")
                val networkType = getActiveNetworkType()
                // First set to None, then back to actual type, to force TDLib to reconnect
                client.send(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) {
                    client.send(TdApi.SetNetworkType(networkType)) { result ->
                        Log.d("TgApi", "Connection recovery SetNetworkType result: $result")
                    }
                }
                // Schedule another recovery in case this one didn't work
                if (isInConnectingState) {
                    scheduleConnectionRecovery()
                }
            }
        }
        connectionRecoveryRunnable = runnable
        mainHandler.postDelayed(runnable, 30_000) // Retry after 30 seconds
    }

    internal fun cancelConnectionRecovery() {
        connectionRecoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionRecoveryRunnable = null
    }

    fun isTestMode() {
        testMode.value = true
        chatsList.value = chatsList.value.toMutableList().apply {
            add(
                Chat(
                    id = 7513554495,
                    title = "Google_play_review_account"
                )
            )
            add(
                Chat(
                    id = -1002246446050,
                    title = "TGwear"
                )
            )
            add(
                Chat(
                    id = 7162555493,
                    title = "gohj99"
                )
            )
        }
    }

    // 处理 TDLib 更新的函数
    private fun handleUpdate(update: TdApi.Object) {
        when (update.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> handleAuthorizationState(update as TdApi.UpdateAuthorizationState)
            TdApi.UpdateNewMessage.CONSTRUCTOR -> handleNewMessage(update as TdApi.UpdateNewMessage)
            TdApi.UpdateMessageContent.CONSTRUCTOR -> handleMessageContentUpdate(update as TdApi.UpdateMessageContent)
            TdApi.UpdateMessageEdited.CONSTRUCTOR -> handleMessageEdited(update as TdApi.UpdateMessageEdited)
            TdApi.UpdateDeleteMessages.CONSTRUCTOR -> handleDeleteMessages(update as TdApi.UpdateDeleteMessages)
            TdApi.UpdateNewChat.CONSTRUCTOR -> handleNewChat(update as TdApi.UpdateNewChat)
            TdApi.UpdateConnectionState.CONSTRUCTOR -> handleConnectionUpdate(update as TdApi.UpdateConnectionState)
            TdApi.UpdateChatReadInbox.CONSTRUCTOR -> handleChatReadInboxUpdate(update as TdApi.UpdateChatReadInbox)
            TdApi.UpdateChatReadOutbox.CONSTRUCTOR -> handleChatReadOutboxUpdate(update as TdApi.UpdateChatReadOutbox)
            TdApi.UpdateChatFolders.CONSTRUCTOR -> handleChatFoldersUpdate(update as TdApi.UpdateChatFolders)
            TdApi.UpdateChatTitle.CONSTRUCTOR -> handleChatTitleUpdate(update as TdApi.UpdateChatTitle)
            TdApi.UpdateUser.CONSTRUCTOR -> handleUpdateUser(update as TdApi.UpdateUser)
            TdApi.UpdateChatLastMessage.CONSTRUCTOR -> handleChatLastMessageUpdate(update as TdApi.UpdateChatLastMessage)
            TdApi.UpdateChatPosition.CONSTRUCTOR -> handleChatPositionUpdate(update as TdApi.UpdateChatPosition)
            TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> handleMessageSendSucceededUpdate(update as TdApi.UpdateMessageSendSucceeded)
            TdApi.UpdateFile.CONSTRUCTOR -> handleFileUpdate(update as TdApi.UpdateFile)
            TdApi.UpdateChatPhoto.CONSTRUCTOR -> handleChatPhotoUpdate(update as TdApi.UpdateChatPhoto)
            TdApi.UpdateChatNotificationSettings.CONSTRUCTOR -> handleChatNotificationSettingsUpdate(update as TdApi.UpdateChatNotificationSettings)
            TdApi.UpdateChatDraftMessage.CONSTRUCTOR -> handleChatDraftMessageUpdate(update as TdApi.UpdateChatDraftMessage)
            TdApi.UpdateCall.CONSTRUCTOR -> handleCallUpdate(update as TdApi.UpdateCall)
            TdApi.UpdateNewCallSignalingData.CONSTRUCTOR -> handleNewCallSignalingDataUpdate(update as TdApi.UpdateNewCallSignalingData)
            TdApi.UpdateNotificationGroup.CONSTRUCTOR -> handleNotificationGroupUpdate(update as TdApi.UpdateNotificationGroup)
            // 其他更新
            else -> {
                Log.d("TdApiUpdate","Received update: $update")
            }
        }
    }

    // 进入聊天页面
    suspend fun openChatPage(openChatId: Long, chatList: MutableState<List<TdApi.Message>>) {
        chatMutex.withLock {
            //println("进入聊天页面开始执行")
            if (saveChatId != openChatId) {
                client.send(TdApi.OpenChat(openChatId)) { result ->
                    if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                        println("Opened chat page successfully, ChatId: $openChatId")
                    } else {
                        println("Failed to open chat page: $result")
                    }
                }
                if (saveChatId != 0L && saveChatId != -1L){
                    saveChatMessagesList = saveChatMessagesList.toMutableMap().apply {
                        put(saveChatId, ChatMessagesSave(
                            saveChatList.value.toMutableList(),
                            lastReadInboxMessageId.value,
                            lastReadOutboxMessageId.value
                        ))
                    }
                }
                saveChatId = openChatId
                val oldChatList = saveChatMessagesList[openChatId]
                if (oldChatList == null) {
                    saveChatList = chatList
                    lastReadInboxMessageId.value = 0L
                    lastReadOutboxMessageId.value = 0L
                } else {
                    saveChatMessagesList.remove(openChatId)
                    chatList.value = oldChatList.messages.toMutableList()
                    saveChatList = chatList
                    lastReadInboxMessageId.value = oldChatList.lastReadInboxMessageId
                    lastReadOutboxMessageId.value = oldChatList.lastReadOutboxMessageId
                }
                isExitChatPage = false
            }
            //println("进入聊天页面结束执行")
        }
    }

    // 退出聊天页面
    suspend fun exitChatPage(draftMessage: TdApi.DraftMessage? = null, messageThreadId: Long = 0L) {
        chatMutex.withLock {
            //println("退出聊天页面开始执行")
            val closeChatId = saveChatId
            client.send(TdApi.SetChatDraftMessage(closeChatId, messageThreadId, draftMessage)) { result ->
                if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                    println("Set draft message successfully, ChatId: $closeChatId")
                } else {
                    println("Failed to set draft message: $result")
                }
            }
            client.send(TdApi.CloseChat(closeChatId)) { result ->
                if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                    println("Closed chat page successfully, ChatId: $closeChatId")
                } else {
                    println("Failed to close chat page: $result")
                }
            }
            if (closeChatId != 0L && closeChatId != -1L){
                saveChatMessagesList.remove(closeChatId)
            }
            isExitChatPage = true
            saveChatId = 0L
            //println("退出聊天页面结束执行")
        }
    }

    // 获取消息
    fun fetchMessages(fromMessageId: Long = saveChatList.value.lastOrNull()?.id ?: -1L, nowChatId: Long = saveChatId, limit: Int = 10) {
        //println("fetchMessages启动")
        //println(saveChatId)
        if (nowChatId == saveChatId) {
            if (fromMessageId != -1L) {
                val getChatMessages = TdApi.GetChatHistory().apply {
                    this.chatId = nowChatId
                    this.limit = limit // 每次获取 limit 条消息
                    this.fromMessageId = fromMessageId
                }

                if (!isExitChatPage){
                    client.send(getChatMessages) { result ->
                        //println("GetChatMessages result: $result")
                        if (result.constructor == TdApi.Error.CONSTRUCTOR) {
                            val error = result as TdApi.Error
                            println("Get Chat Messages Error: ${error.message}")
                        } else {
                            val messages = result as TdApi.Messages
                            if (messages.messages.isNotEmpty()) {
                                val sortedMessages = messages.messages
                                    .toList()
                                    .sortedByDescending { it.date }
                                    .filterNot { message ->
                                        saveChatList.value.any { it.id == message.id }
                                    }

                                saveChatList.value = saveChatList.value.toMutableList().apply {
                                    if (nowChatId == saveChatId) {
                                        addAll(sortedMessages) // 将新消息添加到列表最后面
                                    } else {
                                        println("Discarded messages: $sortedMessages")
                                    }
                                }
                                // 继续加载更旧的消息
                                if (fromMessageId == 0L) {
                                    fetchMessages(messages.messages.last().id)
                                }
                                //println(messages.messages.last().id)
                            }
                        }
                    }
                }
            }
        }
    }

    // 协程获取消息
    suspend fun fetchMessagesSuspend(fromMessageId: Long = saveChatList.value.lastOrNull()?.id ?: -1L, nowChatId: Long = saveChatId, limit: Int = 10) {
        if (nowChatId == saveChatId) {
            if (fromMessageId != -1L) {
                val getChatMessages = TdApi.GetChatHistory().apply {
                    this.chatId = nowChatId
                    this.limit = limit // 每次获取 limit 条消息
                    this.fromMessageId = fromMessageId
                }

                if (!isExitChatPage){
                    val result = sendRequest(getChatMessages)
                    if (result.constructor == TdApi.Error.CONSTRUCTOR) {
                        val error = result as TdApi.Error
                        println("Get Chat Messages Error: ${error.message}")
                    } else {
                        val messages = result as TdApi.Messages
                        if (messages.messages.isNotEmpty()) {
                            val sortedMessages = messages.messages
                                .toList()
                                .sortedByDescending { it.date }
                                .filterNot { message ->
                                    saveChatList.value.any { it.id == message.id }
                                }

                            saveChatList.value = saveChatList.value.toMutableList().apply {
                                if (nowChatId == saveChatId) {
                                    addAll(sortedMessages) // 将新消息添加到列表最后面
                                } else {
                                    println("Discarded messages: $sortedMessages")
                                }
                            }
                            // 继续加载更旧的消息
                            if (fromMessageId == 0L) {
                                fetchMessages(messages.messages.last().id)
                            }
                        }
                    }
                }
            }
        }
    }

    // 关闭连接
    fun close() {
        println("Closing client")
        // Unregister network callback to prevent leaks
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Exception) {
            Log.e("TgApi", "Failed to unregister network callback: ${e.message}")
        }
        cancelConnectionRecovery()
        client.send(TdApi.Close()) {}
    }
}
