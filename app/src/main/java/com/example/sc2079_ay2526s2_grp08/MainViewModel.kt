package com.example.sc2079_ay2526s2_grp08

import androidx.lifecycle.ViewModel
import com.example.sc2079_ay2526s2_grp08.bluetooth.BluetoothManager
import com.example.sc2079_ay2526s2_grp08.domain.*
import com.example.sc2079_ay2526s2_grp08.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Main ViewModel - the single point of interaction between UI and backend.
 *
 * UI layer should call this; UI must NOT touch BluetoothManager directly.
 * All state is exposed via [state] StateFlow which UI observes.
 *
 * MDP ARCM Checklist mapping:
 * - C.1: Bluetooth transmit/receive (via send* methods and state.log)
 * - C.2: Device scanning/selection (via connectToDevice, getPairedDevices, startScan)
 * - C.3: Robot movement (via sendMoveForward, sendTurnLeft, etc.)
 * - C.4: Status display (via state.statusText)
 * - C.5: Arena display (via state.arena)
 * - C.6: Obstacle placement (via sendAddObstacle, sendRemoveObstacle)
 * - C.7: Target face annotation (via sendSetObstacleFace)
 * - C.8: Robust connectivity (handled by BluetoothManager)
 * - C.9: Target ID display (handled by handleTargetDetected)
 * - C.10: Robot position update (handled by handleRobotPosition)
 */
