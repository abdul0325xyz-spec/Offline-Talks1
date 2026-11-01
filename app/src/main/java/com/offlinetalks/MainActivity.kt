package com.offlinetalks

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** One chat message. [peer] is the conversation (the other person's display name). */
data class Msg(var peer: String, val mine: Boolean, val text: String, val ts: Long)

class MainActivity : Activity() {
    companion object {
        private val SERVICE_UUID = UUID.fromString("7d2a1000-8b4e-4c1d-9a20-11aabbccdd01")
        private val MESSAGE_UUID = UUID.fromString("7d2a1001-8b4e-4c1d-9a20-11aabbccdd01")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS = "offline_talks_v1"
        private const val HISTORY = "messages_v2"
        private const val MAX_MESSAGE_BYTES = 180
        private const val SEP = '\u0001'
        private const val DEFAULT_NAME = "Offline Talks user"

        private val GREEN = 0xFF07966B.toInt()
        private val GREEN_DARK = 0xFF075B43.toInt()
        private val INK = 0xFF1B2A24.toInt()
        private val MUTED = 0xFF7A8882.toInt()
        private val TAB_OFF = 0xFF8A9690.toInt()
        private val CHAT_BG = 0xFFEFEAE2.toInt()
        private val BUBBLE_ME = 0xFFD9FDD3.toInt()
        private val AVATAR_COLORS = intArrayOf(
            0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFF8E24AA.toInt(),
            0xFF3949AB.toInt(), 0xFFD81B60.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt()
        )
    }

    // ---- Bluetooth state ----
    private lateinit var bluetoothManager: BluetoothManager
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeGatt: BluetoothGatt? = null
    private var clientReady = false
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedName = "Nearby device"
    private var linkAddress: String? = null
    private var linkPeer: String? = null      // conversation key of the current Bluetooth link
    private var linkTemp = false              // true while linkPeer is only a placeholder label
    private val discovered = linkedMapOf<String, BluetoothDevice>()
    private val subscribedDevices = linkedSetOf<BluetoothDevice>()
    private val main = Handler(Looper.getMainLooper())

    // ---- Chat state ----
    private val messages = mutableListOf<Msg>()
    private var currentPeer: String? = null
    private var scanning = false
    private val permissionRequest = 710

    // ---- Views ----
    private lateinit var mainLayout: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var pages: List<View>
    private lateinit var bottomBar: LinearLayout
    private val tabIcons = mutableListOf<TextView>()
    private val tabLabels = mutableListOf<TextView>()

    private lateinit var chatsList: LinearLayout
    private lateinit var chatsEmpty: TextView

    private lateinit var scanButton: Button
    private lateinit var deviceList: LinearLayout

    private lateinit var nameInput: EditText
    private lateinit var profileAvatar: TextView

    private lateinit var chatScreen: LinearLayout
    private lateinit var chatHeader: LinearLayout
    private lateinit var chatAvatarSlot: FrameLayout
    private lateinit var chatTitle: TextView
    private lateinit var chatSub: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatMessages: LinearLayout
    private lateinit var composerWrap: LinearLayout
    private lateinit var messageInput: EditText

    private fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun permissionsGranted() =
        permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadHistory()
        buildInterface()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            statusText.text = "This phone does not support Bluetooth Low Energy."
            scanButton.isEnabled = false
            return
        }
        if (!permissionsGranted()) requestPermissions(permissions(), permissionRequest)
        else initializeBluetooth()
    }

    // =====================================================================
    //  UI
    // =====================================================================

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun pressable(color: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0xFFE6EDEA.toInt()))
        addState(intArrayOf(), ColorDrawable(color))
    }

    private fun avatar(name: String, sizeDp: Int) = TextView(this).apply {
        text = name.trim().firstOrNull()?.uppercase() ?: "?"
        gravity = Gravity.CENTER
        textSize = sizeDp * 0.4f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setWidth(dp(sizeDp))
        setHeight(dp(sizeDp))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size])
        }
    }

    private fun timeLabel(ts: Long): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val d = Date(ts)
        return if (day.format(d) == day.format(Date()))
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        else SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(d)
    }

    private fun buildInterface() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GREEN_DARK)
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        topBar.addView(TextView(this).apply {
            text = "Offline Talks"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        statusText = TextView(this).apply {
            text = "Starting Bluetooth…"
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xCCFFFFFF.toInt())
        }
        topBar.addView(statusText)
        mainLayout.addView(topBar)

        val host = FrameLayout(this)
        pages = listOf(buildChatsPage(), buildNearbyPage(), buildProfilePage())
        pages.forEach { host.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        mainLayout.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))

        bottomBar = buildBottomBar()
        mainLayout.addView(bottomBar)
        root.addView(mainLayout, FrameLayout.LayoutParams(-1, -1))

        chatScreen = buildChatScreen()
        chatScreen.visibility = View.GONE
        root.addView(chatScreen, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        selectTab(0)
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(insets: WindowInsets) {
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            top = i.top
            bottom = i.bottom
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
        }
        topBar.setPadding(dp(16), top + dp(12), dp(16), dp(12))
        chatHeader.setPadding(dp(4), top + dp(8), dp(12), dp(8))
        bottomBar.setPadding(0, 0, 0, bottom)
        composerWrap.setPadding(dp(8), dp(6), dp(8), bottom + dp(6))
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        bar.addView(View(this).apply { setBackgroundColor(0xFFE3E8E5.toInt()) }, LinearLayout.LayoutParams(-1, 1))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf("💬" to "Chats", "📡" to "Nearby", "👤" to "Profile")
        defs.forEachIndexed { i, def ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { selectTab(i) }
            }
            val icon = TextView(this).apply { text = def.first; textSize = 20f; gravity = Gravity.CENTER }
            val label = TextView(this).apply { text = def.second; textSize = 11f; gravity = Gravity.CENTER }
            cell.addView(icon)
            cell.addView(label)
            tabIcons.add(icon)
            tabLabels.add(label)
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bar.addView(row)
        return bar
    }

    private fun selectTab(index: Int) {
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabLabels.forEachIndexed { i, l ->
            l.setTextColor(if (i == index) GREEN else TAB_OFF)
            l.typeface = if (i == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tabIcons[i].alpha = if (i == index) 1f else 0.5f
        }
        if (index == 0) refreshChats()
    }

    // ---- Chats tab ----

    private fun buildChatsPage(): View {
        val page = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        chatsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(chatsList) }, FrameLayout.LayoutParams(-1, -1))
        chatsEmpty = TextView(this).apply {
            text = "No chats yet\n\nOpen the Nearby tab to find people around you."
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(MUTED)
            setPadding(dp(40), 0, dp(40), 0)
        }
        page.addView(chatsEmpty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        return page
    }

    private fun refreshChats() {
        if (!::chatsList.isInitialized) return
        chatsList.removeAllViews()
        val last = messages.groupBy { it.peer }
            .map { it.key to it.value.last() }
            .sortedByDescending { it.second.ts }
        chatsEmpty.visibility = if (last.isEmpty()) View.VISIBLE else View.GONE
        last.forEach { (peer, msg) ->
            chatsList.addView(chatRow(peer, msg))
            chatsList.addView(
                View(this).apply { setBackgroundColor(0xFFEEF2F0.toInt()) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(82) }
            )
        }
    }

    private fun chatRow(peer: String, last: Msg): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = pressable(Color.WHITE)
            setOnClickListener { openChat(peer) }
        }
        row.addView(avatar(peer, 52))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        col.addView(TextView(this).apply {
            text = peer
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = (if (last.mine) "You: " else "") + last.text
            textSize = 14f
            setTextColor(MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = timeLabel(last.ts)
            textSize = 12f
            setTextColor(MUTED)
        })
        return row
    }

    // ---- Nearby tab ----

    private fun buildNearbyPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F7F5.toInt())
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        page.addView(TextView(this).apply {
            text = "Find people close to you who have Offline Talks open. No internet needed."
            textSize = 14f
            setTextColor(0xFF5E6E66.toInt())
        })
        scanButton = Button(this).apply {
            text = "Find nearby users"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = bg(GREEN, 24)
            setOnClickListener { scanForPeers() }
        }
        page.addView(scanButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(14)
            bottomMargin = dp(10)
        })
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(deviceList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setNearbyHint("Tap “Find nearby users” to look for phones running Offline Talks.")
        return page
    }

    private fun setNearbyHint(text: String) {
        deviceList.removeAllViews()
        deviceList.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(dp(16), dp(40), dp(16), dp(16))
        })
    }

    private fun deviceRow(device: BluetoothDevice, label: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(Color.WHITE, 14)
            setOnClickListener { connectToPeer(device, label) }
        }
        row.addView(avatar(label, 44))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        col.addView(TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = "Tap to connect and chat"
            textSize = 12f
            setTextColor(MUTED)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(GREEN)
        })
        val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        row.layoutParams = lp
        return row
    }

    // ---- Profile tab ----

    private fun myName(): String {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("display_name", DEFAULT_NAME) ?: DEFAULT_NAME
        return raw.replace(SEP.toString(), "").trim().take(16).ifBlank { DEFAULT_NAME.take(16) }
    }

    private fun buildProfilePage(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF4F7F5.toInt()) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        profileAvatar = avatar(myName(), 96)
        col.addView(profileAvatar)

        col.addView(TextView(this).apply {
            text = "YOUR NAME"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
            setPadding(0, dp(24), 0, dp(6))
        }, LinearLayout.LayoutParams(-1, -2))

        nameInput = EditText(this).apply {
            setText(myName())
            setSingleLine(true)
            textSize = 16f
            background = bg(Color.WHITE, 12, 0xFFD5DDD9.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(nameInput, LinearLayout.LayoutParams(-1, -2))

        val save = Button(this).apply {
            text = "Save name"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = bg(GREEN, 24)
            setOnClickListener { saveName() }
        }
        col.addView(save, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })

        col.addView(TextView(this).apply {
            text = "This name is shown to people you chat with (up to 16 characters).\n\n" +
                "Offline Talks works over Bluetooth, so both phones must be close and have the app open. " +
                "Messages are short (about 180 bytes) and are not encrypted yet — don't send private information."
            textSize = 13f
            setTextColor(0xFF5E6E66.toInt())
            background = bg(Color.WHITE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        scroll.addView(col)
        return scroll
    }

    private fun saveName() {
        val name = nameInput.text.toString().replace(SEP.toString(), "").trim().take(16)
            .ifBlank { DEFAULT_NAME.take(16) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("display_name", name).apply()
        nameInput.setText(name)
        val fresh = avatar(name, 96)
        profileAvatar.text = fresh.text
        profileAvatar.background = fresh.background
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(nameInput.windowToken, 0)
        Toast.makeText(this, "Name saved", Toast.LENGTH_SHORT).show()
    }

    // ---- Chat screen ----

    private fun buildChatScreen(): LinearLayout {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CHAT_BG)
        }
        chatHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(GREEN_DARK)
            elevation = dp(4).toFloat()
        }
        chatHeader.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { closeChat() }
        })
        chatAvatarSlot = FrameLayout(this)
        chatHeader.addView(chatAvatarSlot)
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        chatTitle = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        chatSub = TextView(this).apply {
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titles.addView(chatTitle)
        titles.addView(chatSub)
        chatHeader.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        screen.addView(chatHeader)

        chatMessages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        chatScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(chatMessages)
        }
        screen.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        composerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        messageInput = EditText(this).apply {
            hint = "Message"
            textSize = 16f
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = bg(Color.WHITE, 24)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val send = TextView(this).apply {
            text = "➤"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setWidth(dp(48))
            setHeight(dp(48))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GREEN)
            }
            setOnClickListener { sendMessage() }
        }
        composerWrap.addView(messageInput, LinearLayout.LayoutParams(0, -2, 1f))
        composerWrap.addView(send, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        screen.addView(composerWrap)
        return screen
    }

    private fun openChat(peer: String) {
        currentPeer = peer
        chatTitle.text = peer
        setChatAvatar(peer)
        updateChatSub()
        renderChat()
        mainLayout.visibility = View.GONE
        chatScreen.visibility = View.VISIBLE
    }

    private fun closeChat() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(messageInput.windowToken, 0)
        currentPeer = null
        chatScreen.visibility = View.GONE
        mainLayout.visibility = View.VISIBLE
        refreshChats()
    }

    private fun setChatAvatar(peer: String) {
        chatAvatarSlot.removeAllViews()
        chatAvatarSlot.addView(avatar(peer, 40))
    }

    private fun isLinked() = (activeGatt != null && clientReady) || subscribedDevices.isNotEmpty()

    private fun updateChatSub() {
        if (!::chatSub.isInitialized) return
        chatSub.text = if (isLinked()) "connected via Bluetooth" else "not connected • use Nearby to reconnect"
    }

    private fun renderChat() {
        if (!::chatMessages.isInitialized) return
        chatMessages.removeAllViews()
        val peer = currentPeer ?: return
        val list = messages.filter { it.peer == peer }
        if (list.isEmpty()) {
            chatMessages.addView(TextView(this).apply {
                text = "Say hi 👋 — messages travel over Bluetooth."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                background = bg(0xCCFFF6D6.toInt(), 10)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            })
        }
        list.forEach { chatMessages.addView(bubble(it)) }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(m: Msg): View {
        val row = LinearLayout(this).apply {
            gravity = if (m.mine) Gravity.END else Gravity.START
            setPadding(if (m.mine) dp(48) else 0, dp(2), if (m.mine) 0 else dp(48), dp(2))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(if (m.mine) BUBBLE_ME else Color.WHITE, 14)
            setPadding(dp(12), dp(7), dp(12), dp(5))
        }
        box.addView(TextView(this).apply {
            text = m.text
            textSize = 15.5f
            setTextColor(INK)
        })
        box.addView(TextView(this).apply {
            text = timeLabel(m.ts) + if (m.mine) "  ✓" else ""
            textSize = 11f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        row.addView(box)
        return row
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (chatScreen.visibility == View.VISIBLE) closeChat()
        else if (pages[0].visibility != View.VISIBLE) selectTab(0)
        else super.onBackPressed()
    }

    // =====================================================================
    //  Bluetooth
    // =====================================================================

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && permissionsGranted()) initializeBluetooth()
        else statusText.text = "Bluetooth permissions are needed for nearby messaging."
    }

    private fun initializeBluetooth() {
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Please turn on Bluetooth and reopen Offline Talks."
            return
        }
        scanner = adapter.bluetoothLeScanner
        advertiser = adapter.bluetoothLeAdvertiser
        startGattServer()
        startAdvertising()
        statusText.text = "● Bluetooth ready • visible to nearby phones"
    }

    private fun startGattServer() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        messageCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        messageCharacteristic!!.addDescriptor(
            BluetoothGattDescriptor(CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )
        service.addCharacteristic(messageCharacteristic)
        gattServer = bluetoothManager.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                runOnUiThread {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        statusText.text = "● A nearby phone connected"
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        subscribedDevices.remove(device)
                        statusText.text = "A nearby phone disconnected"
                        if (!isLinked()) {
                            linkPeer = null
                            linkTemp = false
                        }
                        updateChatSub()
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (characteristic.uuid == MESSAGE_UUID) {
                    runOnUiThread { onIncoming(value) }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        subscribedDevices.add(device)
                    } else {
                        subscribedDevices.remove(device)
                    }
                    descriptor.value = value
                    runOnUiThread { updateChatSub() }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        })
        gattServer?.addService(service)
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallbackForStop)
    }

    private fun scanForPeers() {
        if (!permissionsGranted()) {
            requestPermissions(permissions(), permissionRequest)
            return
        }
        val bleScanner = scanner ?: run {
            statusText.text = "Bluetooth scanner is unavailable. Check Bluetooth is on."
            return
        }
        discovered.clear()
        setNearbyHint("Searching nearby…")
        statusText.text = "Searching nearby…"
        scanning = true
        scanButton.isEnabled = false
        scanButton.alpha = 0.6f
        scanButton.text = "Searching…"
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bleScanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            statusText.text = "Bluetooth permission was not granted."
            finishScan()
            return
        }
        main.postDelayed({
            if (scanning) {
                try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
                finishScan()
            }
        }, 15000)
    }

    private fun finishScan() {
        scanning = false
        scanButton.isEnabled = true
        scanButton.alpha = 1f
        scanButton.text = "Find nearby users"
        statusText.text = "Search complete • ${discovered.size} device(s) found"
        if (discovered.isEmpty()) {
            setNearbyHint("No one found. Make sure the other phone has Offline Talks open and Bluetooth on.")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return
            if (discovered.containsKey(address)) return
            discovered[address] = device
            runOnUiThread {
                val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("peer_$address", null)
                val devName: String? = try { device.name } catch (_: SecurityException) { null }
                val label = saved ?: devName ?: "Offline Talks • ${address.takeLast(5)}"
                if (discovered.size == 1) deviceList.removeAllViews()
                deviceList.addView(deviceRow(device, label))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                statusText.text = "Scan failed (code $errorCode)."
                finishScan()
            }
        }
    }

    private fun connectToPeer(device: BluetoothDevice, label: String) {
        try {
            if (scanning) {
                scanner?.stopScan(scanCallback)
                finishScan()
            }
            activeGatt?.disconnect()
            activeGatt?.close()
            clientReady = false
            connectedName = label
            linkAddress = device.address
            linkPeer = label
            linkTemp = getSharedPreferences(PREFS, MODE_PRIVATE).getString("peer_${device.address}", null) == null
            statusText.text = "Connecting to $label…"
            activeGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            statusText.text = "Bluetooth connection permission is missing."
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Ask for a bigger MTU first; services are discovered in onMtuChanged.
                val requested = try { gatt.requestMtu(247) } catch (_: Exception) { false }
                if (!requested) {
                    try { gatt.discoverServices() } catch (_: Exception) {}
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    statusText.text = "Disconnected from $connectedName"
                    if (activeGatt === gatt) {
                        activeGatt = null
                        clientReady = false
                        if (subscribedDevices.isEmpty()) {
                            linkPeer = null
                            linkTemp = false
                        }
                    }
                    updateChatSub()
                }
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            try { gatt.discoverServices() } catch (_: Exception) {}
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_UUID)
            if (characteristic == null) {
                runOnUiThread { statusText.text = "This device does not expose the Offline Talks chat service." }
                return
            }
            try {
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Connected, but notifications could not be enabled." }
            }
            runOnUiThread {
                clientReady = true
                statusText.text = "● Connected to $connectedName"
                selectTab(0)
                openChat(linkPeer ?: connectedName)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == MESSAGE_UUID) {
                runOnUiThread { onIncoming(value) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == MESSAGE_UUID) {
                val raw = characteristic.value ?: return
                runOnUiThread { onIncoming(raw) }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) statusText.text = "● Message delivered"
                else statusText.text = "Message write failed (code $status)"
            }
        }
    }

    // =====================================================================
    //  Messaging
    // =====================================================================

    /** Payload format: "<sender name>\u0001<text>". Plain text without the separator is also accepted. */
    private fun onIncoming(bytes: ByteArray) {
        val raw = String(bytes, StandardCharsets.UTF_8)
        val idx = raw.indexOf(SEP)
        val hasName = idx > 0
        val text = if (idx >= 0) raw.substring(idx + 1) else raw
        val peer = if (hasName) raw.substring(0, idx) else (linkPeer ?: "Nearby phone")

        val old = linkPeer
        if (hasName && linkTemp && old != null && old != peer) {
            // The placeholder label is now known by its real name: merge the history.
            messages.filter { it.peer == old }.forEach { it.peer = peer }
            if (currentPeer == old) {
                currentPeer = peer
                chatTitle.text = peer
                setChatAvatar(peer)
            }
            linkAddress?.let {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("peer_$it", peer).apply()
            }
        }
        if (hasName) linkTemp = false
        linkPeer = peer
        connectedName = peer
        addMessage(peer, false, text)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        val peer = currentPeer ?: return
        val name = myName()
        val limit = MAX_MESSAGE_BYTES - name.toByteArray(StandardCharsets.UTF_8).size - 1
        if (text.toByteArray(StandardCharsets.UTF_8).size > limit) {
            Toast.makeText(this, "Message too long for this beta (max about $limit bytes).", Toast.LENGTH_LONG).show()
            return
        }
        val payload = (name + SEP + text).toByteArray(StandardCharsets.UTF_8)
        if (!transmit(payload)) return
        addMessage(peer, true, text)
        messageInput.text.clear()
    }

    /** Sends over our GATT client link if we have one, otherwise notifies phones that connected to us. */
    private fun transmit(payload: ByteArray): Boolean {
        try {
            val gatt = activeGatt
            val characteristic = gatt?.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_UUID)
            if (gatt != null && characteristic != null && clientReady) {
                if (writeToPeer(gatt, characteristic, payload)) return true
                Toast.makeText(this, "Could not send message.", Toast.LENGTH_SHORT).show()
                return false
            }
            if (subscribedDevices.isNotEmpty() && notifyPeers(payload)) return true
        } catch (e: SecurityException) {
            statusText.text = "Bluetooth permission is missing."
            return false
        }
        Toast.makeText(this, "Not connected. Open Nearby and connect first.", Toast.LENGTH_SHORT).show()
        return false
    }

    @Suppress("DEPRECATION")
    private fun writeToPeer(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            ch.value = payload
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyPeers(payload: ByteArray): Boolean {
        val server = gattServer ?: return false
        val ch = messageCharacteristic ?: return false
        var ok = false
        for (device in subscribedDevices.toList()) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (server.notifyCharacteristicChanged(device, ch, false, payload) == BluetoothStatusCodes.SUCCESS) ok = true
            } else {
                ch.value = payload
                if (server.notifyCharacteristicChanged(device, ch, false)) ok = true
            }
        }
        return ok
    }

    private fun addMessage(peer: String, mine: Boolean, text: String) {
        messages.add(Msg(peer, mine, text, System.currentTimeMillis()))
        if (messages.size > 300) messages.removeAt(0)
        saveHistory()
        if (currentPeer == peer) renderChat()
        else if (!mine) statusText.text = "New message from $peer"
        refreshChats()
    }

    private fun loadHistory() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(HISTORY, "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                messages.add(Msg(o.getString("p"), o.getBoolean("m"), o.getString("t"), o.getLong("ts")))
            }
        } catch (_: Exception) {}
    }

    private fun saveHistory() {
        val array = JSONArray()
        messages.forEach {
            array.put(JSONObject().put("p", it.peer).put("m", it.mine).put("t", it.text).put("ts", it.ts))
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(HISTORY, array.toString()).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (scanning) scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { advertiser?.stopAdvertising(advertiseCallbackForStop) } catch (_: Exception) {}
        try { activeGatt?.disconnect(); activeGatt?.close() } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
    }

    private val advertiseCallbackForStop = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            runOnUiThread {
                if (::statusText.isInitialized) {
                    statusText.text = "Could not start Bluetooth advertising (error $errorCode)."
                }
            }
        }
    }
}
