package com.runkeep.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.*
import com.kakao.vectormap.route.*
import com.runkeep.app.BuildConfig
import com.runkeep.app.RunkeepApp
import com.runkeep.app.tracking.PositionPhase
import com.runkeep.core.Coordinate
import com.runkeep.core.Geo
import com.runkeep.core.MapCameraPolicy
import com.runkeep.core.MapRouteKind
import com.runkeep.core.MapRouteSink
import com.runkeep.core.MapRouteUpdates
import com.runkeep.core.RecordEndpointKind
import com.runkeep.core.recordMapEndpoints
import com.runkeep.core.RecordEndpointScreenPoint
import com.runkeep.core.recordEndpointAnchors
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.hypot
import kotlin.math.abs
import kotlinx.coroutines.delay

private fun Coordinate.latLng() = LatLng.from(lat, lon)

@Composable fun RunMap(
    planned: List<Coordinate>, modifier: Modifier = Modifier, tracks: List<List<Coordinate>> = emptyList(),
    current: Coordinate? = null, onTap: ((Coordinate) -> Unit)? = null,
    centerOnUser: Boolean = false, followUserInitially: Boolean = false,
    selectedPoint: Int? = null, onPointSelected: ((Int) -> Unit)? = null,
    onPointMoved: ((Int, Coordinate) -> Unit)? = null, showDistances: Boolean = false,
    focusPoint: Coordinate? = null, useLocationProvider: Boolean = true,
    selectedSegment: Int? = null, segmentLabels: List<String> = emptyList(), onSegmentSelected: ((Int) -> Unit)? = null,
    showRecordEndpoints: Boolean = false
) {
    val mapModifier = modifier.semantics { if (showRecordEndpoints && tracks.any { it.isNotEmpty() }) contentDescription = "기록 경로 · 출발 삼각형 · 종료 깃발" }
    val context = LocalContext.current
    val app = context.applicationContext as RunkeepApp
    var wantPosition by remember { mutableStateOf(useLocationProvider) }
    val position = rememberPosition(app, enabled = wantPosition && useLocationProvider)
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(wantPosition) {
        while (wantPosition) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) }
    }
    val fix = position.recentFix(now)
    val location = current ?: if (wantPosition && useLocationProvider) fix?.let { Coordinate(it.latitude, it.longitude) } else null
    if (BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) {
        Surface(mapModifier, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.Center) {
                Text("카카오 지도 설정이 필요합니다", style = MaterialTheme.typography.titleMedium)
                Text("지도 설정을 확인한 뒤 다시 실행해주세요.")
            }
        }
        return
    }
    val owner = LocalLifecycleOwner.current
    val points by rememberUpdatedState(planned)
    val tap by rememberUpdatedState(onTap)
    val select by rememberUpdatedState(onPointSelected)
    val move by rememberUpdatedState(onPointMoved)
    val selected by rememberUpdatedState(selectedPoint)
    val selectSegment by rememberUpdatedState(onSegmentSelected)
    val latestLocation by rememberUpdatedState(location)
    var map by remember { mutableStateOf<KakaoMap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val cameraPolicy = remember { MapCameraPolicy(centerOnUser) }
    var follow by remember { mutableStateOf(followUserInitially) }
    var centerRequested by remember { mutableStateOf(centerOnUser) }
    var centered by remember { mutableStateOf(false) }
    var currentLabel by remember { mutableStateOf<Label?>(null) }
    val pointLabels = remember { mutableMapOf<Int, Label>() }
    val density = context.resources.displayMetrics.density
    val view = remember {
        KakaoMapSdk.init(context.applicationContext, BuildConfig.KAKAO_NATIVE_APP_KEY)
        MapView(context)
    }
    val frame = remember { MapGestureFrame(context).apply { addView(view, ViewGroup.LayoutParams(-1, -1)) } }
    val actualStyles = remember { RouteLineStylesSet.from(RouteLineStyles.from(RouteLineStyle.from(4 * density, 0xff195c46.toInt()))) }
    val plannedStyles = remember { RouteLineStylesSet.from(RouteLineStyles.from(RouteLineStyle.from(3 * density, 0xff83998e.toInt()))) }
    val currentStyle = remember { LabelStyles.from(LabelStyle.from(currentDot(density)).setApplyDpScale(false).setAnchorPoint(.5f, .5f)) }
    val routeUpdates = remember(map) {
        map?.routeLineManager?.layer?.let { layer ->
            MapRouteUpdates(object : MapRouteSink<RouteLine> {
                override fun create(kind: MapRouteKind, points: List<Coordinate>): RouteLine {
                    val styles = if (kind == MapRouteKind.PLANNED) plannedStyles else actualStyles
                    val segment = RouteLineSegment.from(points.map { it.latLng() }).setStyles(styles.getStyles(0))
                    return requireNotNull(layer.addRouteLine(RouteLineOptions.from(segment).setStylesSet(styles))).apply {
                        setZOrder(if (kind == MapRouteKind.PLANNED) 0 else 1)
                    }
                }
                override fun update(kind: MapRouteKind, handle: RouteLine, points: List<Coordinate>) {
                    val styles = if (kind == MapRouteKind.PLANNED) plannedStyles else actualStyles
                    handle.changeSegments(RouteLineSegment.from(points.map { it.latLng() }).setStyles(styles.getStyles(0)))
                }
                override fun remove(handle: RouteLine) { handle.remove() }
            })
        }
    }
    val endpoints = remember(tracks, showRecordEndpoints) { if (showRecordEndpoints) recordMapEndpoints(tracks) else emptyList() }
    val endpointBitmaps = remember(density, endpoints.isNotEmpty()) {
        if (endpoints.isEmpty()) emptyMap() else RecordEndpointKind.entries.associateWith { endpointBadge(it, density) }
    }
    // This effect owns only the record layer and observation callbacks, never the route or camera.
    if (endpoints.isNotEmpty()) DisposableEffect(map, endpoints, density) {
        val ready = map
        val layer = ready?.labelManager?.addLayer(LabelLayerOptions.from("record-endpoints")
            .setCompetitionType(CompetitionType.None).setClickable(false).setZOrder(2000))
        var disposed = false
        val styles = endpoints.associate { endpoint -> endpoint.kind to listOf(0f, .5f, 1f).associateWith { anchor ->
            LabelStyles.from(LabelStyle.from(endpointBitmaps.getValue(endpoint.kind))
                .setApplyDpScale(false).setAnchorPoint(anchor, .5f))
        } }
        val labels = endpoints.map { endpoint ->
            layer?.addLabel(LabelOptions.from(endpoint.coordinate.latLng())
                .setStyles(styles.getValue(endpoint.kind).getValue(.5f)).setRank(200000L))
        }
        var previousAnchors: Pair<Float, Float>? = null
        fun refreshAnchors() {
            if (disposed || ready == null || map !== ready || endpoints.size != 2) return
            val start = ready.toScreenPoint(endpoints.first().coordinate.latLng()) ?: return
            val end = ready.toScreenPoint(endpoints.last().coordinate.latLng()) ?: return
            val anchors = recordEndpointAnchors(RecordEndpointScreenPoint(start.x.toFloat(), start.y.toFloat()),
                RecordEndpointScreenPoint(end.x.toFloat(), end.y.toFloat()), 36f * density)
            if (anchors == previousAnchors) return
            labels.forEachIndexed { index, label ->
                val anchor = if (index == 0) anchors.first else anchors.second
                label?.changeStyles(styles.getValue(endpoints[index].kind).getValue(anchor), false)
            }
            previousAnchors = anchors
        }
        // SDK callback work is posted to the view thread; queued work cannot outlive this effect/map.
        ready?.setOnCameraMoveEndListener { _, _, _ -> frame.post { refreshAnchors() } }
        ready?.setOnViewportChangeListener { _, _ -> frame.post { refreshAnchors() } }
        refreshAnchors()
        onDispose {
            disposed = true
            if (map === ready) {
                ready?.setOnCameraMoveEndListener(null)
                ready?.setOnViewportChangeListener(null)
                layer?.removeAll()
                layer?.let { ready?.labelManager?.remove(it) }
            }
        }
    }
    val latestRouteUpdates by rememberUpdatedState(routeUpdates)
    DisposableEffect(map, planned, selectedSegment) {
        val readyMap = map
        val layer = readyMap?.routeLineManager?.layer
        val index = selectedSegment?.takeIf { it in 0 until planned.lastIndex }
        val styles = RouteLineStylesSet.from(RouteLineStyles.from(RouteLineStyle.from(6 * density, 0xffe08022.toInt())))
        val line = if (layer != null && index != null) layer.addRouteLine(RouteLineOptions.from(
            RouteLineSegment.from(listOf(planned[index].latLng(), planned[index + 1].latLng())).setStyles(styles.getStyles(0))).setStylesSet(styles))
            ?.apply { setZOrder(2) } else null
        onDispose { if (map === readyMap) line?.remove() }
    }
    DisposableEffect(view, owner) {
        var disposed = false
        fun forgetDestroyedMap() {
            latestRouteUpdates?.forget()
            currentLabel = null; pointLabels.clear(); map = null
        }
        view.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() {
                if (!disposed) forgetDestroyedMap()
            }
            override fun onMapError(exception: Exception) {
                if (!disposed) {
                    forgetDestroyedMap()
                    error = "지도 연결 실패. 인터넷 연결과 지도 설정을 확인해주세요."
                }
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(kakaoMap: KakaoMap) {
                if (disposed) return
                map = kakaoMap
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
            }
            override fun getPosition(): LatLng {
                val initial = if (centerOnUser) latestLocation ?: points.firstOrNull() else points.firstOrNull() ?: latestLocation
                return (initial ?: Coordinate(37.5665, 126.9780)).latLng()
            }
            override fun getZoomLevel(): Int = 17
        })
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.resume()
            if (event == Lifecycle.Event.ON_PAUSE) view.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            try { latestRouteUpdates?.clear() } finally {
                latestRouteUpdates?.forget()
                currentLabel = null; pointLabels.clear(); map = null
                owner.lifecycle.removeObserver(observer); view.finish()
            }
        }
    }
    // Course labels change only when editing, not on every GPS update.
    LaunchedEffect(map, planned, selectedPoint, showDistances, selectedSegment, segmentLabels) {
        val ready = map ?: return@LaunchedEffect
        val labels = ready.labelManager?.layer ?: return@LaunchedEffect
        labels.removeAll(); pointLabels.clear(); currentLabel = null
        val cumulative = planned.zipWithNext().map { (a, b) -> Geo.distance(a, b) }.runningFold(0.0, Double::plus)
        val visibleIndices = if (planned.size <= 200) planned.indices.toList() else listOfNotNull(0, selectedPoint, planned.lastIndex).distinct().filter { it in planned.indices }
        for (index in visibleIndices) {
            val distance = if (showDistances && index > 0) "%.2f km".format(cumulative[index] / 1000) else null
            val bitmap = pointBadge(index + 1, distance, index == selectedPoint, density)
            val style = LabelStyles.from(LabelStyle.from(bitmap).setApplyDpScale(false).setAnchorPoint(.5f, if (distance != null) 54f / 72f else .5f))
            labels.addLabel(LabelOptions.from(planned[index].latLng()).setStyles(style).setRank(if (index == selectedPoint) 10000L else 100L + index))?.let { pointLabels[index] = it }
        }
        if (showDistances && planned.size <= 200) planned.zipWithNext().forEachIndexed { index, (a, b) ->
            val midpoint = Coordinate((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
            val text = segmentLabels.getOrNull(index) ?: "%.2f km".format(Geo.distance(a, b) / 1000)
            val style = LabelStyles.from(LabelStyle.from(distanceBadge(text, density, selectedSegment == index)).setApplyDpScale(false).setAnchorPoint(.5f, .5f))
            labels.addLabel(LabelOptions.from(midpoint.latLng()).setStyles(style).setRank(1L))
        }
        latestLocation?.let { currentLabel = labels.addLabel(LabelOptions.from(it.latLng()).setStyles(currentStyle).setRank(100000L)) }
    }
    LaunchedEffect(map, location, currentLabel) {
        val ready = map ?: return@LaunchedEffect
        if (location == null) { currentLabel?.hide(); return@LaunchedEffect }
        if (currentLabel == null) currentLabel = ready.labelManager?.layer?.addLabel(LabelOptions.from(location.latLng()).setStyles(currentStyle).setRank(100000L))
        else { currentLabel?.moveTo(location.latLng()); currentLabel?.show(false) }
        if (follow || centerRequested) {
            ready.moveCamera(if (centered) CameraUpdateFactory.newCenterPosition(location.latLng()) else CameraUpdateFactory.newCenterPosition(location.latLng(), 17))
            centered = true; centerRequested = false
        }
    }
    LaunchedEffect(map, follow, centerRequested) {
        val ready = map ?: return@LaunchedEffect
        if (centerRequested) latestLocation?.let { ready.moveCamera(CameraUpdateFactory.newCenterPosition(it.latLng(), 17)); centered = true; centerRequested = false }
    }
    LaunchedEffect(map, routeUpdates, planned, tracks) {
        val ready = map ?: return@LaunchedEffect
        routeUpdates?.reconcile(planned, tracks)
        if (cameraPolicy.fitInitially(planned.size + tracks.sumOf { it.size } >= 2)) {
            val all = planned + tracks.flatten()
            ready.moveCamera(CameraUpdateFactory.fitMapPoints(all.map { it.latLng() }.toTypedArray(), (40 * density).toInt(), 18))
        }
    }
    LaunchedEffect(map, focusPoint) {
        focusPoint?.let { cameraPolicy.userMoved(); follow = false; centerRequested = false; map?.moveCamera(CameraUpdateFactory.newCenterPosition(it.latLng())) }
    }
    SideEffect {
        frame.segmentHitTest = { x, y ->
            if (selectSegment == null) null else points.zipWithNext().mapIndexedNotNull { index, (a, b) ->
                val start = map?.toScreenPoint(a.latLng()); val end = map?.toScreenPoint(b.latLng())
                if (start == null || end == null) null else {
                    val dx = (end.x - start.x).toDouble(); val dy = (end.y - start.y).toDouble()
                    val length = dx * dx + dy * dy
                    val fraction = if (length == 0.0) 0.0 else (((x - start.x) * dx + (y - start.y) * dy) / length).coerceIn(0.0, 1.0)
                    val away = hypot(x - start.x - fraction * dx, y - start.y - fraction * dy)
                    if (away <= 16 * density) index to away else null
                }
            }.minByOrNull { it.second }?.first
        }
        frame.onSegmentSelected = { index -> selectSegment?.invoke(index) }
        frame.hitTest = { x, y ->
            if (select == null) null else pointLabels.keys.mapNotNull { index ->
                points.getOrNull(index)?.let { point -> map?.toScreenPoint(point.latLng())?.let { p ->
                    val distance = hypot(p.x - x, p.y - y)
                    val onBadge = showDistances && index > 0 && abs(p.x - x) <= 75 * density && y - p.y in (-54 * density)..(-25 * density)
                    if (distance <= 28 * density || onBadge) index to distance else null
                } }
            }.minByOrNull { it.second }?.first
        }
        frame.selectedPoint = { if (move != null) selected else null }
        frame.onPointSelected = { index -> select?.invoke(index) }
        frame.onMapTap = { x, y -> map?.fromScreenPoint(x.toInt(), y.toInt())?.let { tap?.invoke(Coordinate(it.latitude, it.longitude)) } }
        frame.onPan = { cameraPolicy.userMoved(); follow = false; centerRequested = false }
        frame.onPointDragged = { index, x, y -> map?.fromScreenPoint(x.toInt(), y.toInt())?.let { pointLabels[index]?.moveTo(it) } }
        frame.onPointDropped = { index, x, y -> map?.fromScreenPoint(x.toInt(), y.toInt())?.let { move?.invoke(index, Coordinate(it.latitude, it.longitude)) } }
        frame.onDragCancelled = { index -> points.getOrNull(index)?.let { pointLabels[index]?.moveTo(it.latLng()) } }
    }
    Box(mapModifier) {
        val fitAll: () -> Unit = {
            cameraPolicy.userMoved()
            val all = planned + tracks.flatten(); follow = false; centerRequested = false
            map?.moveCamera(CameraUpdateFactory.fitMapPoints(all.map { it.latLng() }.toTypedArray(), (40 * density).toInt(), 18))
        }
        val centerUser: () -> Unit = {
            cameraPolicy.userMoved()
            wantPosition = useLocationProvider; follow = followUserInitially; centerRequested = true
            if (useLocationProvider && fix == null) app.location.restart()
        }
        AndroidView(factory = { frame }, modifier = Modifier.fillMaxSize())
        if (wantPosition && location == null) Surface(Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 270.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp)) {
                Text(position.message(now), style = MaterialTheme.typography.bodySmall)
                if (position.phase == PositionPhase.DISABLED) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("위치 켜기") }
                if (position.phase == PositionPhase.PERMISSION) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("정확한 위치 허용") }
            }
        }
        MapCameraControls(
            fitEnabled = map != null && error == null && (planned.size >= 2 || tracks.any { it.size >= 2 }),
            locationEnabled = map != null && error == null && location != null,
            following = follow && location != null, onFit = fitAll, onLocation = centerUser,
            modifier = Modifier.align(Alignment.BottomEnd))
        error?.let { message -> Surface(Modifier.align(Alignment.Center).padding(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
            TextButton(onClick = { error = null }) { Text(message) }
        } }
    }
}