class MainViewModel(
    private val bt: BluetoothManager
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    // Counter for generating obstacle IDs
    private var obstacleCounter = 0

    init {
        bt.onEvent = { ev -> handleBluetoothEvent(ev) }
        // Populate paired list immediately so Connect UI isn't empty at first launch
        refreshPairedDevices()
    }

    /** Start listening for incoming connections (server mode) */
    fun startDefaultListening() {
        bt.startServer()
    }

    /** Disconnect current session and return to listening mode */
    fun disconnectAndReturnToListening() {
        bt.stopServer()
        bt.disconnectClient()
        bt.startServer()
    }

    /** Connect to a specific Bluetooth device (client mode) */
    fun connectToDevice(device: android.bluetooth.BluetoothDevice) {
        bt.connect(device)
    }

    fun connectToAddress(address: String) {
        // 1) Try paired devices first (most stable)
        val pairedMatch = try {
            bt.getPairedDevices().firstOrNull { it.address == address }
        } catch (_: SecurityException) {
            null
        }
        if (pairedMatch != null) {
            bt.connect(pairedMatch)
            return
        }

        // 2) Try discovered (scanned) devices
        val discoveredMatch = bt.getDiscoveredDevices().firstOrNull { it.address == address }
        if (discoveredMatch != null) {
            bt.connect(discoveredMatch)
            return
        }

        appendLog(LogEntry.Kind.ERROR, "CONNECT failed: device not found ($address)")
    }

    /** Get list of paired Bluetooth devices */
    fun getPairedDevices(): List<android.bluetooth.BluetoothDevice> {
        return bt.getPairedDevices()
    }

    /** Check if Bluetooth is supported on this device */
    fun isBluetoothSupported(): Boolean = bt.isSupported()

    /** Check if Bluetooth is enabled */
    fun isBluetoothEnabled(): Boolean = bt.isEnabled()

    // -------------------------------------------------------------------------
    // Preetish-style local simulation: always update UI immediately,
    // only send BT command if connected.
    // -------------------------------------------------------------------------

    private fun isConnected(): Boolean =
        _state.value.conn == BluetoothManager.State.CONNECTED

    private fun clampToArena(x: Int, y: Int): Pair<Int, Int> {
        val arena = _state.value.arena
        if (arena == null) return x to y
        val cx = x.coerceIn(0, arena.width - 1)
        val cy = y.coerceIn(0, arena.height - 1)
        return cx to cy
    }

    private fun simulateForward(steps: Int = 1) {
        _state.update { s ->
            val r = s.robot ?: return@update s
            val (dx, dy) = when (r.robotDirection) {
                RobotDirection.NORTH -> 0 to 1
                RobotDirection.EAST -> 1 to 0
                RobotDirection.SOUTH -> 0 to -1
                RobotDirection.WEST -> -1 to 0
            }
            val (nx, ny) = clampToArena(r.x + dx * steps, r.y + dy * steps)
            s.copy(robot = r.copy(x = nx, y = ny))
        }
    }

    private fun simulateBackward(steps: Int = 1) {
        _state.update { s ->
            val r = s.robot ?: return@update s
            val (dx, dy) = when (r.robotDirection) {
                RobotDirection.NORTH -> 0 to -1
                RobotDirection.EAST -> -1 to 0
                RobotDirection.SOUTH -> 0 to 1
                RobotDirection.WEST -> 1 to 0
            }
            val (nx, ny) = clampToArena(r.x + dx * steps, r.y + dy * steps)
            s.copy(robot = r.copy(x = nx, y = ny))
        }
    }

    private fun simulateTurnLeft() {
        _state.update { s ->
            val r = s.robot ?: return@update s
            val newDeg = (r.directionDeg + 270) % 360
            s.copy(robot = r.copy(directionDeg = newDeg))
        }
    }

    private fun simulateTurnRight() {
        _state.update { s ->
            val r = s.robot ?: return@update s
            val newDeg = (r.directionDeg + 90) % 360
            s.copy(robot = r.copy(directionDeg = newDeg))
        }
    }

    // Movement API (UI calls these)
    fun sendMoveForward() {
        simulateForward(1)
        if (isConnected()) send(Outgoing.MoveForward) else appendLog(LogEntry.Kind.INFO, "SIM MOVE,F")
    }

    fun sendMoveBackward() {
        simulateBackward(1)
        if (isConnected()) send(Outgoing.MoveBackward) else appendLog(LogEntry.Kind.INFO, "SIM MOVE,B")
    }

    fun sendTurnLeft() {
        simulateTurnLeft()
        if (isConnected()) send(Outgoing.TurnLeft) else appendLog(LogEntry.Kind.INFO, "SIM TURN,L")
    }

    fun sendTurnRight() {
        simulateTurnRight()
        if (isConnected()) send(Outgoing.TurnRight) else appendLog(LogEntry.Kind.INFO, "SIM TURN,R")
    }

    fun sendMoveForward(steps: Int) {
        val s = steps.coerceAtLeast(1)
        simulateForward(s)
        if (isConnected()) send(Outgoing.MoveForwardSteps(s)) else appendLog(LogEntry.Kind.INFO, "SIM MOVE,F $s")
    }

    fun sendMoveBackward(steps: Int) {
        val s = steps.coerceAtLeast(1)
        simulateBackward(s)
        if (isConnected()) send(Outgoing.MoveBackwardSteps(s)) else appendLog(LogEntry.Kind.INFO, "SIM MOVE,B $s")
    }

    fun sendTurnDegrees(degrees: Int) {
        // Local preview for common angles; still sends raw degrees if connected
        val d = ((degrees % 360) + 360) % 360
        when (d) {
            90 -> simulateTurnRight()
            180 -> { simulateTurnRight(); simulateTurnRight() }
            270 -> simulateTurnLeft()
        }
        if (isConnected()) send(Outgoing.TurnDegrees(degrees)) else appendLog(LogEntry.Kind.INFO, "SIM TURN $degrees")
    }

    /**
     * Add obstacle at position (x, y) with given ID.
     * Also updates local arena state.
     */
    fun sendAddObstacle(obstacleId: String, x: Int, y: Int) {
        send(Outgoing.AddObstacle(obstacleId, x, y))
        updateLocalObstacle(obstacleId, x, y, add = true)
    }

    // -------------------------------------------------------------------------
    // Bluetooth scanning support (TM8 merge)
    // -------------------------------------------------------------------------

    fun refreshPairedDevices() {
        val paired = bt.getPairedDevices().map { it.toBtDevice(bonded = true) }
        _state.update { it.copy(pairedDevices = paired) }
    }

    fun startScan() {
        refreshPairedDevices()
        _state.update { it.copy(scannedDevices = emptyList(), isScanning = true) }
        bt.clearDiscoveredDevices()
        bt.startDiscovery()
    }

    fun stopScan() {
        bt.stopDiscovery()
        _state.update { it.copy(isScanning = false) }
    }

    /**
     * Add obstacle at position with auto-generated ID (B1, B2, etc.)
     */
    fun sendAddObstacle(x: Int, y: Int): String {
        obstacleCounter++
        val obstacleId = "B$obstacleCounter"
        sendAddObstacle(obstacleId, x, y)
        return obstacleId
    }

    /**
     * Remove obstacle by ID.
     * Also updates local arena state.
     */
    fun sendRemoveObstacle(obstacleId: String) {
        send(Outgoing.RemoveObstacle(obstacleId))
        removeLocalObstacle(obstacleId)
    }

    /**
     * Set which face of an obstacle has the target image.
     */
    fun sendSetObstacleFace(obstacleId: String, face: RobotDirection) {
        send(Outgoing.SetObstacleFace(obstacleId, face))
        updateLocalObstacleFace(obstacleId, face)
    }

    /**
     * Send robot position to remote device.
     */
    fun sendSetRobotPosition(x: Int, y: Int, direction: RobotDirection) {
        send(Outgoing.SetRobotPosition(x, y, direction))
    }

    fun sendRequestArenaInfo() = send(Outgoing.RequestArenaInfo)

    fun sendStartExploration() = send(Outgoing.StartExploration)
    fun sendStartFastestPath() = send(Outgoing.StartFastestPath)
    fun sendStop() = send(Outgoing.StopRobot)

    /** Stored configurable button commands */
    private val configButtonCommands = mutableMapOf(
        1 to "F1_DEFAULT",
        2 to "F2_DEFAULT"
    )

    /** Configure a button command (persisted locally) */
    fun setConfigButtonCommand(buttonId: Int, command: String) {
        configButtonCommands[buttonId] = command
    }

    /** Get configured command for a button */
    fun getConfigButtonCommand(buttonId: Int): String {
        return configButtonCommands[buttonId] ?: ""
    }

    /** Send the configured command for a button */
    fun sendConfigButton(buttonId: Int) {
        val command = configButtonCommands[buttonId] ?: return
        send(Outgoing.ConfigButton(buttonId, command))
    }

    fun sendStatus(status: String) = send(Outgoing.SendStatus(status))

    /** Send a raw string (for debugging or unsupported commands) */
    fun sendRaw(line: String) = send(Outgoing.Raw(line))

    /** Initialize arena with default or custom dimensions */
    fun initializeArena(width: Int = ArenaState.DEFAULT_WIDTH, height: Int = ArenaState.DEFAULT_HEIGHT) {
        obstacleCounter = 0
        _state.update { it.copy(arena = ArenaState.empty(width, height)) }
    }

    /** Set robot position locally (without sending) */
    fun setLocalRobotPosition(x: Int, y: Int, direction: RobotDirection) {
        _state.update { s ->
            s.copy(
                robot = RobotState(
                    x = x,
                    y = y,
                    directionDeg = directionToDegrees(direction),
                    robotX = s.robot?.robotX ?: 3,
                    robotY = s.robot?.robotY ?: 3
                )
            )
        }
    }

    /** Clear all detections */
    fun clearDetections() {
        _state.update { it.copy(detections = emptyList(), lastDetection = null) }
    }

    /** Clear path execution state */
    fun clearPath() {
        _state.update { it.copy(pathExecution = PathExecutionState()) }
    }

    /** Set path playback speed */
    fun setPathSpeed(speed: Float) {
        _state.update { s ->
            s.copy(pathExecution = s.pathExecution.copy(speed = speed.coerceIn(0.1f, 10f)))
        }
    }

    /** Toggle path playback */
    fun togglePathPlayback() {
        _state.update { s ->
            s.copy(pathExecution = s.pathExecution.copy(isPlaying = !s.pathExecution.isPlaying))
        }
    }

    /** Step to next pose in path */
    fun stepPathForward() {
        _state.update { s ->
            val path = s.pathExecution
            if (path.currentIndex < path.poses.lastIndex) {
                val nextIndex = path.currentIndex + 1
                val pose = path.poses[nextIndex]
                s.copy(
                    pathExecution = path.copy(currentIndex = nextIndex),
                    robot = RobotState(pose.x, pose.y, pose.directionDeg, s.robot?.robotX ?: 3, s.robot?.robotY ?: 3)
                )
            } else s
        }
    }

    /** Step to previous pose in path */
    fun stepPathBackward() {
        _state.update { s ->
            val path = s.pathExecution
            if (path.currentIndex > 0) {
                val prevIndex = path.currentIndex - 1
                val pose = path.poses[prevIndex]
                s.copy(
                    pathExecution = path.copy(currentIndex = prevIndex),
                    robot = RobotState(pose.x, pose.y, pose.directionDeg, s.robot?.robotX ?: 3, s.robot?.robotY ?: 3)
                )
            } else s
        }
    }

    /** Clear message log */
    fun clearLog() {
        _state.update { it.copy(log = emptyList()) }
    }

    // -------------------------------------------------------------------------
    // UI adapter methods (for your ArenaView/ArenaFragment)
    // -------------------------------------------------------------------------

    private fun toObstacleId(id: Int): String = "B$id"

    private fun toRobotDirection(facing: Facing): RobotDirection = when (facing) {
        Facing.N -> RobotDirection.NORTH
        Facing.E -> RobotDirection.EAST
        Facing.S -> RobotDirection.SOUTH
        Facing.W -> RobotDirection.WEST
    }

    fun placeOrMoveObstacle(id: Int, x: Int, y: Int) {
        sendAddObstacle(toObstacleId(id), x, y)
    }

    fun removeObstacle(id: Int) {
        sendRemoveObstacle(toObstacleId(id))
    }

    fun setObstacleFacing(id: Int, facing: Facing) {
        sendSetObstacleFace(toObstacleId(id), toRobotDirection(facing))
    }

    private fun send(msg: Outgoing) {
        val line = ProtocolEncoder.encode(msg)
        bt.sendLine(line)
        appendLog(LogEntry.Kind.OUT, line)
    }

    private fun handleBluetoothEvent(ev: BluetoothManager.Event) {
        when (ev) {

            // -----------------------------
            // Scan events (TM8 merge)
            // -----------------------------
            is BluetoothManager.Event.DiscoveryStarted -> {
                appendLog(LogEntry.Kind.INFO, ev.message ?: "Scanning...")
                _state.update { it.copy(isScanning = true, scannedDevices = emptyList()) }
            }

            is BluetoothManager.Event.DeviceFound -> {
                val d = ev.device.toBtDevice(bonded = false)
                _state.update { s ->
                    if (s.scannedDevices.any { it.address == d.address }) s
                    else s.copy(scannedDevices = s.scannedDevices + d)
                }
            }

            is BluetoothManager.Event.DiscoveryFinished -> {
                appendLog(LogEntry.Kind.INFO, "Scan finished (${ev.foundCount})")
                _state.update { it.copy(isScanning = false) }
            }

            // -----------------------------
            // Existing Bluetooth events
            // -----------------------------
            is BluetoothManager.Event.StateChanged -> {
                _state.update {
                    it.copy(
                        mode = ev.mode,
                        conn = ev.state,
                        statusText = ev.message ?: it.statusText
                    )
                }
                ev.message?.let { appendLog(LogEntry.Kind.INFO, "STATE: $it") }
            }

            is BluetoothManager.Event.Connected -> {
                appendLog(LogEntry.Kind.INFO, "CONNECTED: ${ev.label}")
            }

            is BluetoothManager.Event.Disconnected -> {
                appendLog(LogEntry.Kind.INFO, "DISCONNECTED: ${ev.reason} ${ev.message ?: ""}".trim())
            }

            is BluetoothManager.Event.LineReceived -> {
                appendLog(LogEntry.Kind.IN, ev.line)
                handleIncomingLine(ev.line)
            }

            is BluetoothManager.Event.EchoReceived -> {
                appendLog(LogEntry.Kind.INFO, "ECHO: ${ev.line}")
            }

            is BluetoothManager.Event.SendRejected -> {
                appendLog(
                    if (ev.reason == BluetoothManager.SendRejectReason.IO_ERROR) LogEntry.Kind.ERROR else LogEntry.Kind.INFO,
                    "SEND_REJECTED: ${ev.reason} ${ev.message ?: ""}".trim()
                )
            }

            is BluetoothManager.Event.Log -> appendLog(LogEntry.Kind.INFO, ev.message)
        }
    }

    private fun handleIncomingLine(line: String) {
        when (val msg = ProtocolParser.parse(line)) {
            is Incoming.RobotPosition -> handleRobotPosition(msg)
            is Incoming.TargetDetected -> handleTargetDetected(msg)
            is Incoming.StatusUpdate -> handleStatusUpdate(msg)
            is Incoming.GridHex -> handleGridHex(msg)
            is Incoming.GridBinary -> handleGridBinary(msg)
            is Incoming.ArenaResize -> handleArenaResize(msg)
            is Incoming.ObstacleUpdate -> handleObstacleUpdate(msg)
            is Incoming.ObstacleRemoved -> handleObstacleRemoved(msg)
            is Incoming.PathSequence -> handlePathSequence(msg)
            is Incoming.PathStep -> handlePathStep(msg)
            is Incoming.PathComplete -> handlePathComplete()
            is Incoming.PathAbort -> handlePathAbort()
            is Incoming.RequestSync -> handleRequestSync()
            is Incoming.Raw -> { /* Already logged */ }
        }
    }

    private fun handleRobotPosition(msg: Incoming.RobotPosition) {
        _state.update { s ->
            s.copy(
                robot = RobotState(
                    x = msg.x,
                    y = msg.y,
                    directionDeg = msg.directionDeg,
                    robotX = s.robot?.robotX ?: 3,
                    robotY = s.robot?.robotY ?: 3
                )
            )
        }
    }

    private fun handleTargetDetected(msg: Incoming.TargetDetected) {
        _state.update { s ->
            val arena = s.arena ?: return@update s

            val updatedCells = arena.cells.mapIndexed { _, cell ->
                val cellObstacleId = cell.obstacleId?.toString() ?: ""
                if (cell.isObstacle && (cellObstacleId == msg.obstacleId || "B$cellObstacleId" == msg.obstacleId)) {
                    cell.copy(
                        imageId = msg.targetId,
                        targetDirection = msg.face ?: cell.targetDirection
                    )
                } else {
                    cell
                }
            }

            val detection = ImageDetection(
                imageId = msg.targetId,
                label = "Obstacle ${msg.obstacleId}"
            )
            val detections = (s.detections + detection).takeLast(100)

            val arena1 = arena.copy(cells = updatedCells)

            s.copy(
                arena = arena1,
                obstacleBlocks = buildObstacleBlocks(arena1),
                detections = detections,
                lastDetection = detection
            )
        }

        appendLog(
            LogEntry.Kind.INFO,
            "TARGET: ${msg.obstacleId} -> ${msg.targetId}" + (msg.face?.let { " (face: $it)" } ?: "")
        )
    }

    private fun handleStatusUpdate(msg: Incoming.StatusUpdate) {
        _state.update { it.copy(statusText = msg.message) }
    }

    private fun handleGridHex(msg: Incoming.GridHex) {
        val width = _state.value.arena?.width ?: ArenaState.DEFAULT_WIDTH
        val height = _state.value.arena?.height ?: ArenaState.DEFAULT_HEIGHT
        val obstacles = decodeHexGrid(msg.hex, width, height)
        if (obstacles != null) {
            _state.update { it.copy(arena = ArenaState.fromObstacleArray(width, height, obstacles)) }
        } else {
            appendLog(LogEntry.Kind.ERROR, "Failed to decode grid hex length=${msg.hex.length}")
        }
    }

    private fun handleGridBinary(msg: Incoming.GridBinary) {
        _state.update { it.copy(arena = ArenaState.fromObstacleArray(msg.width, msg.height, msg.cells)) }
    }

    private fun handleArenaResize(msg: Incoming.ArenaResize) {
        _state.update { s ->
            val oldArena = s.arena
            if (oldArena == null) {
                s.copy(arena = ArenaState.empty(msg.width, msg.height))
            } else {
                val newCells = MutableList(msg.width * msg.height) { Cell.EMPTY }
                for (y in 0 until minOf(oldArena.height, msg.height)) {
                    for (x in 0 until minOf(oldArena.width, msg.width)) {
                        newCells[y * msg.width + x] = oldArena.getCell(x, y)
                    }
                }
                s.copy(arena = ArenaState(msg.width, msg.height, newCells))
            }
        }
    }

    private fun handleObstacleUpdate(msg: Incoming.ObstacleUpdate) {
        updateLocalObstacle(msg.obstacleId, msg.x, msg.y, add = true, face = msg.targetFace)
    }

    private fun handleObstacleRemoved(msg: Incoming.ObstacleRemoved) {
        removeLocalObstacle(msg.obstacleId)
    }

    private fun handlePathSequence(msg: Incoming.PathSequence) {
        _state.update { s ->
            s.copy(
                pathExecution = PathExecutionState(
                    poses = msg.poses,
                    currentIndex = -1,
                    isPlaying = false,
                    speed = s.pathExecution.speed
                )
            )
        }
        appendLog(LogEntry.Kind.INFO, "PATH RECEIVED: ${msg.poses.size} poses")
    }

    private fun handlePathStep(msg: Incoming.PathStep) {
        _state.update { s ->
            s.copy(
                robot = RobotState(
                    x = msg.pose.x,
                    y = msg.pose.y,
                    directionDeg = msg.pose.directionDeg,
                    robotX = s.robot?.robotX ?: 3,
                    robotY = s.robot?.robotY ?: 3
                )
            )
        }
    }

    private fun handlePathComplete() {
        _state.update { s ->
            s.copy(
                pathExecution = s.pathExecution.copy(
                    isPlaying = false,
                    currentIndex = s.pathExecution.poses.lastIndex
                )
            )
        }
        appendLog(LogEntry.Kind.INFO, "PATH COMPLETE")
    }

    private fun handlePathAbort() {
        _state.update { s ->
            s.copy(pathExecution = s.pathExecution.copy(isPlaying = false))
        }
        appendLog(LogEntry.Kind.INFO, "PATH ABORTED")
    }

    private fun handleRequestSync() {
        val s = _state.value
        s.robot?.let {
            send(Outgoing.SetRobotPosition(it.x, it.y, it.robotDirection))
        }
        s.arena?.getObstacles()?.forEach { (x, y, id) ->
            val obstacleId = id?.let { "B$it" } ?: "B${x}_${y}"
            send(Outgoing.AddObstacle(obstacleId, x, y))
        }
    }

    private fun updateLocalObstacle(
        obstacleId: String,
        x: Int,
        y: Int,
        add: Boolean,
        face: RobotDirection? = null
    ) {
        _state.update { s ->
            val arena0 = s.arena ?: ArenaState.empty()
            if (x !in 0 until arena0.width || y !in 0 until arena0.height) return@update s

            val numericId = obstacleId.removePrefix("B").toIntOrNull()

            val clearedCells = if (numericId != null) {
                arena0.cells.map { cell ->
                    if (cell.obstacleId == numericId) {
                        cell.copy(isObstacle = false, obstacleId = null, imageId = null, targetDirection = null)
                    } else cell
                }
            } else {
                arena0.cells
            }

            val arenaCleared = arena0.copy(cells = clearedCells)

            val cell = arenaCleared.getCell(x, y)
            val arena1 = arenaCleared.withCell(
                x, y,
                cell.copy(
                    isObstacle = add,
                    obstacleId = if (add) numericId else null,
                    targetDirection = if (add) (face ?: cell.targetDirection) else null
                )
            )

            s.copy(
                arena = arena1,
                obstacleBlocks = buildObstacleBlocks(arena1)
            )
        }
    }

    private fun removeLocalObstacle(obstacleId: String) {
        _state.update { s ->
            val arena0 = s.arena ?: return@update s
            val numericId = obstacleId.removePrefix("B").toIntOrNull()

            val updatedCells = arena0.cells.map { cell ->
                if (cell.obstacleId == numericId) {
                    cell.copy(isObstacle = false, obstacleId = null, imageId = null, targetDirection = null)
                } else cell
            }

            val arena1 = arena0.copy(cells = updatedCells)

            s.copy(
                arena = arena1,
                obstacleBlocks = buildObstacleBlocks(arena1)
            )
        }
    }

    private fun updateLocalObstacleFace(obstacleId: String, face: RobotDirection) {
        _state.update { s ->
            val arena0 = s.arena ?: return@update s
            val numericId = obstacleId.removePrefix("B").toIntOrNull()

            val updatedCells = arena0.cells.map { cell ->
                if (cell.obstacleId == numericId) cell.copy(targetDirection = face) else cell
            }

            val arena1 = arena0.copy(cells = updatedCells)

            s.copy(
                arena = arena1,
                obstacleBlocks = buildObstacleBlocks(arena1)
            )
        }
    }

    private fun buildObstacleBlocks(arena: ArenaState): List<ObstacleState> {
        val out = mutableListOf<ObstacleState>()

        arena.cells.forEachIndexed { idx, cell ->
            val id = cell.obstacleId ?: return@forEachIndexed
            if (!cell.isObstacle) return@forEachIndexed

            val x = idx % arena.width
            val y = idx / arena.width

            val facing = when (cell.targetDirection) {
                RobotDirection.NORTH -> Facing.N
                RobotDirection.EAST -> Facing.E
                RobotDirection.SOUTH -> Facing.S
                RobotDirection.WEST -> Facing.W
                null -> null
            }

            val targetId: Int? = cell.imageId?.toIntOrNull()

            out.add(
                ObstacleState(
                    id = id,
                    x = x,
                    y = y,
                    facing = facing,
                    targetId = targetId
                )
            )
        }

        return out.sortedBy { it.id }
    }

    private fun directionToDegrees(dir: RobotDirection): Int = when (dir) {
        RobotDirection.NORTH -> 0
        RobotDirection.EAST -> 90
        RobotDirection.SOUTH -> 180
        RobotDirection.WEST -> 270
    }

    private fun appendLog(kind: LogEntry.Kind, text: String) {
        _state.update { s ->
            val next = (s.log + LogEntry(kind, text))
            val capped = if (next.size > 300) next.takeLast(300) else next
            s.copy(log = capped)
        }
    }

    private fun decodeHexGrid(hex: String, width: Int, height: Int): BooleanArray? {
        val clean = hex.trim().lowercase()
        if (clean.isEmpty()) return null

        val totalBits = width * height
        val out = BooleanArray(totalBits)

        var bitIndex = 0
        for (ch in clean) {
            val nibble = ch.digitToIntOrNull(16) ?: return null
            for (shift in 3 downTo 0) {
                if (bitIndex >= totalBits) return out
                val bit = (nibble shr shift) and 1
                out[bitIndex] = (bit == 1)
                bitIndex++
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Helper: BluetoothDevice -> BtDevice (for UI lists)
    // -------------------------------------------------------------------------
    private fun android.bluetooth.BluetoothDevice.toBtDevice(bonded: Boolean? = null): BtDevice {
        val safeName = try { name } catch (_: SecurityException) { null }
        val safeAddr = try { address } catch (_: SecurityException) { "unknown" }
        val isBonded = bonded ?: (bondState == android.bluetooth.BluetoothDevice.BOND_BONDED)

        return BtDevice(
            name = safeName,
            address = safeAddr,
            bonded = isBonded
        )
    }
}
