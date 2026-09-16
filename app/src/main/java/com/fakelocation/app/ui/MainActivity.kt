package com.fakelocation.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fakelocation.app.FakeLocationApp
import com.fakelocation.app.R
import com.fakelocation.app.bridge.LocationHttpServer
import com.fakelocation.app.databinding.ActivityMainBinding
import com.fakelocation.app.geo.CoordTransform
import com.fakelocation.app.location.MyLocationResolver
import com.fakelocation.app.map.AppTileSources
import com.fakelocation.app.map.FreehandDrawOverlay
import com.fakelocation.app.map.TileConnectivity
import com.fakelocation.app.mock.MockLocationService
import com.fakelocation.app.mock.MockLocationWriter
import com.fakelocation.app.model.GeoPoint
import com.fakelocation.app.route.GeoMath
import com.fakelocation.app.route.OsrmRoutePlanner
import com.fakelocation.app.safety.SafetyPolicy
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val planner = OsrmRoutePlanner()

    /** Auto-mode input points (GCJ-02 on Gaode map). */
    private val waypoints = mutableListOf<GeoPoint>()

    /** Route used for simulation (GCJ-02). */
    private var routePoints: List<GeoPoint> = emptyList()
    private var autoRouteReady = false

    private val waypointMarkers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var cursorMarker: Marker? = null
    private var myLocationMarker: Marker? = null

    private var autoMode = false
    private var receiverRegistered = false
    private var wasRunningWhenPaused = false
    /** Prefer converting map GCJ-02 → WGS-84 when writing to OS. */
    private var convertGcjToWgs = true
    private var locating = false

    private lateinit var drawOverlay: FreehandDrawOverlay
    private val locationResolver by lazy { MyLocationResolver(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            locateMe(showToast = false)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MockLocationService.ACTION_STATUS -> {
                    val running = intent.getBooleanExtra(MockLocationService.EXTRA_RUNNING, false)
                    val message = intent.getStringExtra(MockLocationService.EXTRA_MESSAGE)
                        ?: getString(R.string.status_idle)
                    binding.statusText.text = message
                    binding.btnToggle.text =
                        if (running) getString(R.string.stop_mock) else getString(R.string.start_mock)
                    if (!running && message.contains("自动停止")) {
                        showPostStopSafetyReminder()
                    }
                    refreshDiagHeader()
                }
                MockLocationService.ACTION_POSITION -> {
                    val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LAT, 0.0)
                    val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LNG, 0.0)
                    updateCursor(GeoPoint(lat, lng), follow = true)
                }
                MockLocationService.ACTION_DIAG -> {
                    val wgsLat = intent.getDoubleExtra(MockLocationService.EXTRA_WGS_LAT, Double.NaN)
                    val wgsLng = intent.getDoubleExtra(MockLocationService.EXTRA_WGS_LNG, Double.NaN)
                    val readLat = intent.getDoubleExtra(MockLocationService.EXTRA_READ_LAT, Double.NaN)
                    val readLng = intent.getDoubleExtra(MockLocationService.EXTRA_READ_LNG, Double.NaN)
                    val mockOk = isMockLocationAppSelected()
                    val readText = if (readLat.isNaN()) {
                        "系统回读: 无"
                    } else {
                        "系统回读: %.6f, %.6f".format(readLat, readLng)
                    }
                    binding.diagText.text = buildString {
                        append(if (mockOk) "模拟应用: 已授权" else "模拟应用: 未指定⚠")
                        append(" | 写入WGS: ")
                        append("%.5f,%.5f".format(wgsLat, wgsLng))
                        append('\n')
                        append(readText)
                        append(if (convertGcjToWgs) " | 地图GCJ→WGS" else " | 原样写入")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        convertGcjToWgs = getSharedPreferences("safety_prefs", MODE_PRIVATE)
            .getBoolean("convert_gcj", true)

        setupMap()
        setupControls()
        ensurePermissions()
        maybeShowConsent()
        refreshDiagHeader()
        refreshBridgeUrl()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        registerStatusReceiver()
        refreshDiagHeader()
        refreshBridgeUrl()
        if (MockLocationService.isRunning) {
            binding.btnToggle.text = getString(R.string.stop_mock)
            MockLocationService.latestPoint?.let { updateCursor(it, follow = false) }
        } else if (wasRunningWhenPaused) {
            wasRunningWhenPaused = false
            showPostStopSafetyReminder()
        } else if (hasLocationPermission()) {
            locateMe(showToast = false)
        }
    }

    override fun onPause() {
        wasRunningWhenPaused = MockLocationService.isRunning
        unregisterStatusReceiver()
        binding.map.onPause()
        super.onPause()
    }

    private fun registerStatusReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(MockLocationService.ACTION_STATUS)
            addAction(MockLocationService.ACTION_POSITION)
            addAction(MockLocationService.ACTION_DIAG)
        }
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterStatusReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Throwable) {
        }
        receiverRegistered = false
    }

    private fun setupMap() {
        // One-time purge of tiles cached under broken UA/URL config.
        val mapPrefs = getSharedPreferences("map_prefs", MODE_PRIVATE)
        if (!mapPrefs.getBoolean("tiles_purged_v2", false)) {
            try {
                File(cacheDir, "osmdroid/tiles").deleteRecursively()
            } catch (_: Throwable) {
            }
            mapPrefs.edit().putBoolean("tiles_purged_v2", true).apply()
        }

        binding.map.setTileSource(AppTileSources.default())
        binding.map.setMultiTouchControls(true)
        binding.map.setUseDataConnection(true)
        binding.map.isTilesScaledToDpi = true
        binding.map.minZoomLevel = 3.0
        binding.map.maxZoomLevel = 20.0
        binding.map.overlayManager.tilesOverlay.setLoadingBackgroundColor(Color.parseColor("#C8D5CE"))
        binding.map.overlayManager.tilesOverlay.setLoadingLineColor(Color.parseColor("#0B6E4F"))
        binding.map.controller.setZoom(16.0)
        binding.map.controller.setCenter(OsmGeoPoint(30.6570, 104.0650))
        binding.map.invalidate()

        drawOverlay = FreehandDrawOverlay(this, onRouteFinished = { points ->
            applyDrawnRoute(points)
            setDrawing(false)
            toast(getString(R.string.draw_done, points.size))
        })
        binding.map.overlays.add(drawOverlay)

        val tapReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
                if (p == null || MockLocationService.isRunning || drawOverlay.drawingEnabled) return false
                if (!autoMode) return false
                addWaypoint(GeoPoint(p.latitude, p.longitude))
                return true
            }

            override fun longPressHelper(p: OsmGeoPoint?): Boolean {
                if (p == null || MockLocationService.isRunning || drawOverlay.drawingEnabled) return false
                confirmJumpTo(GeoPoint(p.latitude, p.longitude))
                return true
            }
        }
        binding.map.overlays.add(MapEventsOverlay(tapReceiver))

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_tiles_gaode -> {
                    switchTiles(AppTileSources.GAODE_VECTOR, "高德矢量")
                    true
                }
                R.id.action_tiles_gaode_sat -> {
                    switchTiles(AppTileSources.GAODE_SATELLITE, "高德卫星")
                    true
                }
                R.id.action_tiles_geoq -> {
                    switchTiles(AppTileSources.GEOQ, "GeoQ")
                    true
                }
                R.id.action_retry_map -> {
                    probeAndApplyTiles()
                    true
                }
                R.id.action_coord_mode -> {
                    convertGcjToWgs = !convertGcjToWgs
                    getSharedPreferences("safety_prefs", MODE_PRIVATE)
                        .edit().putBoolean("convert_gcj", convertGcjToWgs).apply()
                    toast(
                        if (convertGcjToWgs) R.string.coord_mode_convert
                        else R.string.coord_mode_raw
                    )
                    refreshDiagHeader()
                    true
                }
                R.id.action_wechat_help -> {
                    showWeChatHelp()
                    true
                }
                else -> false
            }
        }

        probeAndApplyTiles()
    }

    private fun switchTiles(source: org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase, label: String) {
        binding.map.setTileSource(source)
        binding.map.invalidate()
        binding.mapStatus.visibility = View.VISIBLE
        binding.mapStatus.text = "已切换：$label（若仍灰屏请点菜单「重试加载地图」）"
    }

    private fun probeAndApplyTiles() {
        binding.mapStatus.visibility = View.VISIBLE
        binding.mapStatus.text = getString(R.string.map_loading)
        lifecycleScope.launch {
            val result = TileConnectivity.probe()
            if (result.ok) {
                val source = when (result.source) {
                    "GeoQ" -> AppTileSources.GEOQ
                    else -> AppTileSources.GAODE_VECTOR
                }
                binding.map.setTileSource(source)
                binding.map.setUseDataConnection(true)
                binding.map.invalidate()
                binding.mapStatus.text = getString(R.string.map_ok, result.source, result.detail)
                binding.mapStatus.postDelayed({
                    if (binding.mapStatus.text.toString().startsWith("地图源")) {
                        binding.mapStatus.visibility = View.GONE
                    }
                }, 4000)
            } else {
                binding.mapStatus.text = getString(R.string.map_fail, result.detail)
            }
        }
    }

    private fun setupControls() {
        binding.speedSlider.valueFrom = 0.5f
        binding.speedSlider.valueTo = SafetyPolicy.MAX_SPEED_MPS
        binding.speedSlider.value = SafetyPolicy.DEFAULT_SPEED_MPS
        binding.speedValue.text = String.format("%.1f", SafetyPolicy.DEFAULT_SPEED_MPS)

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            autoMode = checkedId == R.id.btnModeAuto
            setDrawing(false)
            autoRouteReady = false
            routePoints = emptyList()
            drawRouteLine(emptyList())
            binding.btnPlan.visibility = if (autoMode) View.VISIBLE else View.GONE
            binding.btnDrawToggle.visibility = if (autoMode) View.GONE else View.VISIBLE
            binding.btnPlan.isEnabled = autoMode && !MockLocationService.isRunning
            binding.hintText.text = if (autoMode) {
                getString(R.string.hint_auto)
            } else {
                getString(R.string.hint_draw)
            }
        }

        binding.btnDrawToggle.setOnClickListener {
            if (MockLocationService.isRunning) {
                toast(R.string.stop_before_draw)
                return@setOnClickListener
            }
            setDrawing(!drawOverlay.drawingEnabled)
        }

        binding.btnLocate.setOnClickListener { locateMe(showToast = true) }

        binding.btnCopyBridge.setOnClickListener {
            val url = currentBridgeUrl()
            if (url == null) {
                toast(R.string.bridge_no_ip)
                refreshBridgeUrl()
                return@setOnClickListener
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bridge", url))
            toast(getString(R.string.bridge_copied, url))
        }

        binding.speedSlider.addOnChangeListener { _, value, _ ->
            binding.speedValue.text = String.format("%.1f", value)
            if (MockLocationService.isRunning) {
                startService(
                    Intent(this, MockLocationService::class.java)
                        .setAction(MockLocationService.ACTION_UPDATE_SPEED)
                        .putExtra(MockLocationService.EXTRA_SPEED, value.toDouble())
                )
            }
        }

        binding.btnClear.setOnClickListener {
            setDrawing(false)
            clearRoute(stopIfRunning = true)
        }

        binding.btnPlan.setOnClickListener {
            if (waypoints.size < 2) {
                toast(R.string.route_too_short)
                return@setOnClickListener
            }
            if (!validateWaypointJumps(waypoints)) return@setOnClickListener
            lifecycleScope.launch {
                try {
                    binding.btnPlan.isEnabled = false
                    // OSRM expects WGS-84.
                    val wgsWaypoints = waypoints.map { CoordTransform.gcj02ToWgs84(it) }
                    val plannedWgs = planner.planWalking(wgsWaypoints)
                    val plannedGcj = plannedWgs.map { CoordTransform.wgs84ToGcj02(it) }
                    routePoints = plannedGcj
                    autoRouteReady = true
                    drawRouteLine(plannedGcj)
                    toast(R.string.plan_ok)
                } catch (t: Throwable) {
                    autoRouteReady = false
                    toast(getString(R.string.plan_failed) + "\n${t.message ?: ""}")
                } finally {
                    binding.btnPlan.isEnabled = autoMode && !MockLocationService.isRunning
                }
            }
        }

        binding.btnToggle.setOnClickListener {
            if (MockLocationService.isRunning) {
                stopMock(showReminder = true)
            } else {
                requestStartMock()
            }
        }
    }

    private fun setDrawing(enabled: Boolean) {
        drawOverlay.drawingEnabled = enabled
        binding.map.setMultiTouchControls(!enabled)
        binding.btnDrawToggle.text =
            if (enabled) getString(R.string.stop_draw) else getString(R.string.start_draw)
        if (enabled) {
            binding.hintText.text = getString(R.string.hint_drawing)
        } else if (!autoMode) {
            binding.hintText.text = getString(R.string.hint_draw)
        }
    }

    private fun applyDrawnRoute(points: List<GeoPoint>) {
        waypoints.clear()
        waypointMarkers.forEach { binding.map.overlays.remove(it) }
        waypointMarkers.clear()
        routePoints = points
        autoRouteReady = false
        drawRouteLine(points)
        binding.statusText.text = getString(R.string.draw_done, points.size)
    }

    private fun refreshDiagHeader() {
        val mockOk = isMockLocationAppSelected()
        val last = MockLocationWriter.lastWrittenWgs
        binding.diagText.text = buildString {
            append(if (mockOk) "模拟应用: 已授权 ✓" else "模拟应用: 未指定 ⚠")
            append('\n')
            if (last != null) {
                append("系统写入WGS: %.6f, %.6f".format(last.latitude, last.longitude))
            } else {
                append(getString(R.string.diag_idle))
            }
            append(if (convertGcjToWgs) " | GCJ→WGS" else " | 原样写入")
            append('\n')
            append("微信小程序请用下方「桥接地址」，勿依赖系统 Mock")
        }
    }

    private fun refreshBridgeUrl() {
        val ip = LocationHttpServer.findLanIpv4()
        val alive = try {
            FakeLocationApp.instance.locationServer.isAlive
        } catch (_: Throwable) {
            false
        }
        if (ip == null) {
            binding.bridgeText.text =
                getString(R.string.bridge_hint) + "\n\n" + getString(R.string.bridge_no_ip)
        } else {
            val url = "http://$ip:${LocationHttpServer.DEFAULT_PORT}/location"
            binding.bridgeText.text = buildString {
                append(getString(R.string.bridge_hint))
                append("\n\n")
                append(url)
                append(if (alive) "\n服务: 运行中" else "\n服务: 未启动")
            }
        }
    }

    private fun currentBridgeUrl(): String? {
        val ip = LocationHttpServer.findLanIpv4() ?: return null
        return "http://$ip:${LocationHttpServer.DEFAULT_PORT}/location"
    }

    @SuppressLint("MissingPermission")
    private fun locateMe(showToast: Boolean) {
        if (!hasLocationPermission()) {
            if (showToast) toast(R.string.need_location_permission)
            ensurePermissions()
            return
        }
        if (locating) {
            if (showToast) toast(R.string.locating)
            return
        }
        if (MockLocationService.isRunning) {
            AlertDialog.Builder(this)
                .setTitle(R.string.locate_blocked_title)
                .setMessage(R.string.locate_blocked_message)
                .setPositiveButton(R.string.stop_mock) { _, _ ->
                    stopMock(showReminder = false)
                    binding.root.postDelayed({ locateMe(showToast = true) }, 600)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        locating = true
        binding.btnLocate.isEnabled = false
        binding.mapStatus.visibility = View.VISIBLE
        binding.mapStatus.text = getString(R.string.locating)
        if (showToast) toast(R.string.locating)

        locationResolver.request(
            timeoutMs = 20_000L,
            onSuccess = { mapPoint, raw ->
                locating = false
                binding.btnLocate.isEnabled = true
                val osm = OsmGeoPoint(mapPoint.latitude, mapPoint.longitude)
                val marker = myLocationMarker ?: Marker(binding.map).apply {
                    title = "我的位置"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    myLocationMarker = this
                    binding.map.overlays.add(this)
                }
                marker.position = osm
                binding.map.controller.setZoom(18.0)
                binding.map.controller.animateTo(osm)
                binding.map.invalidate()
                binding.mapStatus.text = getString(
                    R.string.located_ok,
                    mapPoint.latitude,
                    mapPoint.longitude,
                    raw.accuracy
                )
                binding.mapStatus.postDelayed({
                    if (binding.mapStatus.text.toString().startsWith("已定位")) {
                        binding.mapStatus.visibility = View.GONE
                    }
                }, 3500)
                if (showToast) {
                    toast(
                        "已定位 %.5f, %.5f ±%.0fm".format(
                            mapPoint.latitude,
                            mapPoint.longitude,
                            raw.accuracy
                        )
                    )
                }
            },
            onError = { msg ->
                locating = false
                binding.btnLocate.isEnabled = true
                binding.mapStatus.text = msg
                if (showToast) toast(msg)
            }
        )
    }

    private fun maybeShowConsent() {
        if (SafetyPolicy.hasConsent(this)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.safety_title)
            .setMessage(R.string.safety_message)
            .setCancelable(false)
            .setPositiveButton(R.string.safety_accept) { _, _ ->
                SafetyPolicy.setConsent(this, true)
            }
            .setNegativeButton(R.string.safety_decline) { _, _ -> finish() }
            .show()
    }

    private fun showWeChatHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wechat_help_title)
            .setMessage(R.string.wechat_help_message)
            .setPositiveButton(R.string.open_dev_options) { _, _ -> openDeveloperSettings() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun requestStartMock() {
        if (!SafetyPolicy.hasConsent(this)) {
            maybeShowConsent()
            return
        }
        if (!isMockLocationAppSelected()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.need_mock_app)
                .setMessage(R.string.wechat_help_message)
                .setPositiveButton(R.string.open_dev_options) { _, _ -> openDeveloperSettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val speed = binding.speedSlider.value
        if (speed > SafetyPolicy.SAFE_SPEED_MPS) {
            AlertDialog.Builder(this)
                .setTitle(R.string.speed_confirm_title)
                .setMessage(getString(R.string.speed_confirm_message, speed))
                .setPositiveButton(R.string.continue_anyway) { _, _ -> startMock() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            startMock()
        }
    }

    private fun confirmJumpTo(point: GeoPoint) {
        if (!SafetyPolicy.hasConsent(this)) {
            maybeShowConsent()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.jump_confirm_title)
            .setMessage(R.string.jump_confirm_message)
            .setPositiveButton(R.string.jump_here) { _, _ -> jumpTo(point) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPostStopSafetyReminder() {
        AlertDialog.Builder(this)
            .setTitle(R.string.after_stop_title)
            .setMessage(R.string.after_stop_message)
            .setPositiveButton(R.string.open_dev_options) { _, _ -> openDeveloperSettings() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun addWaypoint(point: GeoPoint) {
        if (waypoints.isNotEmpty()) {
            val jump = GeoMath.distanceMeters(waypoints.last(), point)
            if (jump > SafetyPolicy.MAX_WAYPOINT_JUMP_METERS) {
                toast(getString(R.string.jump_too_far, (jump / 1000).toInt()))
                return
            }
        }
        waypoints.add(point)
        autoRouteReady = false
        val marker = Marker(binding.map).apply {
            position = OsmGeoPoint(point.latitude, point.longitude)
            title = "#${waypoints.size}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        waypointMarkers.add(marker)
        binding.map.overlays.add(marker)
        binding.map.invalidate()
        binding.statusText.text = getString(R.string.points_selected, waypoints.size)
    }

    private fun validateWaypointJumps(points: List<GeoPoint>): Boolean {
        for (i in 0 until points.lastIndex) {
            val d = GeoMath.distanceMeters(points[i], points[i + 1])
            if (d > SafetyPolicy.MAX_WAYPOINT_JUMP_METERS) {
                toast(getString(R.string.jump_too_far, (d / 1000).toInt()))
                return false
            }
        }
        return true
    }

    private fun drawRouteLine(points: List<GeoPoint>) {
        routeLine?.let { binding.map.overlays.remove(it) }
        if (points.size < 2) {
            routeLine = null
            binding.map.invalidate()
            return
        }
        val line = Polyline().apply {
            outlinePaint.color = Color.parseColor("#0B6E4F")
            outlinePaint.strokeWidth = 10f
            setPoints(points.map { OsmGeoPoint(it.latitude, it.longitude) })
        }
        routeLine = line
        binding.map.overlays.add(line)
        binding.map.invalidate()
    }

    private fun updateCursor(point: GeoPoint, follow: Boolean) {
        val osm = OsmGeoPoint(point.latitude, point.longitude)
        val marker = cursorMarker ?: Marker(binding.map).apply {
            title = "模拟位置"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            cursorMarker = this
            binding.map.overlays.add(this)
        }
        marker.position = osm
        if (follow) binding.map.controller.animateTo(osm)
        binding.map.invalidate()
    }

    private fun clearRoute(stopIfRunning: Boolean) {
        if (stopIfRunning && MockLocationService.isRunning) {
            stopMock(showReminder = false)
        }
        waypoints.clear()
        routePoints = emptyList()
        autoRouteReady = false
        waypointMarkers.forEach { binding.map.overlays.remove(it) }
        waypointMarkers.clear()
        routeLine?.let { binding.map.overlays.remove(it) }
        routeLine = null
        cursorMarker?.let { binding.map.overlays.remove(it) }
        cursorMarker = null
        binding.map.invalidate()
        binding.statusText.text = getString(R.string.status_idle)
    }

    private fun startMock() {
        if (!hasLocationPermission()) {
            toast(R.string.need_location_permission)
            ensurePermissions()
            return
        }

        val path = when {
            autoMode -> {
                if (!autoRouteReady || routePoints.size < 2) {
                    toast(R.string.need_auto_plan)
                    return
                }
                routePoints
            }
            routePoints.size >= 2 -> routePoints
            else -> {
                toast(R.string.need_draw_first)
                return
            }
        }

        val speed = binding.speedSlider.value.toDouble()
            .coerceIn(0.1, SafetyPolicy.MAX_SPEED_MPS.toDouble())

        val intent = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LATS, path.map { it.latitude }.toDoubleArray())
            .putExtra(MockLocationService.EXTRA_LNGS, path.map { it.longitude }.toDoubleArray())
            .putExtra(MockLocationService.EXTRA_SPEED, speed)
            .putExtra(MockLocationService.EXTRA_CONVERT_GCJ, convertGcjToWgs)

        ContextCompat.startForegroundService(this, intent)
        binding.btnToggle.text = getString(R.string.stop_mock)
        toast(R.string.mock_started_hint)
    }

    private fun stopMock(showReminder: Boolean) {
        startService(
            Intent(this, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_STOP)
        )
        binding.btnToggle.text = getString(R.string.start_mock)
        if (showReminder) {
            binding.root.post { showPostStopSafetyReminder() }
        }
    }

    private fun jumpTo(point: GeoPoint) {
        if (!isMockLocationAppSelected()) {
            toast(R.string.need_mock_app)
            openDeveloperSettings()
            return
        }
        if (!hasLocationPermission()) {
            toast(R.string.need_location_permission)
            ensurePermissions()
            return
        }
        clearRoute(stopIfRunning = false)
        routePoints = listOf(point, point)
        updateCursor(point, follow = true)
        val intent = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LATS, doubleArrayOf(point.latitude, point.latitude))
            .putExtra(MockLocationService.EXTRA_LNGS, doubleArrayOf(point.longitude, point.longitude))
            .putExtra(MockLocationService.EXTRA_SPEED, SafetyPolicy.DEFAULT_SPEED_MPS.toDouble())
            .putExtra(MockLocationService.EXTRA_CONVERT_GCJ, convertGcjToWgs)
        ContextCompat.startForegroundService(this, intent)
        binding.btnToggle.text = getString(R.string.stop_mock)
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isMockLocationAppSelected(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    "android:mock_location",
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    "android:mock_location",
                    android.os.Process.myUid(),
                    packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