private fun currentDot(density: Float): Bitmap {
    val size = (36 * density).toInt(); val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0x40e83f4f; canvas.drawCircle(size / 2f, size / 2f, 18 * density, paint)
    paint.color = android.graphics.Color.WHITE; canvas.drawCircle(size / 2f, size / 2f, 10 * density, paint)
    paint.color = 0xffe83f4f.toInt(); canvas.drawCircle(size / 2f, size / 2f, 7 * density, paint)
    return bitmap
}
private fun pointBadge(number: Int, text: String?, selected: Boolean, density: Float): Bitmap {
    val width = if (text == null) 36 else 150; val height = if (text == null) 36 else 72
    val bitmap = Bitmap.createBitmap((width * density).toInt(), (height * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); canvas.scale(density, density)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    val centerY = if (text == null) 18f else 54f
    if (text != null) {
        paint.color = android.graphics.Color.WHITE; canvas.drawRoundRect(RectF(1f, 1f, 149f, 29f), 9f, 9f, paint)
        paint.color = 0xff195c46.toInt(); paint.textSize = 12f; canvas.drawText(text, 75f, 19f, paint)
    }
    paint.color = android.graphics.Color.WHITE; canvas.drawCircle(width / 2f, centerY, 16f, paint)
    paint.color = if (selected) 0xffe08022.toInt() else 0xff195c46.toInt(); canvas.drawCircle(width / 2f, centerY, 13f, paint)
    paint.color = android.graphics.Color.WHITE; paint.textSize = 11f; canvas.drawText(number.toString(), width / 2f, centerY + 4, paint)
    return bitmap
}
private fun distanceBadge(text: String, density: Float, selected: Boolean = false): Bitmap {
    val width = maxOf(82f, Paint().apply { textSize = 12f }.measureText(text) + 16f)
    val bitmap = Bitmap.createBitmap((width * density).toInt(), (26 * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); canvas.scale(density, density)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = if (selected) 0xffe08022.toInt() else 0xff195c46.toInt(); canvas.drawRoundRect(RectF(0f, 0f, width, 26f), 8f, 8f, paint)
    paint.color = android.graphics.Color.WHITE; paint.textSize = 12f; paint.textAlign = Paint.Align.CENTER; canvas.drawText(text, width / 2, 17f, paint)
    return bitmap
}

/** Shapes distinguish start/end independently of colour; BOTH shares the real coordinate. */
private fun endpointBadge(kind: RecordEndpointKind, density: Float): Bitmap {
    val width = if (kind == RecordEndpointKind.BOTH) 72f else 36f
    val bitmap = Bitmap.createBitmap((width * density).toInt().coerceAtLeast(1), (36 * density).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); canvas.scale(density, density)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun start(x: Float) {
        paint.color = 0xff195c46.toInt(); canvas.drawCircle(x, 18f, 16f, paint)
        paint.color = 0xffffffff.toInt()
        val triangle = android.graphics.Path().apply { moveTo(x - 5f, 10f); lineTo(x + 8f, 18f); lineTo(x - 5f, 26f); close() }
        canvas.drawPath(triangle, paint)
    }
    fun end(x: Float) {
        paint.color = 0xff383838.toInt(); canvas.drawRoundRect(RectF(x - 16f, 2f, x + 16f, 34f), 5f, 5f, paint)
        paint.color = 0xffffffff.toInt(); paint.strokeWidth = 2f
        canvas.drawLine(x - 7f, 9f, x - 7f, 28f, paint)
        canvas.drawRect(x - 6f, 9f, x + 9f, 21f, paint)
        paint.color = 0xff383838.toInt()
        for (row in 0..1) for (column in 0..2) if ((row + column) % 2 == 0)
            canvas.drawRect(x - 6f + column * 5, 9f + row * 6, x - 1f + column * 5, 15f + row * 6, paint)
    }
    when (kind) { RecordEndpointKind.START -> start(18f); RecordEndpointKind.END -> end(18f); RecordEndpointKind.BOTH -> { start(18f); end(54f) } }
    return bitmap
}
